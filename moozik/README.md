# Moozik

Minimalist HiFi music player for Android.

Kotlin + Jetpack Compose front end, C++ DSP core, AAudio output — built for
bit-clarity first: audio is decoded to float PCM and rendered at each track's
native sample rate, never resampled by Moozik itself.

## Features

- **Native-rate output** — every stream opens at the source sample rate
  (44.1/48/88.2/96 kHz+), so no hidden resampling.
- **Exclusive-mode attempt** — tries AAudio exclusive (MMAP) access that
  bypasses the Android mixer where the device allows it; falls back to shared
  mode automatically. The Now Playing screen shows which mode is live.
- **C++ DSP chain in the RT callback** — preamp → parametric cascade →
  graphic EQ, zero allocations on the audio path, TPDF-safe float pipeline.
- **AutoEq support** — import any `ParametricEQ.txt` (EqualizerAPO format,
  exactly what [AutoEq](https://github.com/jaakkopasanen/AutoEq) exports).
  PK/LSC/HSC filters + preamp line are parsed and applied live.
- **10-band graphic EQ** with a real-time frequency-response curve preview.
- **Local library** via MediaStore: artist/album/title browsing, queue-based
  playback with next/previous, auto-advance, seek.
- **Background playback** — foreground service + MediaSession media
  notification (headset/lockscreen controls).
- Formats: anything the platform decodes — FLAC, WAV, MP3, AAC, OGG Vorbis,
  Opus, ALAC. Float or 16-bit codec output is normalized internally.

## Architecture

```
Compose UI ── MediaSession ── PlaybackService (foreground)
                    │
              MoozikPlayer (queue/session orchestration, IO dispatcher)
                    │
        MediaDecoder (MediaCodec → interleaved stereo float)
                    │  lock-free SPSC ring buffer
        moozik_dsp.so  (biquad cascade · preamp · RT-safe process loop)
                    │
        AAudio output (EXCLUSIVE → SHARED fallback, native sample rate)
```

## Building

Requirements: JDK 17+ (Android Studio's bundled JBR works), Android SDK 36,
NDK r28, CMake 3.31 (installed automatically by the wrapper/Studio).

```
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # AutoEq parser + DSP math unit tests
./gradlew assembleRelease        # debug-signed release APK
```

APKs land in `app/build/outputs/apk/{debug,release}/`.

## Using AutoEq presets

1. Generate a preset for your headphones at autoeq.app (or use the GitHub
   database) and export `ParametricEQ.txt`.
2. Moozik → **EQ → IMPORT** → pick the file.
3. The curve updates instantly; playback applies it mid-stream with proper
   headroom (the preset's own preamp plus graphic boosts is honored).
4. Preset selection persists across launches; re-import after reinstall.

## Known limitations / roadmap

- Raw USB DAC access (UAC2 driver bypassing AudioFlinger entirely, like
  USB Audio Player Pro) — backend interface is ready for it; not implemented.
- DSD (DoP/native), ReplayGain, playlists beyond the play queue.
- Channels beyond stereo are folded to L/R (front pair).
- Streaming sources are out of scope for v1; the `PlayerTrack` model is
  designed so remote sources can plug into the same queue pipeline.

## License

MIT — see repo settings.
