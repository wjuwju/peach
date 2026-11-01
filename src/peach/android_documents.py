"""通过安卓授权目录读取媒体，不把 content URI 当作磁盘路径。"""
from __future__ import annotations

import io
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import socket
import struct
import tempfile

from .fsutil import atomic_write_text
from .smb_media import SmbResource
from .smb_transport import SmbEntry, SmbStat

MOUNTS = Path('/media/android')
REGISTRY = 'android-picker-roots.json'


def validate_data_path(path):
    """授权媒体的映射目录不提供 SQLite 所需的本机文件语义。"""
    if os.environ.get('PEACH_ANDROID') == '1' and any(path.is_relative_to(root) for root in (MOUNTS, Path('/media/cloud'))):
        raise ValueError('手机授权目录和云盘目录用于媒体；Peach 数据目录请保留应用内的 /data')


def _receive(stream, count):
    result = bytearray()
    while len(result) < count:
        chunk = stream.recv(count - len(result))
        if not chunk:
            raise OSError('安卓目录连接已中断')
        result.extend(chunk)
    return bytes(result)


def request(config, operation, *, payload=None, timeout=60, **fields):
    """每次读取当前服务凭据；随机口令只存在应用私有目录及回环连接中。"""
    endpoint = json.loads((config.directory('state') / 'android-bridge.json').read_text(encoding='utf-8'))
    if not isinstance(endpoint.get('port'), int) or not 1 <= endpoint['port'] <= 65535:
        raise OSError('安卓目录服务地址无效')
    secret = endpoint.get('secret', '')
    if not isinstance(secret, str) or len(secret) != 64:
        raise OSError('安卓目录服务凭据无效')
    length = 0
    if payload is not None:
        length = payload.seek(0, io.SEEK_END)
        payload.seek(0)
    body = json.dumps({'op': operation, 'secret': secret, 'length': length, **fields}).encode('utf-8')
    with socket.create_connection(('127.0.0.1', endpoint['port']), timeout=timeout) as stream:
        stream.sendall(struct.pack('!I', len(body)) + body)
        if payload is not None:
            while chunk := payload.read(256 * 1024):
                stream.sendall(chunk)
        size = struct.unpack('!I', _receive(stream, 4))[0]
        if not 0 < size <= 16 * 1024 * 1024:
            raise OSError('安卓目录响应无效')
        reply = json.loads(_receive(stream, size))
        error = reply.get('error')
        if error:
            kind = {'missing': FileNotFoundError, 'exists': FileExistsError, 'permission': PermissionError}.get(error, OSError)
            raise kind(reply.get('message', '安卓目录操作未完成'))
        size = reply.get('length', 0)
        if not isinstance(size, int) or not 0 <= size <= 8 * 1024 * 1024:
            raise OSError('安卓目录读取长度无效')
        return reply, _receive(stream, size)


def pick_folder(initial=None):
    from . import settings_file
    from .folder_picker import DIALOG_TIMEOUT_SECONDS, PickerUnavailable
    config = settings_file.active()
    try:
        result, _ = request(config, 'pick', initial=initial, timeout=DIALOG_TIMEOUT_SECONDS + 10)
        if result.get('cancelled'):
            return None
        identifier, kind = result.get('id'), result.get('kind')
        if not isinstance(identifier, str) or len(identifier) != 64 or any(c not in '0123456789abcdef' for c in identifier):
            raise ValueError('安卓目录身份无效')
        if kind not in {'document', 'cloud'}:
            raise ValueError('安卓目录类型无效')
        if kind == 'cloud':
            from .android_integration import connect
            from .android_cloud import CloudConnection, CloudTransport
            remote, token = result.get('remote'), result.get('token')
            if (not isinstance(remote, str) or not remote.startswith('/')
                    or '..' in PurePosixPath(remote).parts or '\\' in remote or '\x00' in remote):
                raise ValueError('云盘目录无效')
            if not isinstance(token, str) or not token or len(token) > 16384 or any(c in token for c in '\r\n'):
                raise ValueError('请先登录内置 CloudDrive2')
            with CloudTransport(CloudConnection(remote, token)) as transport:
                if not transport.stat(PurePosixPath(remote).parts[1:]).is_dir:
                    raise NotADirectoryError('请选择云盘文件夹')
            connect(config, {'token': token})
        mount = MOUNTS / identifier
        mount.mkdir(parents=True, exist_ok=True)
        registry = config.directory('state') / REGISTRY
        mapping = json.loads(registry.read_text(encoding='utf-8')) if registry.exists() else {}
        mapping[str(mount)] = {'kind': kind, 'id': identifier}
        if kind == 'cloud':
            mapping[str(mount)]['remote'] = remote
        atomic_write_text(registry, json.dumps(mapping, ensure_ascii=False))
        return str(mount)
    except (OSError, ValueError, KeyError) as error:
        raise PickerUnavailable(str(error)) from None


