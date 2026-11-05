"""Fetch the compile-only legacy Xposed API from its official Maven host."""
import hashlib
from pathlib import Path
from urllib.request import urlopen

DEST = Path(__file__).resolve().parent / 'tools' / 'xposed-api.jar'
URL = 'https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar'
SHA256 = 'f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25'

if __name__ == '__main__':
    data = DEST.read_bytes() if DEST.exists() else urlopen(URL, timeout=30).read()
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise SystemExit('Xposed API checksum mismatch')
    DEST.parent.mkdir(exist_ok=True)
    if not DEST.exists():
        DEST.write_bytes(data)
    print('Verified legacy Xposed API 82')
