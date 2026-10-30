#!/usr/bin/env python3
"""Generates studio-grade visual & audio assets for PulseWave Music (pure Python stdlib)."""

import array
import math
import os
import random
import struct
import wave
import zlib


def write_png(path: str, width: int, height: int, rgb_rows: list[bytes]) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = b"".join(b"\x00" + row for row in rgb_rows)

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, level=6))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as f:
        f.write(png)


def clamp8(v: float) -> int:
    if v <= 0.0:
        return 0
    if v >= 255.0:
        return 255
    return int(v)


def generate_cover(path: str, c1: tuple, c2: tuple, c3: tuple, mode: int, size: int = 384) -> None:
    rows: list[bytes] = []
    cx = size * 0.5
    cy = size * 0.5
    inv = 1.0 / size
    for y in range(size):
        ny = y * inv
        dy = (y - cy) * inv
        row = bytearray(size * 3)
        idx = 0
        for x in range(size):
            nx = x * inv
            dx = (x - cx) * inv
            dist = math.sqrt(dx * dx + dy * dy)
            angle = math.atan2(dy, dx)

            w1 = 0.5 + 0.5 * math.sin(nx * 5.2 + ny * 3.7 + mode * 0.9)
            w2 = 0.5 + 0.5 * math.cos((nx - ny) * 6.4 - dist * 9.0 + mode)

            r = c1[0] * (1.0 - ny) + c2[0] * ny
            g = c1[1] * (1.0 - ny) + c2[1] * ny
            b = c1[2] * (1.0 - ny) + c2[2] * ny

            glow = max(0.0, 1.0 - dist * 1.85) ** 2
            r += c3[0] * glow * (0.45 + 0.35 * w1)
            g += c3[1] * glow * (0.45 + 0.35 * w1)
            b += c3[2] * glow * (0.45 + 0.35 * w2)

            if mode % 3 == 0:
                # Vinyl / concentric harmonic rings
                if 0.10 < dist < 0.38:
                    ring = 0.5 + 0.5 * math.sin(dist * 140.0 + angle * 2.0)
                    r = r * 0.65 + (30 + 60 * ring) * 0.35
                    g = g * 0.65 + (30 + 60 * ring) * 0.35
                    b = b * 0.65 + (45 + 75 * ring) * 0.35
                elif dist <= 0.10:
                    r, g, b = c3[0] * 0.95, c3[1] * 0.95, c3[2] * 0.95
            elif mode % 3 == 1:
                # Audio spectrum wave ribbon
                wave_y = 0.52 + 0.14 * math.sin(nx * 14.0 + mode) * math.cos(nx * 5.0)
                band = abs(ny - wave_y)
                if band < 0.045:
                    boost = (1.0 - band / 0.045) ** 2
                    r += 130 * boost
                    g += 120 * boost
                    b += 155 * boost
            else:
                # Synth horizon & radial pulse
                ring = abs(math.sin(dist * 26.0 - mode * 0.7))
                if ring > 0.92 and dist < 0.44:
                    boost = (ring - 0.92) * 12.5
                    r += c3[0] * 0.65 * boost
                    g += c3[1] * 0.65 * boost
                    b += c3[2] * 0.65 * boost

            row[idx] = clamp8(r)
            row[idx + 1] = clamp8(g)
            row[idx + 2] = clamp8(b)
            idx += 3
        rows.append(bytes(row))
    write_png(path, size, size, rows)


