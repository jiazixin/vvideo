#!/usr/bin/env python3
"""Create explicitly selected synthetic fixtures; never rewrite existing files."""
import argparse
import pathlib
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('directory', type=pathlib.Path)
p.add_argument('--duration', type=int, default=8)
a = p.parse_args()
if not 2 <= a.duration <= 30:
    p.error('duration must be 2..30 seconds')
a.directory.mkdir(parents=True, exist_ok=True)
matrix = [
    ('01-h264-head.mp4', 'libx264', '960x540', True),
    ('02-h264-tail-portrait.mp4', 'libx264', '540x960', False),
    ('03-hevc.mp4', 'libx265', '960x540', True),
    ('04-av1.mp4', 'libsvtav1', '640x360', True),
    ('05-h264.mkv', 'libx264', '960x540', False),
    ('06-hevc.mkv', 'libx265', '540x960', False),
    ('07-av1.mkv', 'libsvtav1', '640x360', False),
    ('08-h264.ts', 'libx264', '960x540', False),
    ('09-hevc.ts', 'libx265', '960x540', False),
    ('10-h264.mov', 'libx264', '960x540', False),
    ('11-hevc.mov', 'libx265', '540x960', False),
    ('12-h264-high-bitrate.mp4', 'libx264', '1920x1080', True),
]
for number, (name, codec, size, faststart) in enumerate(matrix):
    target = a.directory / name
    if target.exists():
        raise SystemExit(f'Refusing to replace fixture: {target.name}')
    cmd = ['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi',
           '-i', f'testsrc2=size={size}:rate=30', '-f', 'lavfi', '-i',
           f'sine=frequency={330 + number * 55}:sample_rate=48000', '-t', str(a.duration),
           '-c:v', codec, '-threads', '2', '-pix_fmt', 'yuv420p', '-g', '60']
    if codec == 'libx264':
        cmd += ['-preset', 'fast', '-crf', '18' if 'high-bitrate' in name else '26']
    elif codec == 'libx265':
        cmd += ['-preset', 'fast', '-x265-params', 'pools=2:frame-threads=2', '-crf', '28']
        if target.suffix in ('.mp4', '.mov'):
            cmd += ['-tag:v', 'hvc1']
    else:
        cmd += ['-preset', '12', '-crf', '38', '-svtav1-params', 'lp=2']
    cmd += ['-c:a', 'aac', '-b:a', '96k']
    if faststart:
        cmd += ['-movflags', '+faststart']
    cmd += [str(target)]
    subprocess.run(cmd, check=True, timeout=180)
    print(name, flush=True)
for number in range(20):
    target = a.directory / f'photo-{number:02d}.jpg'
    if target.exists():
        raise SystemExit(f'Refusing to replace fixture: {target.name}')
    size = '600x900' if number % 3 == 0 else '900x600'
    subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-f',
                    'lavfi', '-i', f'testsrc2=size={size}:rate=1', '-frames:v', '1',
                    '-threads', '1', str(target)], check=True, timeout=20)
print('Synthetic fixtures complete', flush=True)
