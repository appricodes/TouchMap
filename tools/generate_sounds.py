"""Synthesizes short UI sound effects for TouchMap's touch/aim feedback, plus the
app-open/app-close cues and the 18 pre-rendered binaural "point touch" earcons (one per 20
degrees around the listener, replacing the old GvrAudioEngine spatializer — see AudioClient.

Regenerate with: python tools/generate_sounds.py
Outputs 16-bit PCM WAV files directly into app/src/main/res/raw/ (mono for the plain effects,
stereo for the point_dir_* binaural set).
No third-party dependencies (stdlib `wave` + `math` only).
"""
import math
import os
import struct
import wave

SAMPLE_RATE = 44100
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")

# ---- binaural (point_dir_*) constants ----
HEAD_RADIUS_M = 0.0875   # average adult interaural distance / 2
SPEED_OF_SOUND = 343.0   # m/s
POINT_DIR_STEP_DEG = 20  # 18 files: 000, 020, ..., 340


def envelope(i, n, attack, decay):
    """Linear attack then exponential decay, both in samples."""
    if i < attack:
        return i / attack
    t = (i - attack) / max(1, (n - attack))
    return math.exp(-decay * t)


def tone(freq, duration_s, attack_s=0.003, decay=6.0, amplitude=1.0, vibrato_hz=0.0, vibrato_depth=0.0):
    n = int(SAMPLE_RATE * duration_s)
    attack = max(1, int(SAMPLE_RATE * attack_s))
    samples = []
    for i in range(n):
        t = i / SAMPLE_RATE
        f = freq
        if vibrato_hz:
            f += vibrato_depth * math.sin(2 * math.pi * vibrato_hz * t)
        v = math.sin(2 * math.pi * f * t)
        v *= envelope(i, n, attack, decay)
        samples.append(v * amplitude)
    return samples


def click(duration_s=0.015, center_freq=2400, amplitude=1.0):
    """Short percussive tick: filtered noise-like burst using a fast-decaying tone cluster."""
    n = int(SAMPLE_RATE * duration_s)
    samples = []
    for i in range(n):
        t = i / SAMPLE_RATE
        v = math.sin(2 * math.pi * center_freq * t) * 0.6
        v += math.sin(2 * math.pi * center_freq * 1.9 * t) * 0.4
        v *= envelope(i, n, 1, 40.0)
        samples.append(v * amplitude)
    return samples


def mix_sequence(parts):
    """Concatenate (samples, gap_s) parts into one buffer."""
    out = []
    for samples, gap_s in parts:
        out.extend(samples)
        out.extend([0.0] * int(SAMPLE_RATE * gap_s))
    return out


def normalize(samples, peak=0.9):
    m = max((abs(s) for s in samples), default=0.0)
    if m == 0:
        return samples
    scale = peak / m
    return [s * scale for s in samples]


def write_wav(path, samples):
    samples = normalize(samples)
    frames = b"".join(struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in samples)
    with wave.open(path, "w") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(SAMPLE_RATE)
        f.writeframes(frames)


def write_wav_stereo_prescaled(path, left, right):
    """Like write_wav but stereo and WITHOUT per-file normalization — callers that need a shared
    scale factor across a whole set of files (so relative loudness between files survives) must
    normalize the pair themselves before calling this. See build_point_dir_set."""
    n = min(len(left), len(right))
    frames = b"".join(
        struct.pack("<hh", int(max(-1.0, min(1.0, left[i])) * 32767), int(max(-1.0, min(1.0, right[i])) * 32767))
        for i in range(n)
    )
    with wave.open(path, "w") as f:
        f.setnchannels(2)
        f.setsampwidth(2)
        f.setframerate(SAMPLE_RATE)
        f.writeframes(frames)


def apply_lowpass(samples, cutoff_hz):
    """Simple one-pole IIR lowpass — stands in for head-shadow / spectral-tilt filtering. Leaves
    the signal untouched if cutoff is at/above Nyquist."""
    if cutoff_hz >= SAMPLE_RATE / 2 - 1:
        return list(samples)
    alpha = 1 - math.exp(-2 * math.pi * cutoff_hz / SAMPLE_RATE)
    out = []
    prev = 0.0
    for s in samples:
        prev = prev + alpha * (s - prev)
        out.append(prev)
    return out


