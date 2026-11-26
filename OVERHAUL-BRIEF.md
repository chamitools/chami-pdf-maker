# Chami PDF Maker — Professional Overhaul Prompt (for Claude)

## What this is
Overhaul the CURRENT app. Do NOT rebuild from scratch. Keep every working tool and all logic. Make the UI better, the app faster, and everything professional and good.

The full code is in `chami-pdf-maker-full-code.txt` (every source file in one document). The project is also on GitHub: chamitools/chami-pdf-maker, branch `remove-reader`, commit `adfee92`.

## Starting point
- Package `com.chami.pdfmaker`, v1.2 (versionCode 2), minSdk 26 / targetSdk 36.
- 9 tools: Images to PDF, Merge PDF, Split PDF, Protect PDF, Compress KB, PDF to Images, Image Resizer, Format Convert, OCR.
- Home groups: Images / Fix a PDF / Convert. Images to PDF is tile #1.

## DO NOT CHANGE — locked
- COLOUR: do NOT change the colour. Keep Executive Emerald exactly as it is — green theme (`emerald_pro`), white background, bright green #22C55E / dark green #16A34A / deep green #14532D. No indigo, no violet, no dark theme, no clean-light retheme, no new colour scheme.
- LOGO AND MASCOT: remain the same. The panda face-only launcher icon and the Chami panda mascot stay exactly as they are. Do not redraw, recolour, replace or redesign them.
- All 9 tools' behavior, inputs and outputs. No tool added or removed. No Reader (removed 2026-11-19), no Scan, no Passport Photo, no PDF Editor, no Text to PDF, no PDF to Text.
- Offline-first. No INTERNET permission. No ads. No signup / account. No Pro / in-app purchases.
- FLAG_SECURE always ON (screenshots / recordings blocked) in MainActivity and IntroActivity.
- Ask Where to Save always (no toggle). Haptic feedback on. Slide to Convert on all tools. Google Pay-like Done screen (green tick, confetti).
- 445 string keys x 10 languages in `LocaleHelper.kt` — do NOT break existing keys. If you need a new string, add it to the English map only and list every new key in your summary.
- `<queries>` in `AndroidManifest.xml` (added 2026-11-19 — it is what makes Done "Open" find the phone's PDF viewer apps). KEEP it.
- Check for Updates opens `https://chamitools.github.io/chami-pdf-maker-website/#download-app`. KEEP it.
- Privacy policy: `https://chamitools.github.io/chami-privacy/`

## MAKE IT BETTER — the overhaul
- UI BETTER: commercial store standard. 360–412dp layouts, safe-area insets, 48dp touch targets, 8dp grid, no placeholder UI, no dead buttons. Home, all 9 tool screens, Done screens, History, Settings — consistent headers, buttons, dialogs and cards.
- FASTER: fast startup, fast convert, smooth 60fps scrolling, no lag, no jank, no frozen screens.
- WORKING TOOLS: every tool must actually work perfectly — not just look good. A tool that looks nice but fails is a failure.
- EASY TO USE: simple flows anyone can follow. Plain words, big clear buttons, no confusion, no extra steps.
- ALL DEVICES: compatible for all devices. All screen sizes, Default font AND Large font (1.15x) — nothing clipped, nothing overlapping. Android 8.0 and above.
- ANIMATION: smooth, purposeful animations (page transitions, Done tick, press feedback). All animations respect the system's Remove Animations setting.
- FONT, SPACING, LAYOUT: good typography, consistent font sizes, proper spacing everywhere, clean aligned layouts.
- PROFESSIONAL AND GOOD: polished empty states and error states in plain words (no error codes). Skeleton loaders while PDFs / images load. WCAG AA text contrast. Keep "CHAMI PRO" / "Professional PDF Toolkit" branding.

## Phases — do in order, stop after each for review
- Phase 1: Audit. List every problem per screen (with file + line): UI issues, slow parts, tools that don't work right, layout/font/spacing issues. Then fix the list. No behavior changes beyond the fixes.
- Phase 2: Home + tool screens — UI, speed, layout, animation.
- Phase 3: History + Settings + Done screens + final polish, then verify (below).

## Verification — must do before calling it done
- Build the signed release APK with the repo's GitHub Actions workflow (`build-apk.yml`, included in the code) and fix all errors. Do NOT change the keystore or signing (upload key, alias `upload`).
- Screenshot every screen at Default + Large font and check nothing is clipped.
- Confirm: 9 tools on Home, no Reader, no Scan; colours unchanged (Emerald green); logo and mascot unchanged; Check for Updates opens the website; Done "Open" shows the system app chooser; no new permissions in the manifest.

## Do not
- Do not change the colour, logo, mascot, package name, versionCode, or app name.
- Do not add INTERNET, CAMERA, or any new permission.
- Do not add ads, accounts, or paid features.
- Do not re-add the Reader, Scan, or any removed tool.
- Do not merge anything to `main` — work on this branch.
