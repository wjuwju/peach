"""安卓云盘资源接入原版扫描、字幕登记、Range 与配套文件写入。"""
from __future__ import annotations

import io
import json
import os
import sqlite3
import shutil
import subprocess
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
from uuid import uuid4

import httpx

from peach import android_cloud as cloud, android_integration, media_identity, scan, settings_file, smb_media
from peach import downloads_clouddrive_pb2 as pb
from peach.downloads import CLOUDDRIVE_CREDENTIAL
from peach.follow_secrets import CredentialStore
from peach.migrations import upgrade
from peach.push_discovery import PushDiscoverySettings
from peach.smb_transport import SmbEntry, SmbStat
from peach.ffmpeg import FFmpegResolver
from peach.transcodes import TranscodeService
from peach.smb_response import SmbResponse
from fastapi import FastAPI
from fastapi.testclient import TestClient
from support.conditions import missing_prerequisite


class AndroidCloudTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        config = settings_file.PeachConfig(self.root, self.root / 'config.toml', present=True, locations={})
        self.mount = self.root / 'media'
        self.mount.mkdir()
        self.root_id, library_id = str(uuid4()), str(uuid4())
        self.config = media_identity.project(config, {library_id: {'name': '云盘', 'icon': 'database'}}, {
            self.root_id: {'root': 'R:\\CloudDrive\\fixture', 'mount': str(self.mount), 'library_id': library_id,
                      'location': 'local', 'cost': 'free'}})
        state = self.config.directory('state')
        state.mkdir(parents=True, exist_ok=True)
        (state / 'android-cloud-roots.json').write_text(json.dumps({self.root_id: '/盘/电影'}))
        self.config.path.write_text(settings_file.render(self.config))
        CredentialStore(self.config.directory('secrets')).save(CLOUDDRIVE_CREDENTIAL, {'token': 'private-token'})
        self.environment = patch.dict(os.environ, {'PEACH_ANDROID': '1'})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def resource(self, suffix=''):
        return smb_media.resolve('R:\\CloudDrive\\fixture' + suffix, self.config)

    def test_declared_paths_resolve_with_credentials_and_refuse_escape(self):
        resource = self.resource('\\电影.mp4')
        self.assertEqual(resource.remote_parts, ('盘', '电影', '电影.mp4'))
        self.assertEqual(resource, smb_media.resolve(self.mount / '电影.mp4', self.config))
        self.assertNotIn('private-token', repr(resource))
        with self.assertRaises(ValueError):
            self.resource('\\..\\private.mp4')
        with self.assertRaises(PermissionError):
            self.resource().unlink()
        self.assertFalse(self.resource().is_relative_to('C:/other'))
        with patch.dict(os.environ, {'PEACH_ANDROID': '0'}):
            self.assertIsNone(self.resource('\\电影.mp4'))

    def test_scan_uses_original_ledger_and_subtitle_pairing(self):
        db = self.root / 'ledger.db'
        upgrade(db, Path(__file__).resolve().parents[1] / 'migrations')
        entries = [SmbEntry(10, 1700000000, 1700000000000000000, False, 'film.mp4'),
                   SmbEntry(20, 1700000000, 1700000000000000000, False, 'film.srt')]
        with patch('peach.settings_file.active', return_value=self.config), \
                patch.object(cloud.CloudResource, 'scandir', return_value=entries):
            result = scan.scan_location(db, 'local', 'R:\\CloudDrive\\fixture',
                                       declared_roots=self.config.locations, mounts=self.config.mounts,
                                       report=lambda _: None)
        self.assertEqual((result.files, result.subtitles), (2, 1))
        with closing(sqlite3.connect(db)) as connection:
            self.assertEqual(connection.execute("SELECT path FROM asset WHERE medium='video'").fetchone()[0],
                             'R:\\CloudDrive\\fixture\\film.mp4')
            self.assertEqual(connection.execute('SELECT COUNT(*) FROM asset_subtitle').fetchone()[0], 1)

    def test_seek_and_range_reject_wrong_or_incomplete_payload(self):
        requests = []
        def respond(request):
            requests.append(request.headers['range'])
            return httpx.Response(206, headers={'Content-Range': 'bytes 3-5/10'}, content=b'345')
        with httpx.Client(transport=httpx.MockTransport(respond)) as http:
            reader = cloud.CloudReader(SimpleNamespace(http=http), cloud.ADDRESS + '/file', SmbStat(10, 0, 0, False, 'file'))
            reader.seek(-7, io.SEEK_END)
            self.assertEqual(reader.read(3), b'345')
            self.assertEqual(reader.tell(), 6)
            self.assertEqual(requests, ['bytes=3-5'])
            with self.assertRaises(OSError):
                reader.read(3)
            reader.seek(100)
            self.assertEqual(reader.read(), b'')
            with self.assertRaises(ValueError):
                reader.seek(-1)

    def test_cloud_video_is_probed_and_served_through_the_original_media_pipeline(self):
        ffmpeg = shutil.which('ffmpeg')
        if not ffmpeg or not shutil.which('ffprobe'):
            missing_prerequisite('需要 FFmpeg 验证云盘媒体读取与播放响应')
        source = self.root / 'sample.mp4'
        generated = subprocess.run([ffmpeg, '-v', 'error', '-f', 'lavfi', '-i',
            'testsrc2=size=96x64:rate=10', '-t', '3', '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
            str(source)], capture_output=True, timeout=20)
        self.assertEqual(generated.returncode, 0, generated.stderr)
        data = source.read_bytes()
        requests = []
        def respond(request):
            requests.append(request.headers['range'])
            start, end, status = smb_media.byte_range(request.headers['range'], len(data))
            return httpx.Response(status, headers={'Content-Range': f'bytes {start}-{end}/{len(data)}'},
                                  content=data[start:end + 1])
        def transport():
            value = object.__new__(cloud.CloudTransport)
            value.connection = cloud.CloudConnection('/盘/电影', 'fixture-token')
            value.client = Mock()
            value.client.call.return_value = pb.DownloadUrlPathInfo(downloadUrlPath='/static/{SCHEME}/{HOST}/{PREVIEW}/sample.mp4')
            value.client.find.return_value = pb.CloudDriveFile(name='sample.mp4', fullPathName='/盘/电影/sample.mp4', size=len(data))
            value.http = httpx.Client(transport=httpx.MockTransport(respond), trust_env=False)
            return value
        service = TranscodeService(FFmpegResolver(self.root / 'tools'), self.root / 'transcodes', prefer_hardware=False)
        app = FastAPI()
        resource = self.resource('\\sample.mp4')
        @app.api_route('/video', methods=['GET', 'HEAD'])
        def video():
            from peach.media import FilesystemBackend
            from peach.repository import MediaAsset
            from peach.platform import translate_roots
            roots = translate_roots(self.config.locations['local'])
            filesystem = FilesystemBackend(roots, self.root / 'snapshots')
            resolved = filesystem.file_for(MediaAsset(1, str(resource), None))
            self.assertEqual(resolved, resource)
            path, converted = service.browser_path(1, resolved, convert=False)
            self.assertFalse(converted)
            return SmbResponse(path)
        try:
            with patch.object(cloud.CloudResource, '_transport', side_effect=transport), \
                    patch('peach.settings_file.active', return_value=self.config), TestClient(app) as client:
                self.assertFalse(service.requires_conversion(resource))
                result = client.get('/video', headers={'Range': 'bytes=0-1023'})
                self.assertEqual(result.status_code, 206)
                self.assertEqual(result.headers['content-type'], 'video/mp4')
                self.assertEqual(result.content, data[:1024])
                self.assertEqual(client.head('/video').headers['content-length'], str(len(data)))
                self.assertGreaterEqual(len(requests), 2)
        finally:
            smb_media.close_bridge()

    def test_writer_uses_exact_offsets_and_checks_upload_close(self):
        client = Mock()
        client.call.side_effect = [pb.CreateFileResult(fileHandle=7), pb.WriteFileResult(bytesWritten=3)]
        transport = SimpleNamespace(client=client, path=lambda parts: '/' + '/'.join(parts), checked=Mock())
        writer = cloud.CloudWriter(transport, ('电影', 'movie.nfo'), 'wb')
        writer.write(b'nfo')
        writer.close()
        request = client.call.call_args_list[1].args[1]
        self.assertEqual((request.fileHandle, request.startPos, request.length, request.buffer), (7, 0, 3, b'nfo'))
        self.assertTrue(transport.checked.call_args.args[1].uploadImmediately)
        writer.close()
        self.assertEqual(client.call.call_count, 2)

    def test_connect_reuses_download_settings_without_exposing_token(self):
        with patch.object(android_integration, 'CloudTransport') as transport:
            response = android_integration.connect(self.config, {'token': 'new-private-token'})
        transport.return_value.__enter__.return_value.scandir.assert_called_once_with(())
        self.assertEqual(response, {'connected': True})
        self.assertNotIn('new-private-token', json.dumps(response))
        saved = CredentialStore(self.config.directory('secrets')).load(CLOUDDRIVE_CREDENTIAL)
        self.assertEqual(saved.require('token')[0], 'new-private-token')

    def test_cross_directory_rename_rolls_back_after_rejection(self):
        transport = object.__new__(cloud.CloudTransport)
        transport.client = Mock()
        transport.client.find.return_value = None
        transport._move_confirmed = Mock(side_effect=[None, OSError('拒绝移动'), None])
        with self.assertRaisesRegex(OSError, '拒绝移动'):
            transport.rename(('盘', 'old.mp4'), ('盘', 'sub', 'new.mp4'))
        calls = transport._move_confirmed.call_args_list
        self.assertEqual(len(calls), 3)
        self.assertEqual(calls[0].args[0], '/盘/old.mp4')
        self.assertEqual(calls[2].args[1], '/盘/old.mp4')

    def test_added_folder_roundtrips_original_configuration_and_is_idempotent(self):
        folder = pb.CloudDriveFile(name='电影', fullPathName='/网盘/电影', isDirectory=True,
                                   CloudAPI=pb.CloudAPI(name='115open'))
        with patch.object(android_integration, 'CloudTransport'), \
                patch.object(android_integration, 'CloudDriveClient') as client, \
                patch.object(android_integration, 'CLOUD_MEDIA', self.root / 'cloud-media'):
            client.return_value.__enter__.return_value.find.return_value = folder
            response = android_integration.connect(self.config, {'token': 'private-token', 'path': '/网盘/电影', 'name': '我的云盘'})
            loaded = settings_file.load_config(environ={'PEACH_DATA_ROOT': str(self.root)})
            again = android_integration.connect(loaded, {'token': 'private-token', 'path': '/网盘/电影', 'name': '再次添加'})
        self.assertTrue(again['already_added'])
        self.assertEqual(response['root_id'], again['root_id'])
        self.assertEqual(len(loaded.storage_roots), 2)
        self.assertEqual(loaded.storage_roots[response['root_id']]['location'], '115')
        prefixes = PushDiscoverySettings(loaded.directory('state')).load(loaded.locations).prefixes
        self.assertEqual([(row.prefix, row.root) for row in prefixes],
                         [('/网盘/电影', loaded.storage_roots[response['root_id']]['root'])])
        self.assertNotIn('private-token', loaded.path.read_text())
        resource = smb_media.resolve(loaded.storage_roots[response['root_id']]['root'] + '\\film.mp4', loaded)
        self.assertEqual(resource.remote_parts, ('网盘', '电影', 'film.mp4'))

    def test_uncertain_stage_keeps_paths_for_manual_recovery(self):
        transport = object.__new__(cloud.CloudTransport)
        transport.client = Mock()
        transport.client.find.return_value = None
        transport._move_confirmed = Mock(side_effect=cloud.CloudMoveUnconfirmed('未确认'))
        with self.assertRaises(cloud.CloudMoveUnconfirmed):
            transport.rename(('盘', 'old.mp4'), ('盘', 'sub', 'new.mp4'))
        self.assertEqual(transport._move_confirmed.call_count, 1)