def delay_samples(samples, n):
    """Shifts a signal later by n samples, padding with silence at the start and truncating the
    tail so the length is unchanged (n is always small here — well under 30 samples)."""
    if n <= 0:
        return list(samples)
    if n >= len(samples):
        return [0.0] * len(samples)
    return [0.0] * n + list(samples[:-n])


def build_point_touch():
    # Bright, solid "pluck" for touching a point of interest (node).
    a = tone(1200, 0.05, attack_s=0.002, decay=9.0, amplitude=0.7)
    b = tone(1800, 0.05, attack_s=0.002, decay=10.0, amplitude=0.4)
    n = min(len(a), len(b))
    return [a[i] + b[i] for i in range(n)]


def build_way_touch():
    # Softer, rolling texture for tracing along a way/road. Meant to be replayed
    # rapidly while sliding a finger, so it must feel continuous rather than percussive.
    return tone(340, 0.07, attack_s=0.015, decay=3.0, amplitude=0.6, vibrato_hz=18, vibrato_depth=12)


def build_aim_tick():
    # Neutral, very short tick used while sweeping the phone and not yet on target.
    return click(duration_s=0.02, center_freq=2000, amplitude=0.5)


def build_aim_lock():
    # Rewarding ascending two-note chime for "on target" (12 o'clock) confirmation.
    note1 = tone(880.0, 0.09, attack_s=0.004, decay=5.0, amplitude=0.7)
    note2 = tone(1318.5, 0.14, attack_s=0.004, decay=4.0, amplitude=0.7)
    return mix_sequence([(note1, 0.02), (note2, 0.0)])


def build_location():
    # Short ascending two-note "ping" used for location cues. Distinct pitches from aim_lock
    # (700 -> 1050 Hz, a perfect fifth) so the two chimes are never confused by ear.
    note1 = tone(700.0, 0.07, attack_s=0.003, decay=6.0, amplitude=0.7)
    note2 = tone(1050.0, 0.09, attack_s=0.003, decay=5.0, amplitude=0.7)
    return mix_sequence([(note1, 0.015), (note2, 0.0)])


def build_welcome():
    # Rising three-note figure played once when the app starts (MyApplication.initialize()).
    note1 = tone(523.25, 0.09, attack_s=0.004, decay=6.0, amplitude=0.6)   # C5
    note2 = tone(659.25, 0.10, attack_s=0.004, decay=6.0, amplitude=0.65)  # E5
    note3 = tone(783.99, 0.16, attack_s=0.004, decay=4.5, amplitude=0.7)   # G5
    return mix_sequence([(note1, 0.02), (note2, 0.02), (note3, 0.0)])


def build_goodbye():
    # Falling two-note figure played on deliberate app exit (BaseActivity's double-back-to-exit).
    # Mirrors welcome's timbre (same tone() shape) but descending, so the pair reads as a matched
    # opening/closing pair rather than two unrelated chimes.
    note1 = tone(783.99, 0.09, attack_s=0.004, decay=6.0, amplitude=0.65)  # G5
    note2 = tone(523.25, 0.18, attack_s=0.004, decay=4.5, amplitude=0.6)   # C5
    return mix_sequence([(note1, 0.02), (note2, 0.0)])


# ------------------------------------------------------------------ point_dir_* (binaural)

def build_point_dir_source():
    """The mono earcon rendered at each of the 18 angles: a short two-note figure, each note
    carrying an extra partial so the waveform has real detail (a pure sine collapses the ILD/
    lowpass cues into something that just sounds like "quieter", not "over there")."""
    note1 = tone(1200.0, 0.055, attack_s=0.002, decay=9.0, amplitude=0.7)
    note1_partial = tone(1800.0, 0.055, attack_s=0.002, decay=10.0, amplitude=0.35)
    note2 = tone(1600.0, 0.06, attack_s=0.002, decay=8.0, amplitude=0.6)
    note2_partial = tone(2400.0, 0.06, attack_s=0.002, decay=9.0, amplitude=0.3)
    n1 = min(len(note1), len(note1_partial))
    n2 = min(len(note2), len(note2_partial))
    part1 = [note1[i] + note1_partial[i] for i in range(n1)]
    part2 = [note2[i] + note2_partial[i] for i in range(n2)]
    return mix_sequence([(part1, 0.012), (part2, 0.0)])


