#!/usr/bin/env python3
"""Verify and publish a local debug APK; retain the three most recently published APKs."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def atomic_copy(source, destination):
    """An interrupted copy must not replace a previously working APK."""
    fd, temporary = tempfile.mkstemp(prefix=".release-", dir=destination.parent)
    try:
        with os.fdopen(fd, "wb") as output, source.open("rb") as incoming:
            shutil.copyfileobj(incoming, output)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)


def apk_files(dist):
    return sorted(
        (p for p in dist.glob("*.apk") if p.is_file() and not p.is_symlink()),
        key=lambda p: (p.stat().st_mtime_ns, p.name), reverse=True,
    )


def publish(apk, dist, version):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", version):
        raise ValueError("Unsafe or missing APK version name")
    if not apk.is_file() or apk.stat().st_size == 0:
        raise ValueError("Built APK is missing or empty")
    dist.mkdir(parents=True, exist_ok=True)
    destination = dist / f"ObsFit-{version}-debug.apk"
    atomic_copy(apk, destination)
    # Always retain this successful publication, even if an old file has a future timestamp.
    others = [p for p in apk_files(dist) if p != destination]
    for old in others[2:]:
        old.unlink()
        print(f"Removed {old.name}", flush=True)
    retained = [destination] + others[:2]
    checksums = "".join(
        f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n"
        for p in sorted(retained)
    )
    fd, temporary = tempfile.mkstemp(prefix=".checksums-", dir=dist)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            output.write(checksums)
        os.replace(temporary, dist / "SHA256SUMS")
    finally:
        Path(temporary).unlink(missing_ok=True)
    return destination


def release(root=ROOT, runner=subprocess.run):
    def run(*command):
        runner(list(command), cwd=root, check=True)

    # Do not assemble or prune anything if any verification fails.
    run(sys.executable, "-B", "-m", "unittest", "discover", "-s", "tools", "-p", "test_*.py")
    run("./gradlew", "--no-daemon", ":core:test", "--rerun-tasks")
    run("./gradlew", "--no-daemon", ":app:assembleDebug", ":app:lintDebug")
    apk = root / "app/build/outputs/apk/debug/app-debug.apk"
    run(sys.executable, "tools/check-apk-alignment.py", str(apk))
    metadata = json.loads((apk.parent / "output-metadata.json").read_text(encoding="utf-8"))
    elements = metadata["elements"]
    if len(elements) != 1 or elements[0]["outputFile"] != apk.name:
        raise ValueError("Expected one debug APK in output metadata")
    artifact = publish(apk, root / "dist", elements[0]["versionName"])
    print(f"Ready: {artifact}", flush=True)
    return artifact


def main():
    if len(sys.argv) > 1:
        print("Usage: python3 tools/release.py\nTests → APK + lint → 16 KB check → dist (last 3 APKs).")
        return 0 if sys.argv[1:] == ["--help"] else 2
    # Serialize releases so a parallel invocation cannot prune another's output.
    lock_dir = ROOT / ".gradle"
    lock_dir.mkdir(exist_ok=True)
    with (lock_dir / "obsfit-release.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        release()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"Release failed: {error}", file=sys.stderr)
        sys.exit(1)
