"""安卓进程时间使用系统允许的启动时钟，桌面运行方式保持原样。"""
import os
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from peach.android_platform import configure


class AndroidPlatformTests(unittest.TestCase):
    def test_restricted_boot_time_uses_the_real_boot_clock(self):
        backend = SimpleNamespace(boot_time=Mock(side_effect=PermissionError('restricted')))
        with patch.dict(os.environ, {'PEACH_ANDROID': '1'}):
            self.assertTrue(configure(backend, wall_clock=lambda: 1234.5, boot_clock=lambda: 234.5))
            self.assertEqual(backend.boot_time(), 1000)
            self.assertFalse(configure(backend))

    def test_accessible_boot_information_is_kept(self):
        original = Mock(return_value=1000)
        backend = SimpleNamespace(boot_time=original)
        with patch.dict(os.environ, {'PEACH_ANDROID': '1'}):
            self.assertFalse(configure(backend))
        self.assertIs(backend.boot_time, original)

    def test_process_identity_keeps_a_stable_integer_boot_epoch(self):
        backend = SimpleNamespace(boot_time=Mock(side_effect=PermissionError('restricted')))
        clock = Mock(side_effect=[1234.5123, 999999.9])
        elapsed = Mock(side_effect=[234.5111, 234.5222])
        with patch.dict(os.environ, {'PEACH_ANDROID': '1'}):
            configure(backend, wall_clock=clock, boot_clock=elapsed)
        self.assertEqual(backend.boot_time(), 1000)
        self.assertEqual(backend.boot_time(), 1000)
        clock.assert_called_once()
        elapsed.assert_called_once()

    def test_other_platforms_do_not_probe_or_replace_the_backend(self):
        original = Mock(side_effect=PermissionError('restricted'))
        backend = SimpleNamespace(boot_time=original)
        with patch.dict(os.environ, {'PEACH_ANDROID': '0'}):
            self.assertFalse(configure(backend))
        original.assert_not_called()
        self.assertIs(backend.boot_time, original)

    def test_missing_boot_clock_keeps_the_failure_visible(self):
        original = Mock(side_effect=PermissionError('restricted'))
        backend = SimpleNamespace(boot_time=original)
        with patch.dict(os.environ, {'PEACH_ANDROID': '1'}), self.assertRaises(OSError):
            configure(backend, boot_clock=Mock(side_effect=OSError('clock unavailable')))
        self.assertIs(backend.boot_time, original)
