# Grok 1.2.57 — assets + motion extraction

Source: `%TEMP%\zyvo\grok\grok.apk` (XAPK v2, `ai.x.grok` 1.2.57-release.01,
versionCode 11257201, minSdk 32 / targetSdk 36, base apk 57.8 MB + arm64 + en + mdpi splits).
Decompiled with jadx 1.5.1 → 45,281 java files, fully R8-obfuscated package names
(`a`, `a0`, `q5`, `l1`, …). Resource tree decoded separately with `jadx --no-src`.

## Icons — RECOVERED (usable)

`design/grok-study/icons/vector/` = **498 files** copied out of `res/drawable*/`:

| metric | value |
|---|---|
| files | 498 (`ic_*` + 2 wordmarks + cosmos logo + menu/refresh) |
| native size | 24dp × 24dp (886 of 1,000+ size attrs) |
| style | 491 fill-based, 105 stroke-based |
| fill colours | `#000000` / `#ffffff` (→ `android:tint` recolours them) |
| gradients | none in the `ic_*` set |

Preview page (opens in the browser):
`%TEMP%\zyvo\grok\icons\grok-icons.html` — 494 icons in a grid with names.
Raw JSON with every pathData: `%TEMP%\zyvo\grok\icons\vectors.json`.

Notable ones for our chat UI: `ic_agent`, `ic_agent_filled`, `ic_ai_agent`,
`ic_brain`, `ic_code_terminal`, `ic_code_block`, `ic_copy`, `ic_expand`,
`ic_collapse`, `ic_grok_refresh`, `ic_hourglass`, `ic_cursor`, `ic_gem`,
`ic_check_filled_circle`, `ic_circle_check_alt`, `ic_edit_file`, `ic_edit_square`,
`ic_clipboard_check`, `ic_grok_cosmos` (the G/xAI mark, 100×96), `bg_grok_wordmark`.

Brand set also recovered: `bg_grok_wordmark`, `bg_supergrok_wordmark`,
`ic_grok_menu`, `ic_grok_refresh`, `ic_launcher_foreground`.
Dropped as non-Grok: 1,000+ Media3 / AppCompat / Material / OpenMoji / file-type icons.

## Animations — NOT recoverable as files (by design)

Grok is **100 % Jetpack Compose** (0 layout XMLs) and R8-renamed, so its motion is
Kotlin code, not resources:

- `res/anim/` = 38 files, `res/animator/` = 16 files → **all AndroidX library**
  (AppCompat checkbox/radio, Material bottom-sheet/FAB, Fragment transitions,
  Intercom, Glance). Zero Grok-authored XML animations.
- 95 XML files in the whole tree contain `<animated-vector>` / `<animation-list>` /
  `<objectAnimator>` → library + `$avd_*` OpenMoji animated emoji. No app motion.
- **No Lottie**: no `lottie` package, no `.json` animation assets.
- `assets/i12g.mov` (441 KB) is not a valid MP4 (no `moov` atom) — unusable.
- Compose animation call sites (`animate*AsState`, `Animatable`, `tween`, `spring`)
  are inside obfuscated classes; the numeric duration/easing constants cannot be
  mapped back to a UI element without reading tens of thousands of renamed files.

### What we can lift instead
1. **Sound assets** — `assets/chimes/start.m4a`, `end.m4a`, `start02/04`, `end02/04`
   (~20–26 KB each): Grok's message start/end chimes.
2. **Motion patterns** (behaviour, re-implemented with our own ValueAnimator code):
   see the candidate list below.

## Candidate motion/visual additions for zyvo (View + Animator only, no new deps)

| # | Addition | Where | Cost |
|---|---|---|---|
| 1 | Grok `ic_*` set replaces our tool/action icons | `ChatAdapter.iconFor()`, action row | low |
| 2 | Send button: press scale 0.9 + spring back (overshoot 1.05) + haptic | composer | low |
| 3 | Streaming caret — blinking 2dp block at the end of the live reply | assistant text | low |
| 4 | Thinking dots — 3 dots breathing beside "Thought · Ns" instead of the arc alone | thought row | low |
| 5 | Card enter spring (translateY 8dp + alpha, spring damping 0.75) instead of linear 150ms | tool body / cards | low |
| 6 | Message action row (copy/regen/share) slides in 120ms after the reply finishes | footer | low |
| 7 | Chime on send / on reply done (respects silent switch) | ChatFragment | low |
| 8 | Drawer row press-in scale (0.98) + item fade stagger on open | drawer | low |
| 9 | Empty-home entrance: logo scale 0.94→1 + chips stagger 60ms | empty state | low |

Rule we keep: nothing loops while idle, everything honours
`ANIMATOR_DURATION_SCALE == 0` (reduceMotion).
