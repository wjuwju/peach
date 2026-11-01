"""安卓目录选择保持原版配置身份，授权目录接入原版扫描和随机读取。"""
from contextlib import closing
import io
import json
import os
from pathlib import Path
import socket
import sqlite3
import struct
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from uuid import uuid4

from peach import android_documents as documents, android_cloud, data_storage, folder_picker, media_identity, onboarding, scan, settings_file, smb_media
from peach.downloads import CLOUDDRIVE_CREDENTIAL
from peach.follow_secrets import CredentialStore
from peach.migrations import upgrade
from peach.smb_transport import SmbEntry, SmbStat


class AndroidDocumentsTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.config = settings_file.PeachConfig(self.root, self.root / 'config.toml', locations={})
        self.config.directory('state').mkdir(parents=True)
        self.mounts = self.root / 'mounts'
        self.identifier = 'a' * 64

    def picker(self, reply):
        return patch.multiple(documents, MOUNTS=self.mounts), patch.object(documents, 'request', return_value=(reply, b'')), patch.object(settings_file, 'active', return_value=self.config)

    def test_android_picker_returns_a_mount_without_rewriting_unsaved_configuration(self):
        location, bridge, active = self.picker({'kind': 'document', 'id': self.identifier})
        with location, bridge, active, patch.dict(os.environ, {'PEACH_ANDROID': '1'}):
            mount = folder_picker.pick_folder()
        self.assertTrue(Path(mount).is_dir())
        self.assertFalse(self.config.path.exists())
        self.assertEqual(documents.selection(self.config, mount), {'kind': 'document', 'id': self.identifier})
        self.assertIsNone(documents.selection(self.config, '/unrelated'))

    def test_virtual_media_mount_cannot_be_used_as_the_database_directory(self):
        with patch.dict(os.environ, {'PEACH_ANDROID': '1'}), patch.object(documents, 'MOUNTS', self.mounts):
            with self.assertRaisesRegex(ValueError, '应用内'):
                onboarding.validate_data_root(str(self.mounts / self.identifier))
            with self.assertRaisesRegex(ValueError, '应用内'):
                data_storage.check_target(self.config, str(self.mounts / self.identifier))
            self.assertEqual(onboarding.validate_data_root('/data'), Path('/data'))

    def test_cancel_and_invalid_selection_do_not_register_or_create_a_directory(self):
        for reply in ({'cancelled': True}, {'kind': 'document', 'id': '../../escape'}):
            with self.subTest(reply=reply):
                location, bridge, active = self.picker(reply)
                with location, bridge, active:
                    if reply.get('cancelled'):
                        self.assertIsNone(documents.pick_folder())
                    else:
                        with self.assertRaises(folder_picker.PickerUnavailable):
                            documents.pick_folder()
        self.assertFalse(self.mounts.exists())

    def test_cloud_picker_verifies_the_folder_and_saves_the_connection_without_changing_configuration(self):
        location, bridge, active = self.picker({'kind': 'cloud', 'id': self.identifier,
                                               'remote': '/网盘/电影', 'token': 'fixture-token'})
        with location, bridge, active, patch('peach.android_integration.connect') as connect, \
                patch.object(android_cloud, 'CloudTransport') as transport:
            transport.return_value.__enter__.return_value.stat.return_value = SmbStat(0, 0, 0, True, '电影')
            mount = documents.pick_folder()
        self.assertFalse(self.config.path.exists())
        self.assertEqual(connect.call_args.args[1], {'token': 'fixture-token'})
        self.assertEqual(documents.selection(self.config, mount)['remote'], '/网盘/电影')
        self.assertNotIn('fixture-token', (self.config.directory('state') / documents.REGISTRY).read_text())

    def prepare_root(self, kind='document'):
        mount = self.mounts / self.identifier
        mount.mkdir(parents=True)
        selected = {'kind': kind, 'id': self.identifier, 'remote': '/网盘/电影'}
        (self.config.directory('state') / documents.REGISTRY).write_text(json.dumps({str(mount): selected}))
        root_id, library_id = str(uuid4()), str(uuid4())
        config = media_identity.project(self.config, {library_id: {'name': '手机', 'icon': 'database'}}, {
            root_id: {'root': 'R:\\Android\\fixture', 'mount': str(mount), 'library_id': library_id,
                      'location': 'local', 'cost': 'free'}})
        return config, mount

    def test_selected_document_directory_scans_into_the_original_windows_ledger_paths(self):
        config, mount = self.prepare_root()
        entries = [SmbEntry(10, 1700000000, 1700000000000000000, False, 'film.mp4'),
                   SmbEntry(20, 1700000000, 1700000000000000000, False, 'film.srt')]
        db = self.root / 'ledger.db'
        upgrade(db, Path(__file__).resolve().parents[1] / 'migrations')
        with patch.dict(os.environ, {'PEACH_ANDROID': '1'}), patch.object(settings_file, 'active', return_value=config), \
                patch.object(documents.DocumentResource, 'scandir', return_value=entries):
            resource = smb_media.resolve('R:\\Android\\fixture\\film.mp4', config)
            self.assertEqual(resource, smb_media.resolve(mount / 'film.mp4', config))
            with self.assertRaises(ValueError):
                smb_media.resolve('R:\\Android\\fixture\\..\\private.mp4', config)
            with self.assertRaises(PermissionError):
                smb_media.resolve(mount, config).unlink()
            result = scan.scan_location(db, 'local', 'R:\\Android\\fixture', declared_roots=config.locations,
                                        mounts=config.mounts, report=lambda _: None)
        self.assertEqual((result.files, result.subtitles), (2, 1))
        with closing(sqlite3.connect(db)) as connection:
            self.assertEqual(connection.execute("SELECT path FROM asset WHERE medium='video'").fetchone()[0],
                             'R:\\Android\\fixture\\film.mp4')

    def test_cloud_selection_saved_through_original_form_resolves_without_a_new_root_id(self):
        config, mount = self.prepare_root('cloud')
        CredentialStore(config.directory('secrets')).save(CLOUDDRIVE_CREDENTIAL, {'token': 'fixture-token'})
        with patch.dict(os.environ, {'PEACH_ANDROID': '1'}):
            resource = smb_media.resolve(mount / 'film.mp4', config)
        self.assertIsInstance(resource, android_cloud.CloudResource)
        self.assertEqual(resource.remote_parts, ('网盘', '电影', 'film.mp4'))
        self.assertNotIn('fixture-token', repr(resource))

    def test_random_access_is_bounded_and_short_reads_fail(self):
        calls = []
        def read(operation, parts, **fields):
            calls.append((operation, fields))
            return {}, b'123'
        reader = documents.DocumentReader(SimpleNamespace(call=read), ('film.mp4',), SmbStat(10, 0, 0, False, 'film.mp4'))
        reader.seek(4)
        self.assertEqual(reader.read(3), b'123')
        self.assertEqual((reader.tell(), calls[0][1]), (7, {'offset': 4, 'count': 3}))
        with self.assertRaises(OSError):
            reader.read(2)
        with self.assertRaises(ValueError):
            reader.seek(-1)

    def test_bridge_framing_carries_private_auth_and_payload_and_reports_permission_errors(self):
        server = socket.socket()
        server.bind(('127.0.0.1', 0)); server.listen()
        self.addCleanup(server.close)
        endpoint = self.config.directory('state') / 'android-bridge.json'
        endpoint.write_text(json.dumps({'port': server.getsockname()[1], 'secret': 's' * 64}))
        received, errors = [], []
        def serve():
            try:
                with server.accept()[0] as connection:
                    size = struct.unpack('!I', documents._receive(connection, 4))[0]
                    body = json.loads(documents._receive(connection, size))
                    data = documents._receive(connection, body['length'])
                    received.append((body, data))
                    value = json.dumps({'error': 'permission', 'message': '目录授权已失效'}).encode()
                    connection.sendall(struct.pack('!I', len(value)) + value)
            except BaseException as error:
                errors.append(error)
        worker = threading.Thread(target=serve)
        worker.start()
        try:
            with self.assertRaisesRegex(PermissionError, '目录授权已失效'):
                documents.request(self.config, 'write', payload=io.BytesIO(b'nfo'), id=self.identifier, parts=['film.nfo'])
        finally:
            worker.join(timeout=5)
        self.assertFalse(worker.is_alive())
        self.assertEqual(errors, [])
        self.assertEqual(received[0][0]['secret'], 's' * 64)
        self.assertEqual(received[0][1], b'nfo')

    def test_pair_file_writer_streams_the_complete_result_once_and_preserves_exclusive_mode(self):
        received = []
        def save(operation, parts, **fields):
            received.append((operation, parts, fields['payload'].read(), fields['exclusive']))
        writer = documents.DocumentWriter(SimpleNamespace(call=save), ('film.nfo',), 'xb')
        writer.write(b'old'); writer.seek(0); writer.write(b'nfo'); writer.seek(0)
        writer.close(); writer.close()
        self.assertEqual(received, [('write', ('film.nfo',), b'nfo', True)])