def render_binaural(mono, azimuth_deg):
    """Stdlib HRTF approximation for one azimuth (0 = ahead, positive = clockwise/right, matching
    the old SpatialAudioClient.playPoint convention). Three cues, each doing a distinct job:
      - ITD (Woodworth's formula): the near ear leads, the far ear is delayed up to ~0.66ms.
      - ILD: the far ear is attenuated up to ~6dB, plus a head-shadow lowpass (duller, not just
        quieter — a real ear-canal effect).
      - Front/back spectral tilt: ITD and ILD both go to zero at 0 deg AND 180 deg, so without a
        third cue those two directions would render identically. A tilt scaled by "backness"
        (0 at dead ahead, 1 at dead behind) breaks the tie.
    Returns (left, right) sample lists, same length as `mono`, NOT peak-normalized — see
    build_point_dir_set for why normalization has to happen once, across the whole set.
    """
    az = azimuth_deg if azimuth_deg <= 180 else azimuth_deg - 360  # signed, [-180, 180]
    a_abs = abs(az)
    a_eff = a_abs if a_abs <= 90 else 180 - a_abs  # 0 at 0/180 deg, max (90) at +-90 deg
    sign = 1 if az > 0 else (-1 if az < 0 else 0)  # +1 = right is the near ear
    theta_eff = math.radians(a_eff)

    itd_s = (HEAD_RADIUS_M / SPEED_OF_SOUND) * (theta_eff + math.sin(theta_eff))
    delay_n = int(round(itd_s * SAMPLE_RATE))
    ild_db = 6.0 * math.sin(theta_eff)  # 0..6 dB
    far_gain = 10 ** (-ild_db / 20.0)
    shadow_cutoff_hz = 16000.0 - (a_eff / 90.0) * 12000.0  # 16kHz (front) .. 4kHz (side)

    near = list(mono)
    far = apply_lowpass([s * far_gain for s in delay_samples(mono, delay_n)], shadow_cutoff_hz)
    # sign >= 0 means the RIGHT ear is nearer the source (az in [0, 180], clockwise-positive).
    left, right = (far, near) if sign >= 0 else (near, far)

    # Front/back tilt, applied equally to both ears so it doesn't disturb the L/R balance above.
    backness = (1 - math.cos(math.radians(az))) / 2.0  # 0 = dead ahead, 1 = dead behind
    tilt_cutoff_hz = 18000.0 - backness * 8000.0
    left = apply_lowpass(left, tilt_cutoff_hz)
    right = apply_lowpass(right, tilt_cutoff_hz)
    return left, right


def build_point_dir_set():
    """Renders all 18 point_dir_* angle files with ONE shared peak-normalization scale factor.
    Per-file normalization would boost the attenuated far-ear channel right back up and erase the
    ILD cue that makes the effect work, so the scale has to be computed across the whole set."""
    mono = build_point_dir_source()
    pairs = {}
    peak = 0.0
    for deg in range(0, 360, POINT_DIR_STEP_DEG):
        left, right = render_binaural(mono, deg)
        pairs[deg] = (left, right)
        peak = max(peak, max((abs(s) for s in left), default=0.0), max((abs(s) for s in right), default=0.0))
    scale = (0.9 / peak) if peak > 0 else 1.0
    out = {}
    for deg, (left, right) in pairs.items():
        out[f"point_dir_{deg:03d}.wav"] = ([s * scale for s in left], [s * scale for s in right])
    return out


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    mono_sounds = {
        "point_touch.wav": build_point_touch(),
        "way_touch.wav": build_way_touch(),
        "aim_tick.wav": build_aim_tick(),
        "aim_lock.wav": build_aim_lock(),
        "welcome.wav": build_welcome(),
        "goodbye.wav": build_goodbye(),
    }
    for name, samples in mono_sounds.items():
        path = os.path.join(OUT_DIR, name)
        write_wav(path, samples)
        print(f"wrote {path} ({len(samples)} samples, {len(samples)/SAMPLE_RATE*1000:.0f} ms)")

    for name, (left, right) in build_point_dir_set().items():
        path = os.path.join(OUT_DIR, name)
        write_wav_stereo_prescaled(path, left, right)
        print(f"wrote {path} ({len(left)} samples/channel, {len(left)/SAMPLE_RATE*1000:.0f} ms, stereo)")


if __name__ == "__main__":
    main()
