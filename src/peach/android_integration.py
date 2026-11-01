"""将安卓内置云盘账号和目录接入原版配置与媒体处理。"""
from __future__ import annotations

import json
from pathlib import Path, PurePosixPath
from uuid import uuid4

from . import media_identity, onboarding, settings_file
from .android_cloud import ADDRESS, CloudConnection, CloudTransport
from .downloads import CLOUDDRIVE_CREDENTIAL, DownloadSettings
from .downloads_clouddrive import CloudDriveClient
from .follow_secrets import CredentialStore
from .fsutil import atomic_write_text
from .media_sources import SOURCE_COSTS
from .push_discovery import PushDiscoverySettings

CLOUD_MEDIA = Path('/media/cloud')


def _link_prefix(config, root_id, remote):
    settings = PushDiscoverySettings(config.directory('state'))
    payload = settings.load(config.locations).payload()
    root = config.storage_roots[root_id]['root']
    rows = list(payload['prefixes'])
    if not any(row['prefix'] == remote and row['root'] == root for row in rows):
        rows.append({'prefix': remote, 'root': root})
        settings.save({**payload, 'prefixes': rows}, config.locations)


def _selection(body):
    token, remote, name = body.get('token', ''), body.get('path', ''), body.get('name', '')
    if not isinstance(token, str) or not token or len(token) > 16384 or '\n' in token or '\r' in token:
        raise ValueError('请先登录内置 CloudDrive2')
    if not isinstance(remote, str) or not isinstance(name, str):
        raise ValueError('目录和媒体库名称必须是文字')
    if remote and (not remote.startswith('/') or '..' in PurePosixPath(remote).parts or any(ord(c) < 32 for c in remote)):
        raise ValueError('云盘目录必须是完整路径，不能包含上级目录')
    if remote and (not name.strip() or len(name) > 80 or any(ord(c) < 32 for c in name)):
        raise ValueError('媒体库名称请使用 1 到 80 个可见字符')
    return token, remote, name


def connect(config, body):
    token, remote, name = _selection(body)
    with CloudTransport(CloudConnection('/', token)) as transport:
        transport.scandir(())
    found = None
    if remote:
        with CloudDriveClient(ADDRESS, token) as client:
            found = client.find(remote)
        if found is None or not found.isDirectory:
            raise ValueError('CloudDrive2 中未找到这个目录')
        remote = found.fullPathName or remote
    CredentialStore(config.directory('secrets')).save(CLOUDDRIVE_CREDENTIAL, {'token': token})
    downloads = DownloadSettings(config.directory('state'))
    downloads.save({**downloads.load().payload(), 'clouddrive_address': ADDRESS}, config.locations)
    if not remote:
        return {'connected': True}
    mapping_path = config.directory('state') / 'android-cloud-roots.json'
    mapping = json.loads(mapping_path.read_text(encoding='utf-8')) if mapping_path.exists() else {}
    for root_id, path in mapping.items():
        if path == remote and root_id in config.storage_roots:
            _link_prefix(config, root_id, remote)
            return {'connected': True, 'root_id': root_id, 'already_added': True}
    libraries, roots = media_identity.catalog(config)
    libraries, roots = dict(libraries), dict(roots)
    root_id, library_id = str(uuid4()), str(uuid4())
    cloud = found.CloudAPI.name.casefold()
    location = {'115open': '115', '115': '115', 'pikpak': 'pikpak', 'onedrive': 'onedrive',
                'guangya': 'guangya', 'guangyapan': 'guangya'}.get(cloud, 'mounted')
    mount = CLOUD_MEDIA / root_id
    mount.mkdir(parents=True, exist_ok=True)
    libraries[library_id] = {'name': name.strip(), 'icon': 'database'}
    roots[root_id] = {'root': 'R:\\CloudDrive\\' + root_id, 'location': location,
                      'library_id': library_id, 'mount': str(mount), 'cost': SOURCE_COSTS[location]}
    media_identity.validate_catalog(libraries, roots)
    prepared = media_identity.project(config, libraries, roots)
    # 先登记新映射；配置替换失败时原配置仍不引用它，旧媒体库保持可用。
    atomic_write_text(mapping_path, json.dumps({**mapping, root_id: remote}, ensure_ascii=False))
    settings_file.keep_pre_identity_backup(config.path)
    atomic_write_text(config.path, settings_file.render(prepared))
    _link_prefix(prepared, root_id, remote)
    state = config.directory('state')
    state.mkdir(parents=True, exist_ok=True)
    (state / onboarding.RELOAD_NAME).write_text('reload', encoding='utf-8')
    if body.get('scan_all_configured') is True:
        onboarding.request_first_scan(prepared, 'configured')
    return {'connected': True, 'root_id': root_id, 'name': name.strip()}