def write_stereo_wav(path: str, sample_rate: int, left: list[float], right: list[float]) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    n = min(len(left), len(right))
    fade = min(int(sample_rate * 0.25), n // 8)
    # Smooth loop boundary crossfade so looping is 100% seamless
    for i in range(fade):
        alpha = 0.5 - 0.5 * math.cos(math.pi * i / fade)
        left[i] = left[i] * alpha + left[n - fade + i] * (1.0 - alpha)
        right[i] = right[i] * alpha + right[n - fade + i] * (1.0 - alpha)
        left[n - fade + i] = left[n - fade + i] * alpha + left[i] * (1.0 - alpha)
        right[n - fade + i] = right[n - fade + i] * alpha + right[i] * (1.0 - alpha)

    pcm = array.array("h")
    for i in range(n):
        lv = max(-0.97, min(0.97, left[i]))
        rv = max(-0.97, min(0.97, right[i]))
        pcm.append(int(lv * 32767.0))
        pcm.append(int(rv * 32767.0))

    with wave.open(path, "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm.tobytes())


def gen_rain(path: str, sr: int = 22050, seconds: float = 9.0) -> None:
    rng = random.Random(101)
    n = int(sr * seconds)
    left, right = [0.0] * n, [0.0] * n
    lp_l, lp_r = 0.0, 0.0
    for i in range(n):
        nl = rng.uniform(-1.0, 1.0)
        nr = rng.uniform(-1.0, 1.0)
        lp_l = lp_l * 0.78 + nl * 0.22
        lp_r = lp_r * 0.78 + nr * 0.22
        mod = 0.78 + 0.22 * math.sin(2.0 * math.pi * i / n * 2.0)
        left[i] = lp_l * 0.42 * mod
        right[i] = lp_r * 0.42 * mod
    # Soft water droplets
    for _ in range(95):
        pos = rng.randint(0, n - 900)
        freq = rng.uniform(950.0, 2200.0)
        pan = rng.uniform(0.25, 0.75)
        for k in range(700):
            env = math.exp(-k / 110.0) * 0.10
            s = math.sin(2.0 * math.pi * freq * (k / sr)) * env
            left[pos + k] += s * (1.0 - pan)
            right[pos + k] += s * pan
    write_stereo_wav(path, sr, left, right)


def gen_vinyl(path: str, sr: int = 22050, seconds: float = 8.0) -> None:
    rng = random.Random(202)
    n = int(sr * seconds)
    left, right = [0.0] * n, [0.0] * n
    lp_l, lp_r = 0.0, 0.0
    for i in range(n):
        t = i / sr
        hum = 0.035 * math.sin(2.0 * math.pi * 60.0 * t)
        lp_l = lp_l * 0.86 + rng.uniform(-1.0, 1.0) * 0.14
        lp_r = lp_r * 0.86 + rng.uniform(-1.0, 1.0) * 0.14
        crackle_l = rng.uniform(-0.55, 0.55) if rng.random() < 0.0022 else 0.0
        crackle_r = rng.uniform(-0.55, 0.55) if rng.random() < 0.0022 else 0.0
        pop = rng.uniform(-0.22, 0.22) if rng.random() < 0.012 else 0.0
        left[i] = lp_l * 0.18 + hum + crackle_l + pop
        right[i] = lp_r * 0.18 + hum + crackle_r + pop * 0.9
    write_stereo_wav(path, sr, left, right)


def gen_ocean(path: str, sr: int = 22050, seconds: float = 10.0) -> None:
    rng = random.Random(303)
    n = int(sr * seconds)
    left, right = [0.0] * n, [0.0] * n
    b1, b2 = 0.0, 0.0
    for i in range(n):
        phase = 2.0 * math.pi * i / n
        swell = 0.35 + 0.65 * (0.5 + 0.5 * math.sin(phase * 2.0 - 0.6)) ** 1.7
        pan = 0.5 + 0.25 * math.sin(phase)
        b1 = b1 * 0.93 + rng.uniform(-1.0, 1.0) * 0.07
        b2 = b2 * 0.93 + rng.uniform(-1.0, 1.0) * 0.07
        left[i] = b1 * 0.72 * swell * (1.0 - 0.3 * pan)
        right[i] = b2 * 0.72 * swell * (0.7 + 0.3 * pan)
    write_stereo_wav(path, sr, left, right)


def gen_thunder(path: str, sr: int = 22050, seconds: float = 10.0) -> None:
    rng = random.Random(404)
    n = int(sr * seconds)
    left, right = [0.0] * n, [0.0] * n
    lp1, lp2 = 0.0, 0.0
    for i in range(n):
        lp1 = lp1 * 0.975 + rng.uniform(-1.0, 1.0) * 0.025
        lp2 = lp2 * 0.975 + rng.uniform(-1.0, 1.0) * 0.025
        left[i] = lp1 * 0.45
        right[i] = lp2 * 0.45
    for start_sec, amp in [(1.2, 0.85), (5.4, 0.70)]:
        start = int(start_sec * sr)
        dur = int(3.4 * sr)
        sub_l, sub_r = 0.0, 0.0
        for k in range(dur):
            if start + k >= n:
                break
            env = math.sin(math.pi * min(1.0, k / (0.18 * sr))) if k < 0.18 * sr else math.exp(-(k - 0.18 * sr) / (1.05 * sr))
            sub_l = sub_l * 0.985 + rng.uniform(-1.0, 1.0) * 0.015
            sub_r = sub_r * 0.985 + rng.uniform(-1.0, 1.0) * 0.015
            sub_tone = math.sin(2.0 * math.pi * 46.0 * (k / sr)) * 0.28
            left[start + k] += (sub_l * 2.2 + sub_tone) * env * amp
            right[start + k] += (sub_r * 2.2 + sub_tone * 0.95) * env * amp
    write_stereo_wav(path, sr, left, right)


def gen_night_pad(path: str, sr: int = 22050, seconds: float = 10.0) -> None:
    n = int(sr * seconds)
    left, right = [0.0] * n, [0.0] * n
    # Lush Cmaj9 warm ambient pad (exact integer cycles over 10s so zero phase jump!)
    freqs_l = [130.8, 196.0, 246.9, 293.7, 329.6]
    freqs_r = [131.0, 196.2, 247.1, 293.5, 329.8]
    for i in range(n):
        t = i / sr
        breath = 0.72 + 0.28 * math.sin(2.0 * math.pi * i / n * 2.0)
        sl = sum(math.sin(2.0 * math.pi * f * t) for f in freqs_l) * 0.11 * breath
        sr_val = sum(math.sin(2.0 * math.pi * f * t + 0.4) for f in freqs_r) * 0.11 * breath
        left[i] = sl
        right[i] = sr_val
    write_stereo_wav(path, sr, left, right)


def gen_lofi_beat(path: str, sr: int = 22050) -> None:
    # 4 bars at 80 BPM -> 12.0 seconds exact seamless loop
    bpm = 80.0
    beat_sec = 60.0 / bpm  # 0.75s
    total_beats = 16
    seconds = total_beats * beat_sec  # 12.0s
    n = int(sr * seconds)
    left, right = [0.0] * n, [0.0] * n
    rng = random.Random(505)

    chords = [
        [174.61, 207.65, 261.63, 311.13, 392.00],  # Fm9
        [116.54, 207.65, 246.94, 293.66, 349.23],  # Bb13
        [155.56, 196.00, 233.08, 293.66, 349.23],  # Ebmaj9
        [130.81, 196.00, 233.08, 277.18, 329.63],  # C7alt
    ]

    # Warm Rhodes chord progression + subtle vinyl floor
    lp = 0.0
    for i in range(n):
        t = i / sr
        bar = min(3, int(t / (4.0 * beat_sec)))
        bar_t = t - bar * 4.0 * beat_sec
        env = math.exp(-bar_t * 0.55) * (1.0 - math.exp(-bar_t * 35.0))
        trem_l = 0.82 + 0.18 * math.sin(2.0 * math.pi * 3.5 * t)
        trem_r = 0.82 + 0.18 * math.cos(2.0 * math.pi * 3.5 * t)
        chord = chords[bar]
        s = 0.0
        for idx_f, f in enumerate(chord):
            s += math.sin(2.0 * math.pi * f * t + 0.3 * math.sin(2.0 * math.pi * 2.0 * f * t) * math.exp(-bar_t * 3.0)) * (0.11 if idx_f > 0 else 0.16)
        lp = lp * 0.85 + rng.uniform(-1.0, 1.0) * 0.15
        vinyl = lp * 0.025 + (rng.uniform(-0.12, 0.12) if rng.random() < 0.0012 else 0.0)
        left[i] = s * env * trem_l + vinyl
        right[i] = s * env * trem_r + vinyl

    # Boom-bap drums (Kick on 0, 1.5, 2.5; Snare on 1, 3; Swung 8th hats)
    for bar in range(4):
        base_t = bar * 4.0 * beat_sec
        for kb in [0.0, 1.55, 2.5]:
            pos = int((base_t + kb * beat_sec) * sr)
            for k in range(int(0.24 * sr)):
                if pos + k >= n:
                    break
                kt = k / sr
                f_kick = 48.0 + 95.0 * math.exp(-kt * 28.0)
                kv = math.sin(2.0 * math.pi * f_kick * kt) * math.exp(-kt * 11.0) * 0.52
                left[pos + k] += kv
                right[pos + k] += kv
        for sb in [1.0, 3.0]:
            pos = int((base_t + sb * beat_sec) * sr)
            for k in range(int(0.18 * sr)):
                if pos + k >= n:
                    break
                kt = k / sr
                sn = (rng.uniform(-1.0, 1.0) * math.exp(-kt * 24.0) * 0.28 + math.sin(2.0 * math.pi * 185.0 * kt) * math.exp(-kt * 32.0) * 0.18)
                left[pos + k] += sn * 0.95
                right[pos + k] += sn * 1.05
        for h in range(8):
            swing = 0.06 * beat_sec if h % 2 == 1 else 0.0
            pos = int((base_t + h * 0.5 * beat_sec + swing) * sr)
            for k in range(int(0.055 * sr)):
                if pos + k >= n:
                    break
                kt = k / sr
                hv = rng.uniform(-1.0, 1.0) * math.exp(-kt * 75.0) * (0.11 if h % 2 == 0 else 0.07)
                left[pos + k] += hv * 1.1
                right[pos + k] += hv * 0.85

    write_stereo_wav(path, sr, left, right)


def gen_synthwave_drive(path: str, sr: int = 22050) -> None:
    # 4 bars at 100 BPM -> 9.6 seconds exact seamless loop
    bpm = 100.0
    beat_sec = 60.0 / bpm  # 0.6s
    seconds = 16 * beat_sec  # 9.6s
    n = int(sr * seconds)
    left, right = [0.0] * n, [0.0] * n
    rng = random.Random(606)

    bass_roots = [55.0, 43.65, 65.41, 49.0]  # Am -> F -> C -> G
    arp_notes = [
        [220.0, 261.63, 329.63, 440.0],
        [174.61, 220.0, 261.63, 349.23],
        [261.63, 329.63, 392.0, 523.25],
        [196.0, 246.94, 293.66, 392.0],
    ]

    step_sec = beat_sec * 0.25  # 16th notes
    for i in range(n):
        t = i / sr
        bar = min(3, int(t / (4.0 * beat_sec)))
        step = int(t / step_sec)
        step_t = t - step * step_sec

        # Driving 8th-note analog bass
        b_freq = bass_roots[bar] * (2.0 if (step // 2) % 2 == 1 else 1.0)
        b_env = math.exp(-step_t * 9.0)
        bass = (math.sin(2.0 * math.pi * b_freq * t) * 0.26 + math.sin(4.0 * math.pi * b_freq * t) * 0.12) * b_env

        # Shimmering stereo 16th-note synth arpeggio
        a_freq = arp_notes[bar][step % 4]
        a_env = math.exp(-step_t * 14.0)
        arp_l = math.sin(2.0 * math.pi * a_freq * t) * 0.16 * a_env
        arp_r = math.sin(2.0 * math.pi * (a_freq * 1.003) * t + 0.5) * 0.16 * a_env

        left[i] = bass + arp_l
        right[i] = bass + arp_r

    # 4-on-the-floor punchy kick + gated snare on 2 & 4
    for beat in range(16):
        pos = int(beat * beat_sec * sr)
        for k in range(int(0.20 * sr)):
            if pos + k >= n:
                break
            kt = k / sr
            fk = 50.0 + 110.0 * math.exp(-kt * 32.0)
            kv = math.sin(2.0 * math.pi * fk * kt) * math.exp(-kt * 13.0) * 0.46
            left[pos + k] += kv
            right[pos + k] += kv
        if beat % 4 in (1, 3):
            for k in range(int(0.22 * sr)):
                if pos + k >= n:
                    break
                kt = k / sr
                sn = rng.uniform(-1.0, 1.0) * math.exp(-kt * 16.0) * 0.26
                left[pos + k] += sn
                right[pos + k] += sn * 0.92

    write_stereo_wav(path, sr, left, right)


def main() -> None:
    covers = [
        ("bollywood_hits.png", (42, 10, 58), (195, 34, 92), (255, 179, 56), 0),
        ("punjabi_fire.png", (48, 14, 12), (214, 58, 24), (255, 206, 68), 1),
        ("lofi_chill.png", (16, 18, 48), (64, 46, 122), (168, 139, 250), 3),
        ("desi_hiphop.png", (10, 28, 36), (16, 124, 112), (52, 245, 197), 4),
        ("retro_90s.png", (38, 12, 54), (168, 38, 118), (255, 138, 76), 2),
        ("romantic_soul.png", (46, 12, 34), (178, 34, 79), (255, 148, 182), 5),
        ("workout_beast.png", (14, 24, 16), (34, 138, 62), (182, 255, 64), 7),
        ("late_night_drive.png", (8, 14, 34), (28, 64, 138), (56, 189, 248), 8),
        ("sufi_peace.png", (14, 32, 34), (22, 104, 98), (245, 202, 92), 6),
        ("phonk_bass.png", (20, 8, 28), (112, 18, 68), (244, 63, 94), 9),
        ("indie_india.png", (36, 22, 18), (168, 78, 44), (94, 234, 212), 10),
        ("global_top.png", (18, 16, 46), (88, 48, 168), (236, 72, 153), 11),
    ]
    for name, c1, c2, c3, mode in covers:
        generate_cover(f"app/src/main/assets/covers/{name}", c1, c2, c3, mode, size=384)

    # Launcher fallback high-res PNGs
    generate_cover("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png", (14, 12, 32), (88, 28, 135), (244, 63, 94), 1, size=192)
    generate_cover("app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png", (14, 12, 32), (88, 28, 135), (244, 63, 94), 1, size=192)

    # Studio Stereo Audio Stems & Offline Beats
    gen_rain("app/src/main/res/raw/ambient_rain.wav")
    gen_vinyl("app/src/main/res/raw/ambient_vinyl.wav")
    gen_ocean("app/src/main/res/raw/ambient_ocean.wav")
    gen_thunder("app/src/main/res/raw/ambient_thunder.wav")
    gen_night_pad("app/src/main/res/raw/ambient_night_pad.wav")
    gen_lofi_beat("app/src/main/res/raw/offline_lofi_beat.wav")
    gen_synthwave_drive("app/src/main/res/raw/offline_synthwave_drive.wav")
    print("All studio visual & audio assets generated successfully!")


if __name__ == "__main__":
    main()
