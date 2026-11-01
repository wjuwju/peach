"""安卓运行环境中受限系统信息的等价读取。"""
from __future__ import annotations

import os
import time


def _boot_clock():
    return time.clock_gettime(time.CLOCK_BOOTTIME)


def configure(backend=None, *, wall_clock=time.time, boot_clock=_boot_clock):
    """仅在 Android 拒绝 /proc/stat 时，由系统启动时钟取得同一时间基准。"""
    if os.environ.get('PEACH_ANDROID') != '1':
        return False
    if backend is None:
        from psutil import _pslinux
        backend = _pslinux
    try:
        backend.boot_time()
        return False
    except PermissionError:
        epoch = float(int(wall_clock() - boot_clock()))
    backend.boot_time = lambda: epoch
    return True
