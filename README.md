# Hush

Background noise remover for voice recordings. Android app (APK) built around a from-scratch DSP engine that runs fully on the device.

## How the denoiser works

All in `app/src/main/assets/index.html`, no third-party audio libraries:

1. **Pre-filtering** – zero-phase 80 Hz high-pass for rumble, then automatic mains hum detection (windowed Goertzel over 4 s chunks, 50 vs 60 Hz) and narrow notch filters on each detected harmonic.
2. **STFT** – 2048-point sqrt-Hann frames at 75% overlap (about 43 ms at 48 kHz), custom radix-2 FFT. Stereo pairs share one complex FFT, and every channel gets the same gain so the stereo image is kept.
3. **Noise estimate** – a profile from the quietest 10% of frames (or a user-selected noise-only region). In *Changing* mode it is tracked over time with minima-controlled recursive averaging.
4. **Gain** – optimally-modified log-spectral amplitude (OM-LSA) estimator: decision-directed a priori SNR, speech-presence probability from local, global and frame-level SNR, and a gain floor set by the Reduction slider. Low-presence bins get light frequency smoothing to suppress musical noise.
5. **Output** – overlap-add resynthesis, optional peak normalization, 24-bit WAV.

## Building

Pushing to `main` builds an APK artifact. Pushing a tag like `v1.0.1` builds and publishes a GitHub release:

```
git tag v1.0.1 && git push origin v1.0.1
```

Local build (Android SDK required): `./gradlew assembleRelease`.

### Signing

Releases are signed with the bundled key in `keystore/` so updates install over each other. Before publishing on Play Store, create your own key and add these repo secrets; the workflow uses them automatically:

| Secret | Value |
| --- | --- |
| `HUSH_KEYSTORE_BASE64` | `base64 -w0 your.jks` |
| `HUSH_STORE_PASSWORD` | keystore password |
| `HUSH_KEY_ALIAS` | key alias |
| `HUSH_KEY_PASSWORD` | key password |
