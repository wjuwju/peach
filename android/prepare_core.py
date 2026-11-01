"""将用户自行下载的官方安卓核心放入仅本机使用的构建输入。"""
from __future__ import annotations

import argparse
import hashlib
import shutil
import tarfile
from pathlib import Path, PurePosixPath


def prepare(archive: Path, expected: str, project: Path, abi: str = 'arm64-v8a') -> int:
    with archive.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    if digest != expected:
        raise ValueError(f'核心 SHA-256 不匹配：{digest}')
    upstream_arch = {'arm64-v8a': 'aarch64', 'x86_64': 'x86_64'}[abi]
    native = project / f'app/src/main/jniLibs/{abi}/libclouddrive.so'
    assets = project / 'app/src/main/assets/clouddrive'
    count = 0
    with tarfile.open(archive) as bundle:
        for member in bundle:
            parts = PurePosixPath(member.name).parts
            if not parts or parts[0] != f'clouddrive-2-android-{upstream_arch}-1.1.3':
                raise ValueError('归档不是指定版本和架构的官方安卓核心')
            relative = PurePosixPath(*parts[1:])
            if '..' in parts or relative.is_absolute() or member.issym() or member.islnk():
                raise ValueError('核心归档包含不支持的路径或链接')
            if not member.isfile():
                continue
            if relative.as_posix() == 'clouddrive':
                target = native
            elif relative.parts and relative.parts[0] == 'wwwroot':
                target = assets.joinpath(*relative.parts)
            else:
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with bundle.extractfile(member) as source, target.open('wb') as output:
                shutil.copyfileobj(source, output)
            count += 1
    if not native.is_file() or not (assets / 'wwwroot/index.html').is_file():
        raise ValueError('核心或官方管理界面缺失')
    return count


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--abi', choices=['arm64-v8a', 'x86_64'], default='arm64-v8a')
    args = parser.parse_args()
    print(f'已准备 {prepare(args.archive, args.sha256, Path(__file__).resolve().parent, args.abi)} 个本机构建文件')
