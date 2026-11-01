"""安卓本机 CloudDrive2 媒体资源，复用远程资源的扫描、整理与播放管线。"""
from __future__ import annotations

import io
import json
import os
import posixpath
import tempfile
from contextlib import contextmanager
from uuid import uuid4
from dataclasses import dataclass, field
from pathlib import PurePosixPath, PureWindowsPath
from urllib.parse import urljoin, urlsplit

import grpc
import httpx

from . import downloads_clouddrive_pb2 as pb
from .downloads import CLOUDDRIVE_CREDENTIAL, DownloadError
from .downloads_clouddrive import CloudDriveClient, SERVICE
from .follow_secrets import CredentialStore
from .smb_media import SmbResource
from .smb_transport import SmbEntry, SmbStat

ADDRESS = 'http://127.0.0.1:19798'


class CloudMoveUnconfirmed(OSError):
    """保留当前路径与原版整理意图，等待用户核对。"""


@contextmanager
def filesystem_errors():
    try:
        yield
    except DownloadError as error:
        raise OSError(error.detail) from None
    except httpx.HTTPError:
        raise OSError('云盘文件传输失败') from None


@dataclass(frozen=True)
class CloudConnection:
    remote: str
    token: str = field(repr=False)
    timeout: float = 20


class CloudResource(SmbResource):
    @property
    def name(self):
        return self.relative[-1] if self.relative else PurePosixPath(self.connection.remote).name

    @property
    def remote_parts(self):
        return (*PurePosixPath(self.connection.remote).parts[1:], *self.relative)

    def _transport(self):
        return CloudTransport(self.connection)


def resolve(raw, config):
    if os.environ.get('PEACH_ANDROID') != '1':
        return None
    if isinstance(raw, CloudResource):
        return raw
    mapping_path = config.directory('state') / 'android-cloud-roots.json'
    if not mapping_path.is_file():
        return None
    mapping = json.loads(mapping_path.read_text(encoding='utf-8'))
    text = str(raw)
    for root_id, remote in mapping.items():
        declared = config.storage_roots.get(root_id)
        if not declared:
            continue
        for prefix, kind in ((declared['root'], PureWindowsPath), (declared['mount'], PurePosixPath)):
            candidate, root = kind(text), kind(prefix)
            if candidate == root or candidate.is_relative_to(root):
                credential = CredentialStore(config.directory('secrets')).load(CLOUDDRIVE_CREDENTIAL)
                token = credential.require('token')[0] if credential else ''
                if not token:
                    raise PermissionError('请在内置 CloudDrive2 中登录并连接 Peach')
                resource = CloudResource(root_id, declared['root'], ADDRESS, (), CloudConnection(remote, token))
                return resource.child_relative(candidate.relative_to(root).parts)
    return None


