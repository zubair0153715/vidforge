# VidForge — Original Android Video Editor

Free, original Android video editor built clean-room: **no CapCut name, code, design, templates, licensed music, or digital humans** — only standard editing features, implemented from scratch.

## Features (v1.7)

Pick/preview/trim/split/speed/mute/orientation, lossless export + share, re-encode engine (filters, text overlay, baked speed), crop, cover frame, stickers, volume, audio fade, music mix, join two clips, rotate/flip, freeze frame, export quality, extract audio, voiceover recording, dissolve + slide transitions, reverse video, speed curves, keyframe text animation, and Chroma Key (green screen) as filter 7.

See [FEATURES.md](FEATURES.md) for the full audited feature list.

## Build

Manual pipeline (no Gradle): aapt2 → javac → kotlinc → d8 → zipalign → apksigner.

```sh
./build-apk.sh
```

Requires a JDK 21 (the script expects one on PATH) and the Android SDK build tools. Package: `com.vidforge.editor`, minSdk 29, target 34. Only permission: microphone (voiceover recording).

## Status

v1.7 is usable on-device. Still to come (standing build order): server AI features (cloud backup, auto-captions, AI background removal — free routes tested first), masking/blending modes, video stabilization, multi-clip timeline (2+ clips), and text/sticker overlay on dissolve/slide joins. Never planned: premium template feed, licensed music/SFX library, digital human/AI avatar, beauty AI filters.

## Honest limits

- Reverse video caps at 900 frames and drops audio.
- Dual transitions apply filters only (text/sticker compositing on joins is a known gap).
- Debug-signed APKs in this repo are test builds.
