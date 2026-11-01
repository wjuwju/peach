"""按已核对的输入摘要准备完整 ARM64 Peach 运行环境，无需 Root 或 Docker 守护进程。"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import runpy
import shutil
import subprocess
import tarfile
import urllib.request

PROJECT = Path(__file__).resolve().parents[1]
LOCK = Path(__file__).with_name('runtime-inputs.json')


def request(url, headers=None):
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=120) as response:
        return response.read()


def verified(url, sha256, path, headers=None):
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != sha256:
        value = request(url, headers)
        if hashlib.sha256(value).hexdigest() != sha256:
            raise ValueError('构建输入 SHA-256 不符：' + path.name)
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(path.suffix + '.part')
        temporary.write_bytes(value)
        temporary.replace(path)
    return path


def image(cache, root, locked):
    token = json.loads(request('https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/python:pull'))['token']
    headers = {'Authorization': 'Bearer ' + token,
               'Accept': 'application/vnd.oci.image.manifest.v1+json'}
    digest = locked['digest'].removeprefix('sha256:')
    manifest = verified('https://registry-1.docker.io/v2/library/python/manifests/sha256:' + digest,
                        digest, cache / 'python-manifest.json', headers)
    for layer in json.loads(manifest.read_text())['layers']:
        digest = layer['digest'].removeprefix('sha256:')
        archive_path = verified('https://registry-1.docker.io/v2/library/python/blobs/sha256:' + digest,
                                digest, cache / (digest + '.tar.gz'), headers)
        with tarfile.open(archive_path) as archive:
            members = []
            for member in archive.getmembers():
                if member.ischr() or member.isblk() or member.isfifo():
                    continue
                if PurePosixPath(member.name).name.startswith('.wh.'):
                    raise ValueError('镜像层包含需要单独处理的删除记录')
                if member.issym() and member.linkname.startswith('/'):
                    member = member.replace(linkname=os.path.relpath(root / member.linkname.lstrip('/'),
                                                                     (root / member.name).parent))
                members.append(member)
            archive.extractall(root, members=members, filter='data')


def debs(cache, root, packages, origin):
    def fetch(row):
        path = cache / Path(row['Filename']).name
        return verified(origin + row['Filename'], row['SHA256'], path)
    with ThreadPoolExecutor(max_workers=6) as executor:
        paths = list(executor.map(fetch, packages))
    for path in paths:
        subprocess.run(['dpkg-deb', '-x', str(path), str(root)], check=True)


def python_dependencies(cache, root):
    for name, project, target, source_allowed in [
        ('peach', PROJECT, root / 'usr/local/lib/python3.14/site-packages', []),
        ('amane', PROJECT / 'tools/amane-bridge', cache / 'amane-packages', ['amane', 'zhconv']),
    ]:
        requirements = cache / (name + '-requirements.txt')
        command = ['uv', 'export', '--locked', '--no-dev', '--no-emit-project', '--project', str(project),
                   '--format', 'requirements-txt', '--output-file', str(requirements)]
        if name == 'peach':
            command.extend(['--extra', 'vision'])
        subprocess.run(command, check=True)
        command = ['uv', 'pip', 'install', '--target', str(target), '--python-platform', 'aarch64-manylinux_2_28',
                   '--python-version', '3.14', '--no-deps', '--only-binary', ':all:', '-r', str(requirements)]
        for package in source_allowed:
            command.extend(['--no-binary', package])
        subprocess.run(command, check=True)
    return cache / 'amane-packages'


def prepare(cache):
    cache.mkdir(parents=True, exist_ok=True)
    root = cache / 'rootfs-arm64'
    if root.exists():
        raise ValueError('输出根目录已存在；请使用新的构建目录，避免混入旧文件或用户数据')
    root.mkdir()
    locked = json.loads(LOCK.read_text())
    image(cache, root, locked['python'])
    debs(cache, root, locked['debian'], 'https://deb.debian.org/debian/')
    termux = cache / 'termux-arm64'
    debs(cache, termux, locked['termux'], 'https://packages.termux.dev/apt/termux-main/')
    amane_packages = python_dependencies(cache, root)
    packager = runpy.run_path(str(PROJECT / 'android/package_runtime.py'))
    packager['copy_source'](root)
    models = runpy.run_path(str(PROJECT / 'scripts/fetch_face_models.py'))
    models['install'](root / 'app/tools/face-models', cache=cache / 'model-cache')
    packager['amane'](root, amane_packages, cache / 'amane-source')
    packager['package'](root, termux / 'data/data/com.termux/files/usr',
                        PROJECT / 'android/runtime-assets/arm64-v8a/peach', 'arm64-v8a')
    shutil.copyfile(LOCK, PROJECT / 'android/runtime-assets/arm64-v8a/peach/runtime-inputs.json')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    prepare(parser.parse_args().work.resolve())