class CloudTransport:
    def __init__(self, connection):
        self.connection = connection
        self.client = CloudDriveClient(ADDRESS, connection.token, timeout=connection.timeout)
        self.http = httpx.Client(timeout=connection.timeout, follow_redirects=False, trust_env=False)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        self.http.close()
        self.client.close()

    def path(self, parts):
        if any(part in {'.', '..'} or any(c in part for c in '/\\\x00') for part in parts):
            raise ValueError('云盘路径越界')
        return '/' + '/'.join(parts)

    def _found(self, parts):
        with filesystem_errors():
            found = self.client.find(self.path(parts))
        if found is None:
            raise FileNotFoundError('云盘文件不存在')
        return found

    @staticmethod
    def attributes(file):
        nanoseconds = file.writeTime.seconds * 1_000_000_000 + file.writeTime.nanos
        return SmbEntry(file.size, nanoseconds / 1_000_000_000, nanoseconds, file.isDirectory, file.name)

    def stat(self, parts):
        return self.attributes(self._found(parts))

    def scandir(self, parts):
        stream = self.client.channel.unary_stream(SERVICE + 'GetSubFiles',
            request_serializer=pb.ListSubFileRequest.SerializeToString,
            response_deserializer=pb.SubFilesReply.FromString)
        entries = []
        try:
            for response in stream(pb.ListSubFileRequest(path=self.path(parts)), timeout=self.connection.timeout,
                                   metadata=(('authorization', 'Bearer ' + self.connection.token),)):
                for file in response.subFiles:
                    self.path((file.name,))
                    entries.append(self.attributes(file))
        except grpc.RpcError as error:
            if error.code() == grpc.StatusCode.NOT_FOUND:
                raise FileNotFoundError('云盘目录不存在') from None
            if error.code() in {grpc.StatusCode.PERMISSION_DENIED, grpc.StatusCode.UNAUTHENTICATED}:
                raise PermissionError('云盘目录无读取权限或登录已过期') from None
            raise OSError('云盘目录读取失败') from None
        return entries

    def open(self, parts):
        with filesystem_errors():
            info = self.client.call('GetDownloadUrlPath', pb.GetDownloadUrlPathRequest(
                path=self.path(parts), preview=False, lazy_read=True, get_direct_url=False), '读取媒体')
        value = info.downloadUrlPath.replace('{SCHEME}', 'http').replace('{HOST}', '127.0.0.1:19798').replace('{PREVIEW}', 'false')
        url = urljoin(ADDRESS, value)
        parsed = urlsplit(url)
        if (parsed.scheme, parsed.hostname, parsed.port) != ('http', '127.0.0.1', 19798) or parsed.username or parsed.password:
            raise ValueError('核心返回的读取地址不属于本机服务')
        return CloudReader(self, url, self.stat(parts))

    def checked(self, method, request):
        with filesystem_errors():
            result = self.client.call(method, request, '保存云盘文件')
        if not result.success:
            raise OSError(result.errorMessage or '云盘操作未完成')

    def rename(self, source, target):
        old, new = self.path(source), self.path(target)
        if posixpath.dirname(old) == posixpath.dirname(new) or posixpath.basename(old) == posixpath.basename(new):
            self._move_confirmed(old, new)
        else:
            if self.client.find(new) is not None:
                raise FileExistsError('目标文件已存在')
            temporary = '.peach-move-' + uuid4().hex + posixpath.splitext(old)[1]
            current = old
            try:
                staged = posixpath.join(posixpath.dirname(old), temporary)
                self._move_confirmed(current, staged)
                current = staged
                staged = posixpath.join(posixpath.dirname(new), temporary)
                self._move_confirmed(current, staged)
                current = staged
                self._move_confirmed(current, new)
            except CloudMoveUnconfirmed:
                raise
            except Exception as original:
                try:
                    if posixpath.dirname(current) != posixpath.dirname(old):
                        restored = posixpath.join(posixpath.dirname(old), posixpath.basename(current))
                        self._move_confirmed(current, restored)
                        current = restored
                    if current != old:
                        self._move_confirmed(current, old)
                except Exception:
                    raise OSError(f'云盘移动未完成，文件保留在 {current}，请在云盘管理中检查') from original
                raise

    def _move_confirmed(self, source, target):
        from .organize_clouddrive import (PikPakDav, PikPakPaths, UnconfirmedMove,
                                          _cloud_listing, _move_remote, _rename_remote, _refresh_listing)
        mapping = PikPakPaths('R:\\AndroidCloud', self.connection.remote)
        def ledger(path):
            tail = PurePosixPath(path).relative_to(PurePosixPath(self.connection.remote))
            return str(PureWindowsPath(mapping.root, *tail.parts))
        class Moves(PikPakDav):
            def ensure_parent(inner, path):
                parent = PurePosixPath(mapping.cloud_path(path)).parent.parts[1:]
                if not self._found(parent).isDirectory:
                    raise NotADirectoryError('云盘目标父目录不存在')
        verifier = Moves(self.http, mapping, lambda path: _refresh_listing(self.client, path, mapping),
                         listing=lambda path: _cloud_listing(self.client, path, mapping),
                         rename=lambda old, new: _rename_remote(self.client, old, new, mapping),
                         move_remote=lambda old, new: _move_remote(self.client, old, new, mapping), confirm_attempts=2)
        try:
            verifier.move(ledger(source), ledger(target))
        except UnconfirmedMove:
            raise CloudMoveUnconfirmed(f'云盘移动状态未确认，未更新账本；请检查 {source} 和 {target}') from None

    def unlink(self, parts, directory=False):
        if directory and self.scandir(parts):
            raise OSError('目录非空，未删除')
        self.checked('DeleteFile', pb.FileRequest(path=self.path(parts)))

    def mkdir(self, parts):
        if self.client.find(self.path(parts)) is not None:
            raise FileExistsError('云盘目录已存在')
        self.client.create_folder(self.path(parts))

    def open_write(self, parts, mode='wb'):
        if mode == 'xb' and self.client.find(self.path(parts)) is not None:
            raise FileExistsError('云盘文件已存在')
        return CloudWriter(self, parts, mode)


