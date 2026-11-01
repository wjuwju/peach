"""Peach application package."""

__version__ = "0.52.0"

import os as _os

if _os.environ.get('PEACH_ANDROID') == '1':
    from .android_platform import configure as _configure_android
    _configure_android()