def selection(config, mount):
    registry = config.directory('state') / REGISTRY
    if not registry.is_file():
        return None
    return json.loads(registry.read_text(encoding='utf-8')).get(str(mount))


def resolve(raw, config):
    if os.environ.get('PEACH_ANDROID') != '1':
        return None
    for root_id, declared in config.storage_roots.items():
        selected = selection(config, declared['mount'])
        if not selected or selected['kind'] != 'document':
            continue
        for prefix, cls in ((declared['root'], PureWindowsPath), (declared['mount'], PurePosixPath)):
            path, root = cls(str(raw)), cls(prefix)
            if path.is_relative_to(root):
                return DocumentResource(root_id, declared['root'], 'android:' + selected['id'], (),
                                        (config, selected['id'])).child_relative(path.relative_to(root).parts)
    return None


class DocumentResource(SmbResource):
    @property
    def name(self):
        return self.relative[-1] if self.relative else '手机目录'

    @property
    def remote_parts(self):
        return self.relative

    def _transport(self):
        return DocumentTransport(*self.connection)


class DocumentTransport:
    def __init__(self, config, identifier):
        self.config, self.identifier = config, identifier

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        pass

    def call(self, operation, parts, **fields):
        if any(part in {'', '.', '..'} or any(c in part for c in '/\\\x00') for part in parts):
            raise ValueError('安卓目录越界')
        return request(self.config, operation, id=self.identifier, parts=list(parts), **fields)

    @staticmethod
    def attributes(value):
        return SmbEntry(value['size'], value['modified'] / 1000, value['modified'] * 1000000,
                        value['directory'], value['name'])

    def stat(self, parts):
        reply, _ = self.call('stat', parts)
        return self.attributes(reply)

    def scandir(self, parts):
        reply, _ = self.call('list', parts)
        return [self.attributes(row) for row in reply['entries']]

    def open(self, parts):
        return DocumentReader(self, parts, self.stat(parts))

    def open_write(self, parts, mode='wb'):
        return DocumentWriter(self, parts, mode)

    def mkdir(self, parts):
        self.call('mkdir', parts)

    def unlink(self, parts, directory=False):
        self.call('delete', parts, directory=directory)

    def rename(self, source, target):
        # 目标也过相同边界，不允许原生桥把分隔符解释成文档名。
        self.call('stat', target[:-1])
        if not target or any(p in {'', '.', '..'} or any(c in p for c in '/\\\x00') for p in target):
            raise ValueError('安卓目录越界')
        self.call('rename', source, target=list(target))


class DocumentReader(io.RawIOBase):
    def __init__(self, transport, parts, attributes):
        self.transport, self.parts, self.attributes = transport, parts, attributes
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
        data = bytearray()
        while count:
            requested = min(count, 1024 * 1024)
            _, chunk = self.transport.call('read', self.parts, offset=self.position, count=requested)
            if len(chunk) != requested:
                raise OSError('安卓媒体读取不完整')
            self.position += len(chunk)
            data.extend(chunk)
            count -= len(chunk)
        return bytes(data)


class DocumentWriter(io.IOBase):
    def __init__(self, transport, parts, mode):
        self.transport, self.parts, self.mode = transport, parts, mode
        self.spool = tempfile.SpooledTemporaryFile(max_size=8 * 1024 * 1024)
        if mode in {'ab', 'r+b'}:
            try:
                with transport.open(parts) as source:
                    while chunk := source.read(1024 * 1024):
                        self.spool.write(chunk)
            except FileNotFoundError:
                if mode == 'r+b':
                    self.spool.close()
                    raise
            if mode == 'r+b':
                self.spool.seek(0)

    def write(self, data):
        return self.spool.write(data)

    def read(self, size=-1):
        return self.spool.read(size)

    def seek(self, offset, whence=0):
        return self.spool.seek(offset, whence)

    def tell(self):
        return self.spool.tell()

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
            self.transport.call('write', self.parts, payload=self.spool, exclusive=self.mode == 'xb')
        finally:
            self.spool.close()
            super().close()