class CloudReader(io.RawIOBase):
    def __init__(self, transport, url, attributes):
        self.transport, self.url, self.attributes = transport, url, attributes
        self.position = 0

    def readable(self):
        return True

    def seekable(self):
        return True

    def stat(self):
        return self.attributes

    def tell(self):
        return self.position

    def seek(self, offset, whence=0):
        target = offset if whence == 0 else self.position + offset if whence == 1 else self.attributes.size + offset
        if whence not in (0, 1, 2) or target < 0:
            raise ValueError('媒体读取位置无效')
        self.position = target
        return target

    def read(self, size=-1):
        if self.closed:
            raise ValueError('媒体句柄已关闭')
        remaining = max(0, self.attributes.size - self.position)
        count = remaining if size is None or size < 0 else min(size, remaining)
        if not count:
            return b''
        end = self.position + count - 1
        with filesystem_errors(), self.transport.http.stream('GET', self.url, headers={'Range': f'bytes={self.position}-{end}', 'Accept-Encoding': 'identity'}) as response:
            if response.status_code != 206:
                raise OSError('云盘服务未返回指定字节范围')
            if response.headers.get('content-range', '') != f'bytes {self.position}-{end}/{self.attributes.size}':
                raise OSError('云盘服务返回的媒体范围不匹配')
            data = response.read()
        if len(data) != count:
            raise OSError('云盘媒体读取不完整')
        self.position += len(data)
        return data


class CloudWriter(io.IOBase):
    def __init__(self, transport, parts, mode):
        self.transport, self.parts = transport, parts
        self.spool = tempfile.SpooledTemporaryFile(max_size=8 * 1024 * 1024)
        if mode in {'ab', 'r+b'}:
            with transport.open(parts) as source:
                while chunk := source.read(1024 * 1024):
                    self.spool.write(chunk)
            if mode == 'r+b':
                self.spool.seek(0)

    def write(self, data):
        return self.spool.write(data)

    def read(self, size=-1):
        return self.spool.read(size)

    def tell(self):
        return self.spool.tell()

    def seek(self, offset, whence=0):
        return self.spool.seek(offset, whence)

    def stat(self):
        position = self.spool.tell()
        size = self.spool.seek(0, io.SEEK_END)
        self.spool.seek(position)
        return SmbStat(size, 0, 0, False, self.parts[-1])

    def flush(self):
        if not self.spool.closed:
            self.spool.flush()

    def close(self):
        if self.closed:
            return
        try:
            path = self.transport.path(self.parts)
            handle = self.transport.client.call('CreateFile', pb.CreateFileRequest(
                parentPath=posixpath.dirname(path), fileName=posixpath.basename(path)), '写入配套文件').fileHandle
            position = 0
            self.spool.seek(0)
            try:
                while chunk := self.spool.read(256 * 1024):
                    result = self.transport.client.call('WriteToFile', pb.WriteFileRequest(
                        fileHandle=handle, startPos=position, length=len(chunk), buffer=chunk), '写入配套文件')
                    if result.bytesWritten != len(chunk):
                        raise OSError('云盘文件未完整写入')
                    position += len(chunk)
            finally:
                self.transport.checked('CloseFile', pb.CloseFileRequest(fileHandle=handle, uploadImmediately=True))
        finally:
            self.spool.close()
            super().close()
