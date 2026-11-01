"""使用本机 WebView 实测的编码能力选择 MP4 直读。"""
from __future__ import annotations

import json
import os


def hevc_supported(tag: str, pixel_format: str) -> bool:
    if os.environ.get('PEACH_ANDROID') != '1' or tag not in {'hvc1', 'hev1'}:
        return False
    profile = {'yuv420p': 'main', 'yuv420p10le': 'main10'}.get(pixel_format)
    if profile is None:
        return False
    from . import settings_file
    path = settings_file.active().directory('state') / 'android-playback.json'
    try:
        if path.stat().st_size > 4096:
            return False
        value = json.loads(path.read_text(encoding='utf-8'))
        return isinstance(value, dict) and value.get('schema') == 1 and value.get(tag + '_' + profile) is True
    except (OSError, ValueError):
        return False
