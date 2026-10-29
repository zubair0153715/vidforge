# VidForge — FULL CapCut-feature audit (koi feature miss nahi hoga)

Rule: CapCut ka naam, logo, code, assets, templates ya licensed content kabhi
copy nahi hoga. Feature families (trim, filters, text, transitions) standard
editing concepts hain — inhe apne design/code se banaya jata hai. Har feature
ki status yahan likhi hai; koi feature chupke se drop nahi hoga.

## DONE — VidForge v1.0–v1.7 me maujood
- Video import (system picker), preview player (play/pause/seek)
- Trim (start/end), Split at playhead, Join two clips (Cut lossless + Fade)
- Playback speed 0.5x/1x/1.5x/2x, baked speed on effects export
- Mute audio, orientation hint (0/90/180/270), lossless export, share
- Video info (duration/resolution/size), export to Movies/VidForge
- Filters baked into export: None, Grayscale, Sepia, Warm, Cool, Invert, Vignette
- Text overlay (Top/Center/Bottom), sticker shapes (Circle/Star/Heart/Arrow)
- Crop: Full / Square 1:1 / Wide 16:9 / Vertical 9:16 (real output size)
- Cover frame (playhead frame → Pictures/VidForge PNG)
- Volume 50%/100%/150%, audio fade in/out (1 sec)
- Background music mix (apni audio file, half volume, looped, resampled)
- Rotate / flip actual pixels (90°/180°/270°, flip H/V) on effects export
- Freeze last frame (2 sec / 3 sec) on effects export
- Export quality: Original / 720p / 1080p / 4K upscale (upscale detail nahi banata)
- Extract audio (trim range → Music/VidForge .m4a, no re-encode)
- Voiceover recording (mic → effects export me mix; RECORD_AUDIO permission)
- Dissolve + Slide transitions (dual-decoder crossfade, 0.5s overlap)
- Reverse video (frames ulta; audio drop hota hai; 900 frames cap)
- Speed curves (Slow→Fast, Fast→Slow; curve par audio drop hota hai)
- Keyframe text animation (Slide in, Fade in)
- Chroma Key v1.7 (green screen → solid black; single + dual/join engine dono me)
- Offline (voiceover ke liye mic permission ke ilawa koi permission nahi), zero ads

## NEXT — offline engine me ban raha hai (agle rounds)
- [ ] Masking / blending modes (overlay ko blend karna)
- [ ] Video stabilization (shaky video ko smooth karna, on-device)
- [ ] Multi-clip timeline (2 se zyada clips join + per-clip trim)
- [ ] Text/sticker overlay on dissolve/slide joins (dual engine me abhi filter only)

## SERVER — user ki ijazat se (2026-10-28): internet/server par chalne wale bhi
- [ ] Cloud project backup (project save/load server par)
      UNBLOCKED 2026-10-28 ~17:55: FastAPI Cloud login + deploy dono kaam kar
      rahe hain (Ujala live) — server round ab ban sakta hai
- [ ] Original asset pack server se (apne transitions/effects/music catalog)
- [ ] Auto-captions (speech-to-text) — free route test karna hai: phone ka
      speech recognizer file par nahi chalta; server par faster-whisper
      container RAM me tight hai, test ke baad confirm hoga
- [ ] AI background removal — free route test karna hai: on-device ML Kit
      segmentation (Play services, koi key nahi) ya server model
- [ ] Chroma key (green screen) — on-device mumkin hai, server ki zaroorat nahi
- [ ] Text-to-speech voiceover — free on-device TTS engine se mumkin hai

## LICENSED / PAID — waise ki waise kabhi nahi (original alternative banega)
- Premium template feed (CapCut ke templates) → apne original templates
- Licensed music / sound-effects library → apni original royalty-free pack
- Digital human / AI avatar video → server AI model chahiye, paid APIs ke baghair
  test ke baad; jab tak test na ho, wada nahi
- Beauty / retouch AI filters (server models) → basic on-device version pehle

## NOTES
- v1 export lossless hai isliye trim/split/mute/orientation instant hain.
- Filters/text/transitions ke liye video ko dobara encode karna parta hai.
- Har version ke baad ye checklist update hogi.
