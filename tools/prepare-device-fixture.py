#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-3.0-or-later
"""Generate silent audio and neutral artwork without reading a personal library."""
import argparse
from pathlib import Path
import shutil
import struct
import subprocess
import zlib


def png_chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Output directory outside source files")
    parser.add_argument("--smb", action="store_true", help="Also create a sparse file for offsets beyond 4 GiB")
    args = parser.parse_args()
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        parser.error("ffmpeg is required; on Debian: sudo apt-get install ffmpeg")
    output = args.output.resolve()
    repository = Path(__file__).resolve().parent.parent
    if output == repository or (repository in output.parents and not output.is_relative_to(repository / "fixtures")):
        parser.error("Use the ignored fixtures/ directory or a directory outside the repository")
    work = output / "RJ00000001 测试作品"
    audio = work / "音频" / "03_测试音轨.mp3"
    script = work / "台本" / "03_测试音轨.txt"
    cover = work / "特典" / "封面.png"
    for file in (audio, script, cover):
        file.parent.mkdir(parents=True, exist_ok=True)
        if file.exists():
            parser.error("Generated fixture already exists; use a new output directory")
    # 1,852 seconds at 320 kbps supports the existing long-seek and 40 MB range checks.
    # No source recording, title, author metadata or image is copied into the fixture.
    subprocess.run([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-n",
        "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo", "-t", "1852",
        "-c:a", "libmp3lame", "-b:a", "320k", "-write_xing", "1",
        "-map_metadata", "-1", str(audio),
    ], check=True)
    audio.with_suffix(".mp3.vtt").write_text(
        "WEBVTT\n\n00:00:00.000 --> 00:00:03.000\n测试字幕一\n\n"
        "00:00:04.000 --> 00:00:07.000\nテスト字幕\n\n"
        "00:15:00.000 --> 00:15:05.000\n测试字幕二\n", encoding="utf-8")
    script.write_text("测试台本\nThis text is generated for integration tests.\n", encoding="utf-8")
    width = height = 128
    rows = (b"\x00" + b"\x38\x2b\x45" * width) * height
    cover.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(rows)) + png_chunk(b"IEND", b""))
    if args.smb:
        with (output / "large-offset.bin").open("xb") as file:
            file.seek(4_294_967_303)
            file.write(b"WHISP")
    print("Generated silent MP3, subtitles, script and neutral cover.")


if __name__ == "__main__":
    main()
