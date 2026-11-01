#!/usr/bin/env python3
"""Check 64-bit ELF LOAD segments and uncompressed APK entries against 16 KB pages.

No SDK/NDK dependency; runs on the actual assembled APK in CI.
Android reference: https://developer.android.com/guide/practices/page-sizes
"""
import struct
import sys
import zipfile

PAGE = 16384

def check(apk):
    failures = []
    checked = 0
    with zipfile.ZipFile(apk) as archive, open(apk, 'rb') as raw:
        for info in archive.infolist():
            if not info.filename.startswith(('lib/arm64-v8a/', 'lib/x86_64/')) or not info.filename.endswith('.so'):
                continue
            data = archive.read(info)
            if data[:5] != b'\x7fELF\x02':
                failures.append(f'{info.filename}: expected ELF64')
                continue
            endian = '<' if data[5] == 1 else '>'
            offset = struct.unpack_from(endian + 'Q', data, 32)[0]
            size, count = struct.unpack_from(endian + 'HH', data, 54)
            loads = 0
            for index in range(count):
                segment = struct.unpack_from(endian + 'IIQQQQQQ', data, offset + index * size)
                if segment[0] != 1:
                    continue
                loads += 1
                if segment[-1] < PAGE or segment[2] % PAGE != segment[3] % PAGE:
                    failures.append(f'{info.filename}: LOAD segment is not aligned to 16 KB')
            if not loads:
                failures.append(f'{info.filename}: no LOAD segments')
            if info.compress_type == zipfile.ZIP_STORED:
                raw.seek(info.header_offset + 26)
                name_len, extra_len = struct.unpack('<HH', raw.read(4))
                data_start = info.header_offset + 30 + name_len + extra_len
                if data_start % PAGE:
                    failures.append(f'{info.filename}: APK entry is not aligned to 16 KB')
            checked += 1
    if failures:
        raise SystemExit('\n'.join(failures))
    print(f'16 KB alignment: {checked} native 64-bit libraries passed')

if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: check-apk-alignment.py path/to/app.apk')
    check(sys.argv[1])
