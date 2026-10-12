#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-3.0-or-later
"""Check Git candidates and staged blobs without printing matched secret values."""
from pathlib import Path
import ipaddress
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
RULES = {
    "personal filesystem path": re.compile(
        r"/(?:home|Users|mnt|media)/[^\s\"'<>]+|[A-Za-z]:\\Users\\", re.I
    ),
    "private key material": re.compile(r"-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----"),
    "access token": re.compile(
        r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})\b"
    ),
    "literal signing password": re.compile(
        r"(?:storePassword|keyPassword)\s*[=:]\s*[\"'][^\"']+[\"']"
    ),
}
IPV4 = re.compile(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])")
ALLOWED_TEST_IPS = {"10.0.2.2", "127.0.0.1"}
BLOCKED_SUFFIXES = {
    ".p12",
    ".pfx",
    ".pem",
    ".key",
    ".jks",
    ".keystore",
    ".apk",
    ".aab",
    ".apks",
    ".mp3",
    ".m4a",
    ".flac",
    ".wav",
    ".ogg",
    ".opus",
    ".aac",
    ".db",
    ".sqlite",
    ".log",
    ".zip",
}
BLOCKED_PARTS = {
    ".signing",
    ".gradle",
    ".kotlin",
    "build",
    "fixtures",
    "screenshots",
    "artifacts",
}


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def inspect(name, data, origin):
    findings = []
    path = Path(name)
    if (
        BLOCKED_PARTS.intersection(path.parts)
        or path.suffix.lower() in BLOCKED_SUFFIXES
        or path.name == "local.properties"
        or path.name.startswith(".env")
    ):
        findings.append((0, "excluded private or generated file"))
    if name == "gradle/wrapper/gradle-wrapper.jar":
        return findings
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return findings + [(0, "unreviewed binary file")]
    for number, line in enumerate(text.splitlines(), 1):
        for label, rule in RULES.items():
            # A share named /media/ in an example URL is not a host mount path.
            candidate = (
                re.sub(r"\b(?:https?|smb|content)://[^\s\"'<>]+", "", line)
                if label == "personal filesystem path"
                else line
            )
            if rule.search(candidate):
                findings.append((number, label))
        for match in IPV4.finditer(line):
            value = match.group()
            try:
                address = ipaddress.IPv4Address(value)
            except ipaddress.AddressValueError:
                continue
            first, second, _, _ = address.packed
            if (
                first == 10
                or first == 172
                and 16 <= second <= 31
                or first == 192
                and second == 168
            ) and value not in ALLOWED_TEST_IPS:
                findings.append((number, "private network address"))
    return [f"{origin}:{name}:{line}: {label}" for line, label in findings]


def main():
    try:
        candidates = git(
            "ls-files", "--cached", "--others", "--exclude-standard", "-z"
        ).split(b"\0")
        staged = git("ls-files", "--stage", "-z").split(b"\0")
    except subprocess.CalledProcessError:
        sys.exit("Run this check in an initialized Git repository.")
    findings = []
    checked = 0
    for raw in sorted(set(candidates) - {b""}):
        name = raw.decode()
        path = ROOT / name
        if path.is_symlink():
            findings.append(f"working:{name}:0: symlink requires manual review")
        elif path.is_file():
            findings.extend(inspect(name, path.read_bytes(), "working"))
            checked += 1
    for record in filter(None, staged):
        metadata, raw = record.split(b"\t", 1)
        mode, blob, stage = metadata.split()
        name = raw.decode()
        if stage != b"0" or mode == b"120000":
            findings.append(f"staged:{name}:0: unresolved or linked file")
        else:
            findings.extend(
                inspect(name, git("cat-file", "blob", blob.decode()), "staged")
            )
    if findings:
        print("Public content check failed (values withheld):")
        for finding in sorted(set(findings)):
            print(finding)
        return 1
    print(
        f"Public content check passed for {checked} candidate files and their staged versions."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
