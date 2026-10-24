# Hush

Background noise remover for voice and video recordings. Android app (APK) built around a from-scratch DSP engine that runs fully on the device.

## Formats

**Opens:** anything Android or the WebView can decode — MP3, WAV, M4A/AAC, FLAC, OGG/Opus, AMR, 3GP, AIFF, WebM, MP4, MOV, MKV. Files the WebView can't read fall back to Android's own decoders. Supports **Share → Hush** and **Open with → Hush**.

**Saves** to `Downloads/Hush`:

| Format | Encoder |
| --- | --- |
| WAV 16 / 24-bit, 32-bit float | built in |
| FLAC 24-bit | built in (fixed-predictor + Rice coding, written from scratch) |
| AIFF 24-bit | built in |
| MP3 320 / 192 kbps | LAME (lamejs, bundled offline) |
| M4A (AAC-LC 256 kbps) | Android MediaCodec |
| OGG (Opus 160 kbps) | Android MediaCodec |
| Video (MP4 or WebM) | original video track copied untouched, cleaned audio muxed in |

## How the denoiser works

All in `app/src/main/assets/index.html`, no third-party audio libraries:

1. **Pre-filtering** – zero-phase 80 Hz high-pass for rumble, then automatic mains hum detection (windowed Goertzel over 4 s chunks, 50 vs 60 Hz) and narrow notch filters on each detected harmonic.
2. **STFT** – 2048-point sqrt-Hann frames at 75% overlap (about 43 ms at 48 kHz), custom radix-2 FFT. Stereo pairs share one complex FFT, and every channel gets the same gain so the stereo image is kept.
3. **Noise estimate** – a profile from the quietest 10% of frames (or a user-selected noise-only region). In *Changing* mode it is tracked over time with minima-controlled recursive averaging.
4. **Gain** – optimally-modified log-spectral amplitude (OM-LSA) estimator: decision-directed a priori SNR, speech-presence probability from local, global and frame-level SNR, and a gain floor set by the Reduction slider. Low-presence bins get light frequency smoothing to suppress musical noise.
5. **Output** – overlap-add resynthesis, optional peak normalization, 24-bit WAV.

## Building

Every push to `main` builds the APK. If the version in `VERSION` has no release yet, the workflow tags it and publishes a GitHub release with the APK attached. To ship an update, bump `VERSION` (for example to `1.0.1`) and push. Pushing a `v*` tag also works.

Local build (Android SDK required): `./gradlew assembleRelease`.

### Signing

Releases are signed with the bundled key in `keystore/` so updates install over each other. Before publishing on Play Store, create your own key and add these repo secrets; the workflow uses them automatically:

| Secret | Value |
| --- | --- |
| `HUSH_KEYSTORE_BASE64` | `base64 -w0 your.jks` |
| `HUSH_STORE_PASSWORD` | keystore password |
| `HUSH_KEY_ALIAS` | key alias |
| `HUSH_KEY_PASSWORD` | key password |
