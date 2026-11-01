"""把已核对的 Linux、锁定依赖和原版源码封装成安卓离线运行环境。"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy
import shutil
import stat
import subprocess
import zipfile

PROJECT = Path(__file__).resolve().parents[1]


def copy_source(root):
    app = root / 'app'
    app.mkdir(exist_ok=True)
    for name in ('src', 'web', 'resources', 'migrations', 'tools', 'scripts', 'frontend'):
        target = app / name
        models = target / 'face-models'
        saved_models = root / 'face-models-build-cache'
        if name == 'tools' and models.is_dir():
            if saved_models.exists():
                shutil.rmtree(saved_models)
            shutil.move(models, saved_models)
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(PROJECT / name, target, dirs_exist_ok=True, ignore=shutil.ignore_patterns(
            '.git', '.venv', '__pycache__', 'node_modules', 'build', '*.pyc'))
        if name == 'tools' and saved_models.is_dir():
            shutil.move(saved_models, models)
    for name in ('LICENSE', 'README.md', 'pyproject.toml', 'uv.lock', 'setup.py', 'MANIFEST.in'):
        shutil.copy2(PROJECT / name, app / name)


def amane(root, packages, work):
    destination = root / 'app/amane-runtime'
    site = destination / '.venv/lib/python3.14/site-packages'
    shutil.copytree(packages, site, dirs_exist_ok=True, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    binary = destination / '.venv/bin'
    binary.mkdir(parents=True, exist_ok=True)
    python = binary / 'python'
    if not python.is_symlink():
        python.symlink_to('/usr/local/bin/python3.14')
    (destination / '.venv/pyvenv.cfg').write_text('home = /usr/local/bin\ninclude-system-site-packages = false\nversion = 3.14.8\n')
    builder = runpy.run_path(str(PROJECT / 'scripts/build_amane_runtime.py'))
    facts = {**builder['locked_amane'](), 'kind': 'python', 'executable': '.venv/bin/python'}
    if not (destination / 'amane-corresponding-source.zip').is_file():
        work.mkdir(parents=True, exist_ok=True)
        builder['corresponding_source'](destination, work, facts)
    else:
        facts['source_sha256'] = hashlib.sha256((destination / 'amane-corresponding-source.zip').read_bytes()).hexdigest()
    (destination / 'manifest.json').write_text(json.dumps(facts, ensure_ascii=False, indent=2))


def prepare_library_links(root, abi):
    """补齐仅解包 Debian 依赖时未执行 update-alternatives 的数学库入口。"""
    multiarch = {'arm64-v8a': 'aarch64-linux-gnu', 'x86_64': 'x86_64-linux-gnu'}[abi]
    directory = root / 'usr/lib' / multiarch
    for library in ('blas', 'lapack'):
        name = f'lib{library}.so.3'
        target = directory / library / name
        if not target.is_file():
            raise ValueError(f'运行环境缺少 {multiarch}/{library}/{name}')
        link = directory / name
        if link.is_file():
            continue
        if link.is_symlink():
            link.unlink()
        link.symlink_to(f'{library}/{name}')


def package(root, termux, output, abi):
    prepare_library_links(root, abi)
    native = PROJECT / 'android/app/src/main/jniLibs' / abi
    native.mkdir(parents=True, exist_ok=True)
    for source, target in [('bin/proot', 'libproot.so'), ('libexec/proot/loader', 'libprootloader.so'),
                           ('lib/libtalloc.so.2', 'libtalloc.so'), ('lib/libandroid-shmem.so', 'libandroid-shmem.so')]:
        shutil.copyfile(termux / source, native / target)
    for name in ('data', 'media', 'proc', 'sys', 'dev', 'tmp', 'root'):
        (root / name).mkdir(exist_ok=True)
    if any((root / 'data').iterdir()) or any((root / 'media').iterdir()):
        raise ValueError('运行环境不得包含用户数据或媒体')
    passwd = root / 'etc/passwd'
    value = passwd.read_text()
    if not any(row.split(':')[2] == '1000' for row in value.splitlines() if row.count(':') >= 2):
        passwd.write_text(value + 'peach:x:1000:1000:Peach:/root:/bin/sh\n')
    output.mkdir(parents=True, exist_ok=True)
    archive_path = output / '.runtime.zip.part'
    modes, links = {}, {}
    with zipfile.ZipFile(archive_path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(root.rglob('*')):
            relative = path.relative_to(root).as_posix()
            if '__pycache__' in path.parts or path.suffix == '.pyc':
                continue
            if path.is_symlink():
                target = os.readlink(path)
                if target.startswith('/'):
                    target = os.path.relpath(root / target.lstrip('/'), path.parent)
                links[relative] = target
            elif path.is_file():
                modes[relative] = stat.S_IMODE(path.stat().st_mode)
                archive.write(path, relative)
            elif path.is_dir():
                modes[relative] = stat.S_IMODE(path.stat().st_mode)
                archive.writestr(relative + '/', b'')
        archive.writestr('runtime-links.json', json.dumps({'modes': modes, 'links': links}))
    with archive_path.open('rb') as file:
        digest = hashlib.file_digest(file, 'sha256').hexdigest()
    archive_path = archive_path.replace(output / 'runtime.zip')
    tree = subprocess.check_output(['git', '-C', str(PROJECT), 'rev-parse', '462543c224164c110eb912bb772f1ab8a684597a:web'], text=True).strip()
    manifest = {'version': digest[:32], 'sha256': digest, 'abi': abi, 'peach_version': '0.52.0',
                'original_commit': '462543c224164c110eb912bb772f1ab8a684597a', 'original_web_tree': tree,
                'archive_bytes': archive_path.stat().st_size, 'entries': len(modes) + len(links)}
    (output / 'runtime.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    print(json.dumps(manifest), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rootfs', required=True, type=Path)
    parser.add_argument('--amane-packages', required=True, type=Path)
    parser.add_argument('--termux-prefix', required=True, type=Path)
    parser.add_argument('--work', required=True, type=Path)
    parser.add_argument('--abi', choices=('arm64-v8a', 'x86_64'), default='arm64-v8a')
    args = parser.parse_args()
    runtime = args.rootfs.resolve()
    copy_source(runtime)
    amane(runtime, args.amane_packages.resolve(), args.work.resolve())
    package(runtime, args.termux_prefix.resolve(), PROJECT / 'android/runtime-assets' / args.abi / 'peach', args.abi)
