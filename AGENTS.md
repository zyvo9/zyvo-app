# Zyvo Code on Termux fork â€” project memory

Fork of `termux/termux-app` (package `com.termux`) carrying our Chat UI + the
bundled zyvo engine. Repo `zyvo9/zyvo-termux` (public, default branch **master**),
local `C:\Users\Admin\zyvo-termux`.

## Build / ship
- CI: `.github/workflows/zyvo-build.yml`, triggers on push to `master` +
  `workflow_dispatch`. Name filter: "Zyvo Termux Build". Release `apk-<run_number>`
  (arm64-v8a ~100MB + universal).
- Poll: `C:\Users\Admin\AppData\Local\Temp\zyvo\poll_termux.py <sha>`; failed log:
  `fetch_log.py <job-logs-url> out.log`.
- Push: `git fetch <token-url> "+master:refs/remotes/origin/master"` â†’ rebase â†’
  push `master:master` (NEVER `main` â€” that branch does not exist upstream).
- Local machine has NO Android SDK â€” CI is the only compiler. Brace-balance check
  before push: `python -c "s=open(f).read(); print(s.count('{')-s.count('}'))"`.

## Current state (2026-11-14, apk-32 = 3bae398)
- Context meter (P4 item 1): `ChatFragment.ctxTokens` = newest assistant
  `info.tokens` (input + output + cache.read, `tokensOf()`); strip shows
  `Working 12s Â· 8.2k ctx` / `Worked for 1m 22s Â· 12.4k ctx` (`ctxSuffix()`,
  `fmtTokens()`); replayed on session open from `api.messages()`.
- Queue (P4 item 2): busy + typed text â†’ send button becomes arrow and QUEUES
  (`queuedMessage` + `queued_bar` chip above working strip, Ã— cancels);
  busy + empty input â†’ stop/abort as before (`updateSendIcon()`); on
  `session.idle` â†’ `sendQueuedIfAny()` re-sends it. Queue cleared on
  new/open session.
- Gotcha: zyvo_strings.xml already had `cancel` â†’ duplicate resource breaks
  `mergeDebugResources`; new string is `queued_cancel_desc`.
- Device support: minSdk 21, targetSdk 28, compileSdk 36; splits list all ABIs
  but the engine `libzyvo.so` ships arm64-v8a only â†’ effectively 64-bit ARM
  Android phones.
- Wireless adb (2026-11-14): phone shows up as mDNS
  `adb-Z60plusBD2310070295-7kxTqf._adb-tls-connect._tcp` (user-supplied IP:port
  changes per toggle; the mDNS serial works without it).

## Earlier state (2026-11-13, apk-30 = a85bbc0)- Motion (P3, our own, reduce-motion aware): rows fade+rise 6dp/170ms once
  (`ChatItem.animated`, `ChatAdapter.animateIn`); running tool rows get a
  brand-blue sweep (`bg_sweep.xml` + `ToolVH.startSweep/stopSweep`, 1.3s,
  cancelled on recycle); `animateLayoutChanges` on tool/thought/todo roots;
  working bar fades in. `reduceMotion` = Settings.Global.ANIMATOR_DURATION_SCALE == 0.
- Todo card (P4): engine `todowrite` parts never render as tool rows â€” one
  `ChatItem.TODO` card ("Tasks n/m") is updated in place (`applyTodos`), rows
  âœ“/â—/â—‹/âœ•, completed = dim + strikethrough, `item_todo.xml` + `TodoVH`.
  Live: a first event with empty input may create a ghost row â†’ hidden then
  (`existing.hidden = true`). Replay: `parseMessages` merges all todowrite
  parts into one card. Engine also keeps a `todo` table in opencode.db
  (`session_id, content, status, priority, position`).
- Gotcha: engine tool part `state.title` for todowrite is "N todos", and
  `titleFor()` matched "write" â†’ ghost rows titled "Write"; now todo is matched
  first ("Tasks").
- DB inspect recipe: `su -c cp .../share/zyvo/opencode.db(-wal,-shm) /sdcard/`
  â†’ adb pull â†’ sqlite3 (`part.data` JSON: `type`,`tool`,`state.input/status/diff`).

## Current state (2026-11-13, apk-27 = e692d14)
- Tool rows (apk-26/27): flat, no card background, 11sp; icon 13dp +
  `Terminal/Write/Edit/Read` label + faint `text_disabled` target; `cleanCommand()`
  strips the `export HOME=â€¦;` preamble; diff shows `+N âˆ’N` (green/red) from
  `state.diff`, and `+N` live from `state.input.content` while a write streams
  (`ChatItem.liveLines`); bookkeeping writes (AGENTS.md/SKILL.md/vault) set
  `ChatItem.hidden` and render GONE (first part event has no input yet, so the
  hide check runs again once `cmd` is known).
- Thought rows neutral gray (`text_secondary` / `text_disabled`), brain pulse kept.
- Working bar above composer: `Working 12s` â†’ `Worked for 1m 22s`; setBusy(false)
  must NOT force GONE when `workingStartMs > 0` (a tool completing calls
  setBusy(false) before session.idle â€” the old code hid the finished duration).
- Assistant streaming shows `plainMarkdown()` (single-pass, no regex: strips
  `*`, backticks, heading hashes, `>` quotes, `- ` â†’ `â€¢ `); finished text renders
  full Markwon (tables/linkify/strikethrough). Engine prompt now enforces clean
  structure (sections, blank lines, bullets, tables, short lines) â€” shipped in
  ZYVO-AI `3e6bb4e`, baked into release `android-v1.17.9` (assets refreshed
  13:44Z; the fork CI pulls that tag, so a rebuild picks the new engine).
- adb now: `192.168.1.109:39111` (port changes per wireless-debug toggle).

## Current state (2026-11-13, apk-22 = 98c2e24)
- Dark-first: `ChatActivity.onCreate` forces `MODE_NIGHT_YES` (Zai dark tokens
  in `values-night/zyvo_colors.xml` now always active).
- Tool icons = lucide set copied from ZCode as `res/drawable/ic_lc_*.xml`
  (24Ã—24, stroke 2, tint at runtime); `ChatAdapter.iconFor()` maps tool name â†’
  glyph (todo â†’ list-checks, glob â†’ folder-search, read â†’ file-text, write/edit
  â†’ pen-line, web â†’ globe, grep â†’ search, task â†’ bot, skill â†’ sparkles).
- Thought rows: braille spinner replaced by `ic_lc_brain` that pulses
  (alpha 0.55â†”1, 1.4 s cosine) while streaming, static 0.75 when done.
- Single notification `ZyvoNotif` (channel `zyvo_engine`, id 4101, Awake/Exit);
  Exit = engine + EngineService + terminal stop (full app exit).
- Error surfacing (apk-21): `session.error` + `message.updated.info.error` â†’
  error card; 40s no-signal banner; 150s â†’ `failTurn()` card with rate-limit hint;
  drawer row **Engine logs** (tail 250 lines, copy). `EngineManager.isReady()`
  now checks `process.isAlive()`; `pumpLog` end flips state to ERROR; watchdog
  restarts a dead engine.
- Known live issue: opencode free lane `FreeUsageLimitError` per public IP. The
  engine retries silently (no event) â€” the app now shows it. Fix = fresh IP
  (mobile data). Verified with curl on device.

## UI decisions (user-owned, do not change without asking)
- No bottom nav; hamburger drawer (new chat, sessions, terminal, **Engine logs**,
  settings). No Files tab.
- Composer: 56dp/22dp, model name plain text beside send (no box), Build/Plan chip.
- Assistant text word-by-word reveal; Working row stays for the whole turn with
  elapsed seconds; no "Worked for" summary rows; user echo never rendered twice.
- Notification: ONE "Zyvo Code" entry only (Termux must never post its own).

## 2026-11-13 â€” next big task: ZCode-inspired redesign (user-approved plan)
Source of truth: ZCode desktop v3.17.2 Electron app at
`%LOCALAPPDATA%\Programs\ZCode\resources\app.asar` (374MB; React 19 + Tailwind v4;
main CSS `out/renderer/assets/styles-NK68EN_n.css`, 480KB, 59 keyframes).
- Decisions: **dark theme**, **inspire-only** (NOT a pixel clone, NOT ZCode's own
  animations â€” the motion system is OUR brand), but two things close: **tool icons**
  (lucide: brain/file-text/pen-line/terminal/search/list-checks/globe) and the
  **text-streaming feel**.
- Hard constraint: keep the app LIGHT â€” no new gradle deps, no Lottie/Compose/
  WebView; VectorDrawables + Android Animator only; APK delta target < 500KB;
  no idle animation loops; honour reduce-motion.
- Phases: P0 extract tokens/icons/motion â†’ P1 screen spec â†’ P2 dark theme port â†’
  P3 our own motion system (+ mascot last) â†’ P4 working-system UX (plan/build,
  todo panel, tool cards, diff, stop/queue, context meter) â†’ P5 build+verify.
- Study artifacts live in `design/zcode-study/` (tokens, icons, notes).

## 2026-11-13 â€” P0 DONE (extraction)
- `design/zcode-study/`: `styles.css`, `prop-map.json`, `keyframes.json`,
  `TOKENS_DARK.md` (202 dark tokens), `KEYFRAMES.md` (59), `SCALE.txt`,
  `NOTES.md` (decisions), `icons/` = 19 lucide vectors + `icons.json`.
- Icons: 24Ã—24 viewport, stroke 2, white stroke â†’ tint at runtime. Mapping in
  NOTES.md Â§4. Copy into `res/drawable/` as `ic_tool_*` in P2.
- Dark palette ALREADY exists in `values-night/zyvo_colors.xml` (Zai dark) â€”
  P2 = refine + make it the app default (user wants dark), not build from zero.
- Extractor scripts (PC temp): `extract_zcode.js`, `extract_icons.js`,
  `dump_tokens.js`, `dump_scale.js`; asar reader pattern documented in
  `extract_zcode.js` (header offsets: size@4, jsonLen@12, data base 8+size).
- Screenshot of ZCode home saved at `Temp\zyvo\zcode-1.png`; window capture +
  click helper `shot_zcode.ps1` / `click_shot.ps1` (SetForegroundWindow is
  unreliable â€” Windows blocks foreground steal; verify before trusting clicks).
- Next: P2 dark default + tool icons, then P3 own motion (NOT ZCode keyframes).

## adb / device
- Wireless debugging screen shows the VPN address (10.8.0.x) â€” UNREACHABLE from
  the PC. Take the SAME port it shows and use the phone's LAN IP:
  `adb connect 192.168.1.109:<port>` (LAN IP changes; `Test-Connection` each
  candidate). mdns discovery is often empty â€” don't wait on it.
- Install: `adb -s <ip:port> install -r -d <apk>`; screenshot:
  `shell screencap -p /sdcard/x.png` then `pull`.

## 2026-11-14 (later) â€” Grok/ChatGPT-class UI port (apk-36/37)
- Source study: Grok 1.2.56 xapk (ai.x.grok, minSdk 32 -> CANNOT install on the
  Android-12 phone, so no live shots). 100% Compose, R8-renamed; tokens taken from
  Play Store screenshots + strings. Full write-up: `design/grok-study/NOTES.md`.
- Grok's 3 menus = top tabs **Ask / Imagine / Build** (white active label + 16dp
  underline), circular hamburger left, circular new-chat right, pure-black bg,
  user bubble #111113 r22, **assistant = plain text, no bubble**, composer
  #19191B r26 with + / mode pill / white send pill. Font = Chirp (proprietary)
  -> we bundle **Inter 400/500/600** (`res/font/inter.xml`).
- Ported: top tabs **Chat / Terminal / Settings** (Terminal/Settings open the
  Termux activities), new tokens in `values-night/zyvo_colors.xml` (bg #0A0A0B,
  card #16171A, text #ECEDEE / #9A9CA3), `Theme.Zyvo.Chat` in
  `values/zyvo_styles.xml` (Inter + bg window), user bubble r22 16sp,
  assistant 16sp/lineSpacing 6dp, composer r26.
- Gotcha: removing a view from a layout breaks `R.id` in Java at compile time â€”
  after the top-bar rewrite, `title`/`top_engine_dot`/`model_chip` lookups
  became null assignments (build 36 javac failure, fixed in 68efb12).
- PC tooling for this: REA 6.7.0 (`%APPDATA%\npm\rea.cmd`) + jadx-headless-mcp
  0.7.1 JAR at `~/.local/share/rea/android/`; jadx CLI at `C:\Users\Admin\Tools\jadx`;
  apktool 2.11.1 at `C:\Users\Admin\Tools\apktool`. REA `load_apk` with full
  resources OOMs on this 3.4GB laptop -> use `jadx --no-res --single-class <cls>`
  or apktool smali + grep.

## 2026-11-14 (apk-38) â€” tool cards + real token streaming
- Tool rows are now CARDS for every tool (g_tool: card #16171A + 1dp line,
  r10, padding 9dp). Stage cycle on tap: 1 line -> 40 lines -> all -> 1 line
  (ChatItem.expanded + ull, ChatAdapter.nextStage). Running tools show a
  3-line live TAIL; failed tools 6 lines; meta line "N lines Â· tap to expand".
- +N âˆ’N is a pill (g_diff_pill) that toggles ChatItem.showDiff and renders
  the real diff inline (diffSpans: + green / - red / @@ dim, 300-line cap).
- STREAMING ROOT CAUSE: the engine publishes token-level message.part.delta
  {sessionID, messageID, partID, field:"text", delta} for BOTH reasoning and
  answer text (verified inside the shipped libzyvo.so of apk-37; engine source
  packages/opencode/src/session/processor.ts:398/785). message.part.updated
  only fires at part START (empty text) and END â€” our client ignored deltas, so
  "Thinking" appeared fully formed together with the first tool call. Now:
  ChatFragment.handlePartDelta -> pplyDelta, partTypes map (pid->type),
  pendingDeltas buffer for deltas that overtake the snapshot, thought rows
  created on the empty reasoning snapshot, dirtyThoughts flushed every 60ms.
- Second smoothness bug: startReveal() used to emoveCallbacks + repost every
  token, so a fast stream postponed the reveal forever (text appeared only when
  the stream paused). Now guarded by evealScheduled (one queued tick at a
  time); same guard for 	houghtScheduled.
- Thought rows now show a live 2-line dim TAIL while streaming (indThought),
  so reasoning is visible as it arrives, not only after expand.
- Verified resource refs with Temp\zyvo\check_refs.py (R.id/string/drawable
  vs res/) before pushing â€” no Android SDK locally, so this + XML parse is the
  pre-flight.

## 2026-11-14 (apk-40) - UI now mirrors the zyvo CLI itself
- User asked to study the CLI and match it ("zyvo te valo kore dekho"). TUI
  source: packages/tui/src/routes/session/index.tsx - PART_MAPPING = text /
  tool / reasoning; ReasoningPart = 1-line collapsed header + duration;
  ToolPart splits BlockTool (Shell, Write, Edit, ApplyPatch - panel card with
  a left border, command line shown only when open) vs InlineTool (read, grep,
  glob, task, todowrite - flat 1-2 line row, hover, failed = red, denied =
  strikethrough); DiffStats = +N green / -N red; ToolPart.shouldHide hides
  bookkeeping write/edit to AGENTS.md, ZyvoVault, SKILL.md, skills/.
- Palette: packages/tui/src/theme/assets/zyvo.json is the DEFAULT theme
  (config/theme.tsx kv.get("theme","zyvo")). Warm coffee/amber:
  darkStep1 #0e0c09 (bg), step2 #16130e, step3 #1f1b14, step4 #29241b,
  step5 #332d22, step6 #3e3729, step7 #4a4232, step8 #5c5340,
  step9 #e8a33d (primary amber), step10 #ffc27a, step11 #8a8172 (muted),
  step12 #f2ede2 (text), secondary #d9b45a, accent #e8734a, red #e06c75,
  orange #f5a742, green #7fd88f, cyan #56b6c2, yellow #e5c07b.
  Markdown: heading=accent, link=step9, code=green, strong=orange.
- Android: values-night/zyvo_colors.xml rewritten to that palette;
  bg_block.xml = panel r3 (layer-list android:width needs API 23, so the 3dp
  left border is a real View: left_border in item_tool.xml);
  item_tool.xml = tool_card > left_border + tool_inner (head row + cmd_line
  "$ command" + output + expand + diff_body + sweep);
  ChatAdapter.bindTool picks block (shell/write/edit/patch) vs inline,
  bg_sweep is amber now. Build 40 green.
- Local APK delivery without adb: python -m http.server 8485 in
  Temp\zyvo\apkserve + cloudflared quick tunnel ->
  https://racing-nut-license-specialist.trycloudflare.com/zyvo-40.apk
- User preference (2026-11-14): the app's look must follow the zyvo CLI itself (warm amber, block/inline tool rows) - NOT a Grok clone. Grok study was only a layout reference.
- Design split (final, 2026-11-14): SHELL from Grok (top tabs + underline, circular hamburger/new-chat, user bubble r22 / assistant plain text, r26 composer, Inter, empty-state chips, FAB) + CONTENT from the CLI (amber coffee palette, block/inline tool rows, reasoning header, mono code, +N -N). Not Grok's neutral #080808, not Grok's voice-first Speak pill.

## 2026-11-14 (apk-41) - Grok-style motion pass
- Tabs now share ONE underline (tab_row + tab_underline in fragment_chat.xml);
  each tab keeps an 8dp spacer instead of its own 3dp view. ChatFragment
  moveUnderline(tab, animate) centres it under the tapped tab, 220ms decelerate;
  underline colour = @color/accent (amber).
- Empty/home screen entrance: animateEmpty() staggers empty_title, empty_hint,
  chip1..3 with alpha 0->1 + 12dp rise, 50ms apart, 300ms each.
- Thinking shimmer: ThoughtVH.startShimmer() runs a LinearGradient highlight
  across the "Thinking..." title (ValueAnimator 1250ms infinite, Matrix
  translate, dim #8A8172 -> bright #F2EDE2); stopped on completion/recycle.
- Small feedback: send button scales to 0.9 on touch-down; scroll-to-bottom FAB
  pops in with OvershootInterpolator 1.3.
- All of it respects animator_duration_scale via animationsOn().
- Existing motion (kept): fadeIn rows (170ms, 6dp rise), word-by-word reveal
  32ms, braille tool spinner, sweep band on running tool, working-bar fade.
- Build 41 green; APK served at .../zyvo-41.apk.

## 2026-11-14 (apk-42) - streaming perf: why it stuttered, what fixed it
- Symptom: word streaming felt laggy/jumpy, thinking row flickered.
- Causes (all in the app, engine was fine):
  1. revealTick did a FULL notifyItemChanged every 32ms -> whole row rebind:
     plainMarkdown over the whole text, setText (full re-layout), footer,
     action visibility, animateIn path.
  2. step = max(3, backlog/8) at 32ms -> geometric catch-up, text stayed far
     behind the model.
  3. thought flush did a full rebind every 60ms and tail() used split("\n")
     on the entire reasoning text (allocation per bind).
  4. scrollIfPinned ran on every tick (60 scroll calls/s) -> bounce.
  5. reasoning was capped with substring(0,6000) -> the live tail FROZE on
     long thinking; now keeps the last 6000 chars.
- Fix: ChatAdapter.PAYLOAD_STREAM + 3-arg onBindViewHolder -> bindStreamText /
  bindStreamThought touch ONLY the TextView (and hide the footer/actions).
  Reveal: 16ms tick, step = max(2, (backlog+3)/4), word snap only when
  backlog > 32, full bind exactly once when the stream finishes.
  tail(): backward lastIndexOf scan, no array allocation.
  Scroll: scrollIfPinnedThrottled() max once per 60ms while pinned.
- Verification gate used: Temp\zyvo\check_refs.py (braces + R refs) then CI.

## 2026-11-14 (apk-44/45) - Grok-black theme + Codex tool rows + error system
- User decision OVERRIDES the old amber-coffee direction: **full Grok black**
  (1A) - bg #000, text #FFF/#9A9A9A/#6B6B6B, line #262626, card #1A1A1A,
  user bubble #2A2A2A, amber #E8A33D ONLY on the send button; tool rows are
  **flat** Codex-style (2A action row = copy + regenerate + share; 3A one build).
- Silent-quota root cause (fixed 2bd5849, apk-44): engine publishes
  `session.status` retry events every ~2s with NO message part - app now
  renders one live retry card + "Retrying - attempt N" strip.
- apk-45 (2966682): flat tool row = 26dp outlined icon box + gray verb label
  (`labelFor`: Ran command / Writing file / Wrote file / Read file ...) +
  +N -N + bold white title + chevron; tap 10 lines -> full -> closed.
  Thought row = lightbulb + "Thought for 4s >" (shimmer updated to grays).
  Reply action row btn_copy/btn_regen/btn_share; regen re-sends the prompt
  above (`regenerateReply`). Errors: `reportError(name, raw)` dispatches the
  engine error union - MessageAbortedError = footer "interrupted" only,
  ContextOverflow/OutputLength/StructuredOutput/APIError = red-border panel
  (item_error.xml) under the reply, auth/quota = amber notice; `errorName()`
  reads name from the error object (or .data.name).
- Preflight recipe: `Temp\zyvo\paren_check.py` (strings/comments stripped -
  the raw-count check_refs.py gives false positives) + xml.dom.minidom parse
  of every res XML.

## 2026-11-16 - full-project review + verified release state (apk-86)
- HEAD = origin/master = 7d6651a (nothing unpushed). apk-86 (run 86, green)
  publishes THREE assets: arm64-v8a 97.5MB, universal 222.4MB, x86_64 81.4MB.
  Verified by unzipping the x86_64 APK: lib/x86_64/libzyvo.so = 95.5MB (the
  real x86_64 engine) + libopentui/libc++_shared + libtermux-bootstrap 29MB.
  => "one APK for both arches" ALREADY works: the universal APK carries both
  engines; per-ABI APKs are the slim option. Engine zips: aarch64 53.4MB,
  x86_64 39MB; the x86_64 zip has NO librust_pty_* and NO libtagfix.so (arm64
  only), so BUN_PTY_LIB stays unset on x86_64 (EngineManager has the
  librust_pty_x86_64.so fallback but that file does not exist).
- 32-bit (armeabi-v7a / x86) is impossible: engine is a Bun standalone and Bun
  ships no 32-bit target. EngineManager.doStart already fails with a clear
  "needs 64-bit Android" message.
- REVIEW FINDINGS (open, priority order):
  P0-1 ChatFragment session filter treats sessionId==null as "accept every
  event" (handleEvent/handlePart/handlePartDelta): pressing New chat mid-turn
  leaks the old turn's parts into the empty chat.
  P0-2 zyvo-adb bridge is dead: the script reads $HOME/.zyvo-adb-token
  (EngineManager.writeAdbBridge) but the token is only ever written to
  filesDir/adb_bridge_token (AdbBridgeServer) - token never matches.
  P0-3 ApiClient.http() sets readTimeout(0) for ALL calls; one hung REST call
  blocks the single-thread `io` executor (send/replay/drawer/abort) forever.
  P0-4 session.idle guard can strand a turn that ends on a tool
  (lastWasTool stays true) -> busy stays on until Stop.
  P0-5 repo is PUBLIC and the lane key is baked into BuildConfig -> anyone can
  download the APK and extract ZYVO_OPENCODE_KEY.
  P1: regenerate appends a duplicate user prompt instead of rewinding; replay
  can race live events (loadMessages clears items after they arrived); queue
  holds only one message; engine.log opened+closed per line; PARTIAL_WAKE_LOCK
  held with no timeout; Prefs.onboardingDone/systemPrompt/gatewayKey/
  serverPassword have no UI; README is stock Termux; no ZYVO app icon; stock
  Termux workflows (Build / Unit tests / Dependency submission) run and fail on
  every push; debug-signed APK; no tests or lint gate; x86_64 engine never
  device-tested (no emulator smoke job).
- Remaining 40% (product shell): own Settings screen (model/key/system prompt/
  server password/ADB/logs/about), first-run onboarding, true regenerate +
  edit/branch, live model catalog from /config/providers, release signing +
  versionCode bump, per-ABI default downloads, docs/screenshots.

## 2026-11-16 - locked UI pass (user-approved decisions)
- Fix P0-1..P0-4 (P0-5 public-key SKIPPED by user). Then UI:
- Tool card = Codex-style code card: header (tool icon + language label +
  copy button ONLY, no play) + mono body. DEFAULT COLLAPSED for every status
  incl. running (spinner in header, body closed); tap cycle closed -> 10 lines
  -> full -> closed.
- Line numbers ONLY on write/edit bodies (green gutter, numbered). Run command
  and read output = plain mono, NO gutter.
- Diff: +N -N moved AFTER the file name; body = line-number gutter + full-width
  row wash (green #1E3B26 / red #3A2225) + syntax-colored code.
- Group header replaces the "Explore · N files" card: one collapsible header
  per tool batch with live counts "Explored N items · Ran N commands · Changed
  N files", default expanded; item_summary.xml restyled (14dp icon, 13sp
  #B4B7BD text, chevron). Only non-zero categories are shown; tapping the
  header hides its tool rows (GONE) and flips the chevron; a new assistant
  text/thought closes the batch and the next tool starts a fresh header.
- Home: minimalist Z logo (Desktop\MouseWithoutBorders\minimalist-app-icon-logo.jpg)
  as watermark on the empty/new-chat screen, width 72%, alpha 0.12.
- Launcher icon: replace stock Termux icon with the ZYVO logo (adaptive +
  legacy PNG set + roundIcon).
- Thought row: "Thought · 4s" gray, no bold "for". Inline code chip #1A1A1A bg /
  #C9CDD3 text. Tokens: bg #0F0F10, card #17181A, line #262626, text #ECECEC /
  #9A9CA3 / #6B7075.

## 2026-11-16 - UI pass SHIPPED (apk-88 = 531912e, CI green)
- All of the above implemented. Details worth remembering:
  - Run group: `batchCard/batchExplore/batchRun/batchChanged/groupSeq` in
    ChatFragment; first tool inserts the header BEFORE the leading thought
    rows and adopts them (group id); `countTool()` classifies
    write/edit/patch=Changed, isExploreTool=Explored, else Ran;
    `updateBatchHeader()` rebuilds the live text; `closeBatch()` collapses
    (called on assistant text part, sendMessage, finishTurn).
  - ChatAdapter: `groupCollapsed()` scans back over TOOL/THOUGHT rows;
    `capSpans()`, `langFor()`, `extOf()`, `nextStage()`;
    diffSpans = gutter + solid full-width washes + syntax colors;
    numberedBody = green gutter (write only); bindThought honors group.
  - P0-2: AdbBridgeServer token moved to `files/home/.zyvo-adb-token`
    (chmod 600) - matches the `$HOME/.zyvo-adb-token` read in the
    `zyvo-adb` script.
  - P0-3: ApiClient `http()` read=60s, `sse()` read=0; subscribe/rawCall use sse().
  - P0-1: sessionId volatile + `mine(sid)` filter (also added to session.idle).
  - P0-4: session.idle -> 900ms `idleSettle` (skips while a tool runs or a
    newer signal arrived).
  - Home bg: `drawable/new_chat_bg.png` regenerated as 1080x2400 #0F0F10 with
    the Z+ZYVO art at ~14% opacity (crop 42,200 699x753 of the source jpg,
    content bbox 130,138 457x441 + 24px pad). The `empty` container keeps this
    as its background - no extra ImageView needed.
  - Icons: `ic_foreground.png` (432, alpha-keyed white art), legacy
    mipmap-*/ic_launcher(.round).png regenerated. `ic_bg_card` stays the
    PRE-EXISTING layer-list XML - do NOT add a PNG with that name (duplicate
    resource kills mergeDebugResources).
  - UTF-8 hazard: PowerShell Get-Content/Set-Content mangles `·`; edit such
    files with python script files, not PS.
- Phone install pending: adb had no device (wireless debugging off / port
  changed). Install apk-88 arm64 when the phone is reachable.

## 2026-11-16 - LOCAL PC BUILD ENV (Android Studio on DESKTOP-DHVMQAB)
- Project path on this PC: `C:\Users\Admin\Documents\zyvo-termux`
  (NOT `C:\Users\Admin\zyvo-termux`).
- Android SDK root = `C:\Users\Admin\AppData\Local\Android` (this is what
  Android Studio 2026.2.2 has registered in jdk.table.xml). It now holds
  platform-tools, build-tools 35.0.0 + 36.0.0, platforms android-36 &
  android-37.0, ndk/29.0.14206865 (2.3 GB), cmdline-tools/latest, licenses.
  A second half-SDK `...\Android\Sdk` still exists but is no longer used
  (its build-tools/36.0.0 + platform-tools were moved into the root).
- `local.properties` -> `sdk.dir=C\:\\Users\\Admin\\AppData\\Local\\Android`.
- Gradle 9.2.1 wrapper + AGP 8.13.2; `gradle/gradle-daemon-jvm.properties`
  (untracked, auto-generated) pins the daemon JVM to **25** - that works,
  because Android Studio's JBR is Temurin 25 (`C:\Program Files\Android\
  Android Studio\jbr`). CLI builds must set JAVA_HOME to that JBR; plain
  `java` on PATH is Temurin 21 and would trigger a JDK-25 auto-provision.
- Engine/bootstraps are NOT in git (`.so`/`*.zip` ignored). To make a local
  build equal to CI, mirror `.github/workflows/zyvo-build.yml`:
  download `https://github.com/zyvo9/ZYVO-AI/releases/download/android-v1.17.9/`
  `zyvo-1.17.9-android-aarch64.zip`, `...-x86_64.zip`, `skills.tar.gz`;
  `zyvo.bin` -> `app/src/main/jniLibs/<abi>/libzyvo.so`, `usr/lib/*.so` ->
  same dir, `skills.tar.gz` -> `app/src/main/assets/`. Bootstrap zips are
  downloaded by the `downloadBootstraps` Gradle task (needs network).
- Build command (PowerShell, from the project dir):
  `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr";
   $env:TERMUX_PACKAGE_VARIANT="apt-android-7";
   .\gradlew.bat assembleDebug --console=plain -PzyvoBuild=900`
  Outputs: `app/build/outputs/apk/debug/app-<abi>-debug.apk` + `app-universal-debug.apk`.
  Keys stay empty locally (`-PzyvoOpencodeKey` / `-PzyvoGatewayKey` are CI secrets;
  P0-5 lane key is deliberately skipped).
- Phone install: `C:\Users\Admin\AppData\Local\Android\platform-tools\adb.exe
  install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk`.

- VERIFIED 2026-11-16: full local `assembleDebug` -> BUILD SUCCESSFUL (1m50s first
  run after bootstraps). Artefacts in `app/build/outputs/apk/debug/`:
  `termux-app_apt-android-7-debug_arm64-v8a.apk` 97.6 MB, `_x86_64` 81.4,
  `_armeabi-v7a` 38.4, `_x86` 40.9, `_universal` 222.5 MB - byte sizes match
  CI apk-88. Engine confirmed inside the APK: `lib/arm64-v8a/libzyvo.so` 165 MB,
  `libopentui.so`, `libtermux-bootstrap.so` 29 MB, `assets/skills.tar` (aapt
  renames skills.tar.gz -> skills.tar; EngineManager tries both names).
- First-run `termux-shared:buildNdkBuildDebug[x86_64]` ProcessException
  ("NDK_PROJECT_PATH==null. Please explicitly set APP_BUILD_SCRIPT") was TRANSIENT -
  the same task succeeded untouched on re-run; always retry once before touching NDK config.
## 2026-11-16 - WORKFLOW RULE (user decision, follow every session)
- zyvo writes CODE ONLY. Do NOT run `gradlew` / builds after each change.
- User tests everything in ONE batch on a phone connected to Android Studio,
  then builds the APK himself (Build > Build APK(s)) - he is sitting in front of Studio.
- So: implement all requested features/changes first, keep the tree buildable,
  tell him "Run (Shift+F10) on the phone" only when the batch is complete.
- Only run a build if he explicitly asks or the code has a compile doubt he wants checked.
- 2026-11-16: phone **Z60 plus** (Android 12, arm64-v8a, IP 192.168.1.117) paired over
  wireless debugging; `com.termux` 0.118.0 / versionCode 118 is ALREADY installed
  (lastUpdateTime 2026-11-16 02:53 = apk-88), so the apk-88 install task is DONE.
  Reconnect later with `adb mdns services` -> `_adb-tls-connect._tcp` port then `adb connect`.- 2026-11-16: USER ASKS TO SEE THE APP UI -> take it off the phone, don't describe it:
  `adb -s <ip:port> exec-out screencap -p > shot.png` then `Start-Process shot.png`
  (he wants the image OPENED, not a path). Save shots under `%TEMP%\zyvo\shots\`.
## 2026-11-16 - NEXT GOAL: UI "smoother + more natural + interesting"
- User's words: "ui ta aro smooth ar natural interesting korar ki ki ase" - polish pass on top of apk-88.
- Known jank source: item_tool.xml uses `android:animateLayoutChanges="true"` on the tool row (auto
  layout animation = stutter); replace with a measured 160-200ms height/alpha ValueAnimator + chevron rotation.
- No touch feedback today: tool cards (bg_tool_card) and drawer rows (bg_session_item) are clickable without
  a ripple -> add `?attr/selectableItemBackground` foreground ripples.
- Drawer row titles truncate to "S..." for some sessions (item_session title match_parent, maxLines 1) - investigate width/measure bug.
- Tool spinner is a text hack (TextView with "⠋<" monospace) -> should be a rotating arc drawable/frame animation.
- Home is a static watermark + composer; no suggestion chips / recent-task row yet.

## 2026-11-16 - CARD BODY = CAPPED + SCROLLS INSIDE (user rule)
- User rule: no card may fully expand. Long output scrolls INSIDE the card
  ("sotar modde scroll hobe"), applied to every card type.
- New `com.zyvo.code.MaxHeightScrollView` (extends androidx NestedScrollView,
  nested scrolling on so the RecyclerView still gets edge drags). Cap =
  fraction of screen height, clamped 110-330dp; `setMaxFraction()` per use.
- `item_tool.xml`: `tool_scroll` (MaxHeightScrollView, default 0.40) wraps ONLY
  `code_text`; the card header (lang + copy) stays pinned above it.
  `code_text` renders with maxLines=MAX_VALUE, ellipsize=null and a
  `capSpans(...,800)` guard in the "full" stage; preview stages end with "...".
  Scroll resets to the top on every tap (posted after notifyItemChanged).
- `item_code.xml` (reply code block): same wrapper `code_scroll` with
  `setMaxFraction(0.28)` in CodeVH, `capSpans(...,200)`; expand dialog kept.
- `animateLayoutChanges="true"` REMOVED from item_tool/item_thought/item_todo
  (that was the stutter). Body open now = 150ms alpha+4dp rise (`fadeBodyIn`),
  chevron rotates 150ms.
- Tool spinner = real rotating arc: `drawable/ic_spinner_arc.xml` + ImageView
  `tool_spinner` + ObjectAnimator(rotation 0-360, 900ms, infinite) inside
  `ToolVH.startTicker/stopTicker` (TickingVH's braille ticker now unused by it).
- Tap cycle unchanged: closed -> 10 lines -> full (scrollable) -> closed.

## 2026-11-16 - RUN-GROUP HEADER REMOVED (user rule)
- User: the "Ran N commands" header (folder icon) folded every command away,
  collapsed itself, and left a dead empty block in the chat -> remove it.
- `ChatFragment.handlePart` no longer inserts `ChatItem.summary` (the block at
  the old line 1304-1326 is gone); `batchCard` stays null forever, so
  `closeBatch()` / `countTool()` / `updateBatchHeader()` are inert no-ops and
  lines 1388/1443 never assign a group.
- `ChatAdapter.groupCollapsed()` now returns false unconditionally - a tool or
  thought row must never be hidden behind a header again.
- Do NOT reintroduce grouping headers without asking the user.

## 2026-11-16 - GROK 1.2.57 FULL RE (icons + motion inventory)
- XAPK pulled to `%TEMP%\zyvo\grok\` (base 57.8MB + arm64/en/mdpi splits).
  jadx 1.5.1 re-installed at `C:\Users\Admin\Tools\jadx` (the old copy was gone);
  decompile = 45,281 java files, fully R8-renamed (`a`, `a0`, `q5`, `l1` ...).
- ICONS EXTRACTED: `design/grok-study/icons/vector/` = 498 files (all `ic_*`,
  24dp, fill #000/#fff so `android:tint` works, 105 stroke-style) + both
  wordmarks + `ic_grok_cosmos`. Preview page: `%TEMP%\zyvo\grok\icons\grok-icons.html`,
  raw paths: `%TEMP%\zyvo\grok\icons\vectors.json`.
- ANIMATIONS: nothing liftable. `res/anim` (38) + `res/animator` (16) are ALL
  AndroidX library; 95 anim-bearing XMLs are library + OpenMoji `$avd_*`;
  no Lottie; `assets/i12g.mov` has no moov atom (unusable). Grok is 100% Compose,
  so its motion lives in obfuscated Kotlin - durations/easings not recoverable.
- Liftable sound assets: `assets/chimes/{start,end,start02,start04,end02,end04}.m4a`.
- Candidate motion additions list (9 items, all View+Animator, no deps):
  `design/grok-study/EXTRACT-NOTES.md`.
- REA state: ghidra + jadx-bridge healthy; node 24.19.0 is BELOW the 24.22.1
  floor (rea doctor `healthy:false`), hopper and kuna missing.

## 2026-11-16 - Grok icons + motion pass #2 (code only, NOT built)
- ICONS: 18 vectors copied `design/grok-study/icons/vector` -> `res/drawable/`
  as `ic_g_*.xml` (grok keyboards/terminal, lists, folder, read/write file,
  pencil_edit, globe, search, code_block, agent, sparkle, wrench, copy,
  refresh, expand, collapse, send). All 24dp, `fillColor #000000`, NO
  android:tint in the file -> runtime tint works.
- `ChatAdapter.iconFor()` now returns the `ic_g_*` set (terminal / lists /
  folder / read_file / write_file / pencil_edit / globe / search / code_block /
  agent / sparkle / wrench). Action icons swapped in XML: `btn_copy` ->
  `ic_g_copy`, `btn_regen` -> `ic_g_refresh`, tool `code_copy` -> `ic_g_copy`.
  Old `ic_lc_*` files left in place (unused, harmless).
- MOTION (all gated on `reduceMotion`):
  - streaming caret: `withCaret()` appends `▍` on a 480ms blink, used by
    `bindStreamText` + the revealing branch of `bindMessage`.
  - reply action row slides in once: `ChatItem.actionsAnimated` + 6dp rise /
    alpha 170ms with 60ms delay in `bindMessage`.
  - card body open = spring: `fadeBodyIn` now 6dp + OvershootInterpolator(0.7),
    210ms.
  - send button: haptic on ACTION_DOWN, 0.88 -> 1.06 -> 1.0 two-stage spring
    (`ChatFragment` send touch listener).
  - drawer rows (`SessionAdapter.VH` constructor): 0.97 dip + 160ms return.
- SKIPPED: "thinking dots" next to the thought title - the shimmer runs a
  LinearGradient shader on the same TextView, so ForegroundColorSpan dots
  would be invisible. Needs a separate dot view first (layout change).
- Verification: `:app:compileDebugJavaWithJavac` exit 0 + `javachk.py` JAVA OK.
  Full APK build still left to the user (Run / Build APK in Studio).

## 2026-11-16 - PACKAGING FIX + "no build" rule (user repeated)
- User again: "build koiro na, phone e run koro, build to sob sesh e korbo" ->
  NEVER run `assembleDebug` / `packageDebug` / any `gradlew` build task on our own,
  not even to verify. He tests with Studio Run on the phone and builds at the end.
  (A javac-only compile check for a specific doubt is the sole exception.)
- Studio error `Execution failed for task ':app:packageDebug' > Could not find EOCD
  in ...\intermediates\apk\debug\termux-app_..._arm64-v8a.apk` = the previously
  written APK zip is TRUNCATED (write was cut: restart / AV / disk). It is a stale
  artifact, NOT a code problem.
  FIX (worked): delete `app\build\intermediates\apk` entirely, then Run again -
  Gradle repackages from scratch. Verify a suspect APK first with
  `[System.IO.Compression.ZipFile]::OpenRead(path)` - "End of Central Directory
  record could not be found" confirms truncation. `app\build\outputs\apk\debug`
  may still hold the last GOOD APKs (they are valid, just stale).
- Phone at that moment: adb shows `adb-Z60plusBD2310070295-7kxTqf._adb-tls-connect._tcp`
  device (mDNS serial) - Studio Run works; the old 192.168.1.117:34197 port is dead.

## 2026-11-16 - Tool card = Grok run box / file box (redesign)
- Grok evidence: 100% Compose, so no layout/color files exist for its tool cards.
  What IS in resources: `grok_bots_activity_running_a_few_commands`,
  `..._writing_some_code`, `..._reading_the_file`, `grok_bots_approval_ran_one_command`
  ("Ran 1 command"), `grok_bots_approval_command_header` ("Command"),
  `..._show_more` / `..._show_less`, `grok_bots_approval_source_terminal` ("Terminal"),
  plus `ic_code_terminal` / `ic_code_block` / `ic_vector_terminal`.
  => Grok's pattern: short verb header, and the COMMAND lives inside the box
  (a terminal-like block), never repeated in the header.
- `item_tool.xml` rewritten around that: card header unchanged (icon + verb +
  +N -N + target + chevron) but body is now a real run box:
  header row (lang/file label, mono 11sp, dim + copy 34dp) -> `cmd_line`
  ("$ command", mono 12sp, dim `$` span) -> 1dp `cmd_sep` hairline -> scroll body.
  `body_fade` (drawable `bg_body_fade.xml`, transparent -> #121315) sits at the
  bottom of the scroll frame and shows only while `canScrollVertically(1)`.
- `ChatAdapter.bindTool` now: computes isTerminal BEFORE the body chain,
  `code = ""` when a shell tool has no output yet (the "$ command" line carries
  the card), `showCmd = isTerminal && !clean.equals(payload)`.
  When a shell box is OPEN the header title is hidden (`h.name` GONE) so the
  command is not printed twice; collapsed it stays.
  Box label = `langFor()` (TSX/Bash/JSON); for write/edit it falls back to
  `shortPath()` (last 2 segments) only when langFor yields write/edit/patch.
  New helpers: `cmdSpans()` (SpanString, `$ ` dim) and `shortPath()`.
- Fade listener is guarded `Build.VERSION.SDK_INT >= 23` (minSdk is 21) plus a
  post-bind refresh call.
- Verified: XML ET parse OK + `:app:compileDebugJavaWithJavac` exit 0.

## 2026-11-16 - Text style pass (Grok typography) + reveal cap
- How text actually shows: engine publishes `message.part.delta`; `applyDelta`
  appends to the part; `revealTick` (16ms, `step = max(2, ceil(backlog/30))`,
  now CAPPED at 120 chars/tick so a burst cannot dump at once) moves
  `ChatItem.shown`, and only the TextView is rebound (`PAYLOAD_STREAM`).
  While streaming the text is `plainMarkdown()` (markers stripped); the moment
  the stream ends ONE full bind renders real Markwon markdown - hence the small
  reflow at the end. Grok instead parses markdown live in Compose, so it has no
  end swap; a live-markdown variant here would need a throttled (>=80ms)
  Markwon parse per tick = jank risk on long replies (offered, not done).
- MarkwonTheme (ChatAdapter ctor) now Grok-ish: heading multipliers
  {1.26, 1.16, 1.08, 1.02, 1, 1} (was all 1f), `headingBreakHeight(dp(12))`,
  `blockQuoteColor(line)` + `blockQuoteWidth(dp(2))`, `bulletWidth(dp(4))`,
  `thematicBreakColor(line)`, link = @color/ok, code = mono on @color/code_bg.
- Markwon 4.x in this project has NO `bulletRadius(int)` on MarkwonTheme.Builder
  (compile error) - do not re-add.

## 2026-11-16 - Editor box (12 lines, gutter, no wrap) + collapsed rows + Working stays
- User rules (2026-11-16): tool rows (terminal / write / edit) start COLLAPSED;
  the code box is a fixed ~12-line editor box with a pinned line-number gutter
  ("1 2 3"), the body scrolls DOWN inside it and long lines NEVER wrap - they run
  right and the box scrolls SIDEWAYS ("1st line er lekha 2nd line e jabe na,
  dorkar moto samne jabe"). Reference: `Downloads\Screenshot_20.png`.
- `item_tool.xml` body is now:
  MaxHeightScrollView(`tool_scroll`) > LinearLayout(horizontal) >
  [`code_gutter` TextView (mono 11sp, gravity end, ok-green numbers, minWidth 30dp)]
  [`gutter_sep` 1dp line] [`code_hscroll` HorizontalScrollView weight 1 >
  `code_text` (mono, `scrollHorizontally="true"`, wrap_content, no maxLines)].
  `body_fade` still overlays the bottom edge.
- `MaxHeightScrollView.setMaxPx(px)` added (sets fraction=1f so the pixel cap
  wins). `bindTool` posts `12 * codeText.getLineHeight() + dp(12)` after setText.
- Write bodies: `syntaxBlock(content)` + pinned gutter (`gutterText(n)`), the old
  inline `numberedBody()` is no longer used for write. Diff bodies keep their own
  inline numbers + washes.
- Open rule: `open = !item.collapsed && (item.expanded || item.full)` - nothing
  auto-opens any more (was: terminal/write/edit/failed auto-open).
- `removeWorking()` calls at BOTH reasoning-start sites in ChatFragment removed:
  the in-chat "Working" row used to vanish the moment thinking began, i.e. before
  the turn ended. It now lives until `finishTurn()`.
- Verified: XML parse OK + `:app:compileDebugJavaWithJavac` exit 0 (no APK built).

## 2026-11-16 - Editor box round 2 (review fixes)
- BUGS found by review and fixed:
  1. `code_text` had `textIsSelectable="true"` -> TextView's Editor ate every
     drag, so the box could NOT be scrolled by finger. Now false (copy button
     is the copy path). Same for `cmd_line`.
  2. `nextStage()` still cycled closed->10 lines->full->closed, but both middle
     stages render identically now (12-line box) -> second tap looked dead.
     Now a clean 2-state toggle (closed <-> open).
  3. TERMINAL box was 12 code lines PLUS the "$ command" line -> taller than a
     write box. `bindTool` post now subtracts the measured cmd line height from
     the cap so every box has the same total height.
  4. Dead code removed: `numberedBody()` (write uses syntaxBlock+gutter now),
     unused `isExpandable` / `maxLines` locals.
  5. Running terminal preview was `tail(...,8)` from the 40-line era -> now
     `tail(...,40)` so the 12-line box is actually full.

## 2026-11-16 - BUG HUNT from a phone screenshot (5 real bugs)
- Phone evidence: `adb exec-out screencap -p` MUST be redirected with
  `cmd /c "... > file"`; PowerShell `>` corrupts the PNG (System.Drawing then
  says "Out of memory"). Base64/test: first 8 bytes `89504e470d0a1a0a`.
- **CODE CARD WAS ALWAYS EMPTY** (`</>` + copy + expand, no body, no lang, both
  buttons dead): `onBindViewHolder(holder, position)` had NO `CodeVH` branch, so
  `bindCode()` never ran (the inflated XML defaults showed instead). FIX: added
  `else if (holder instanceof CodeVH) bindCode(...)`. Reply code blocks
  (`splitAssistantCode`) render again.
- **+N −N pill was dead**: no click listener existed in `bindTool`, and
  `showDiff` was never read. FIX: pill toggles `item.showDiff` + opens the row;
  write rows render `diffSpans(item.diff)` when it is on.
- **Duplicate action footers**: `splitCodeSegments` copies timeMs/durationMs to
  every text piece -> every segment showed copy/regen + `7:27 PM · 5.7s`.
  FIX: `ChatItem.noFooter` on all but the LAST text piece (bindMessage hides
  actions + time for those).
- **Stale stall banner**: "Waiting for the model - no reply yet (42s)" stayed up
  after the reply arrived (only `finishTurn` hid it). FIX: `clearStallBanner()`
  called from `handlePart` + `handlePartDelta` (hides only when `stallWarned`).
- **Send button could end up rotated** ("send button ta ulta"): `updateSendIcon`
  used `rotationBy(±180)` per swap, so a cancelled animation left the arrow at a
  random angle. FIX: `animate().cancel()` + absolute `rotation(0f)` every swap.
- Screenshot reading workflow that worked: pull PNG -> Pillow crop+upscale
  (`Image.LANCZOS`) of the exact region -> Read the crop. Full-screen PNG at
  720x1600 fails the Read tool's size limit.

## 2026-11-16 - "box scroll kaj kore na" = tap bubbling to the card toggle
- BUG: the tool card's click listener lives on `tool_card` (an ANCESTOR of the
  whole body). A tap anywhere inside the opened box bubbled up to it and
  `nextStage()` CLOSED the box, so "tap + drag inside to scroll" looked dead.
- FIX 1: `ToolVH` ctor sets a no-op click listener + `setClickable(true)` on
  `tool_body`, so every unhandled tap inside the box is swallowed. The HEADER is
  still the toggle (that is where the chevron is).
- FIX 2: `android:nestedScrollingEnabled="false"` on `tool_scroll` and
  `code_scroll` -> classic ScrollView gesture handling: it calls
  `requestDisallowInterceptTouchEvent(true)` on the RecyclerView, so a vertical
  drag inside the box scrolls the BOX instead of the chat list.
- Symptom to remember: "12 line er beshi, click kore scroll korle kaj kore na"
  is a bubbling/gesture-ownership bug, never a height bug.








## 2026-11-18 - LAUNCHER ICON = the real Z-orbit artwork (user decision)
- User rejected the hand-drawn SVG recreation ("oi er theke valo hoto ... eta app
  er icon e bosiye dita") and pointed at `Downloads\Gemini_Generated_Image_b2t9nbb2t9nbb2t9.jpg`
  as the LOOK target: a near-black rounded tile with the white italic Z + orbit
  swoosh, legible at small size.
- Source artwork = `Downloads\Screenshot_1.png` (310x246 RGBA, opaque #0A0A0A bg,
  mark bbox 55,65-260,206 = 206x142). Alpha keyed with luminance:
  `alpha = clip((lum-14)/(235-14),0,1)` -> clean transparent white mark.
- Assets regenerated (`design\zyvo-brand\make-icons.py` reproduces everything):
  legacy `mipmap-*/ic_launcher(.round).png` 48-192 = #0B0B0F rounded tile
  (r 0.22n) + mark at 0.70n; `drawable/ic_foreground.png` 432 = mark at 0.47n
  centred (must stay inside the 66% safe circle - 0.545 clipped the ring tip);
  `drawable-*/ic_zyvo_mark.png` 24dp mark for headers; masters in
  `design\zyvo-brand\` (mark-master.png, zyvo-icon-1024.png, transparent 1024).
- `res/drawable/ic_bg_card.xml` was a 6dp-inset rounded layer-list (would leave
  transparent corners under an adaptive mask) -> now a plain full-bleed
  `#0B0B0F` rectangle. Keep it XML (a PNG of that name = duplicate resource).
- App label is STILL "Termux" (&TERMUX_APP_NAME; in values/strings.xml). The
  mockup shows "ZYVO" underneath - offered as a one-line change, NOT applied.
## 2026-11-18 - chat shell redesign (user spec from mockup) + code card cleanup
- User gave a locked visual spec (source: Downloads\Gemini_Generated_Image_2paiit2paiit2pai.jpg):
  canvas #0B0B0F, headings white, body #E4E4EB, cards/user bubbles #1E1E24.
- `fragment_chat.xml` rebuilt:
  - top bar 58dp = 34dp logo chip (bg_logo_chip, ic_zyvo_mark tint white) + "ZYVO"
    wordmark 19sp/letterSpacing .06 + "AI Agent" pill (bg_agent_pill, #8E8EA0 12sp) +
    hamburger btn_sessions (40dp borderless). 1dp `header_line` #1A1A22 under it.
    **btn_new was REMOVED** (new chat = drawer); do not re-add R.id.btn_new.
  - centered watermark `@drawable/zyvo_watermark` (drawable-nodpi, 1200x1160, tint
    @color/watermark_tint #17171D, 44dp side margins, adjustViewBounds) as the FIRST
    child of the list FrameLayout -> under the transcript. `empty` lost its
    `new_chat_bg` image (that PNG had #0F0F10 baked in and would show as a box).
  - dock = `suggest_row` (HorizontalScrollView, fillViewport, centered) with
    chip_s1 "Brainstorm ideas" / chip_s2 "Help me code" (bg_suggest_chip pills) +
    `composer` horizontal pill (bg_composer #16161E r26 1dp #262630): btn_plus 34dp,
    input (hint "Message ZYVO..."), btn_voice 34dp, btn_send 38dp white ic_arrow_up.
  - `agent_chip` + `composer_model` moved to a muted centered meta line UNDER the pill
    (11sp, text_disabled) - keeps the mockup's clean dock.
- Java: `suggestRow` field + `updateSuggest()` (visible only while the input is empty),
  chip_s1/s2 reuse the existing `chipClick`, `newBtn` lookup deleted.
- Palette (both values/ and values-night/): bg #0F0F10 -> **#0B0B0F**, header likewise,
  user_bubble #1C1D20 -> **#1E1E24**; new tokens header_line, agent_muted, input_bg,
  input_line, chip_bg, chip_text, body_text, watermark_tint, logo_chip.
  New strings: agent_label "AI Agent"; composer_hint now "Message ZYVO...".
- Reply CODE CARD (item_code.xml) after the phone screenshot showed `</>` + "text" +
  copy + expand clutter and cramped mono:
  - header = uppercase letterspaced language label (10sp) + ONE copy icon (32dp);
    `code_tag` and `code_expand` ids are GONE (R.id.code_tag / R.id.code_expand no
    longer exist - do not reference them).
  - long-press on the body opens the full `showCodeDialog` (expand action kept,
    just not as a button). Card scroll cap 0.36.
  - body mono 13sp, lineSpacingExtra 4dp, padding 13dp all round (last line no
    longer touches the border).
- Typography: assistant text 16sp -> 15sp + lineSpacingExtra 7dp + @color/body_text;
  user bubble 15sp + 4dp spacing + 11dp padding. item_code root padding 18dp/6dp so a
  card and the prose that follows it no longer touch.
- Verified with `:app:compileDebugJavaWithJavac` (clean). APK still built by the USER
  in Studio (Run / Build APK) - never by us.
## Workflow (user's rule, 2026-11-18)
- zyvo = code in this project. USER = runs/builds in Android Studio (Shift+F10) and tests on the phone.
  Never treat a CI push as the verification loop unless a release APK was explicitly requested.
- Allowed local check: `:app:compileDebugJavaWithJavac` only (never assemble/package).
## 2026-11-18 - REDESIGN REVERTED (user: "pura ui emon kore felso re, undo kor")
- The mockup-based chat shell (logo chip header + "AI Agent" pill + suggest chips + #0B0B0F
  palette + decluttered code card + drawer restyle) was REJECTED. Reverted to the previously
  committed UI (HEAD 531912e) for: ChatAdapter, ChatFragment, ChatItem, SessionAdapter and the
  layouts fragment_chat / activity_chat / item_code / item_message_assistant / item_message_user /
  item_session / item_thought / item_todo / item_tool + bg_code_card / bg_composer / bg_tool_card /
  bg_user_bubble + drawable/new_chat_bg.png (restored).
- KEPT (approved earlier, not part of the reject): launcher icon art (ic_foreground.png, mipmaps,
  ic_bg_card.xml), AndroidManifest (ChatActivity as launcher), dark colour tokens, tool-manifest text.
- The ONE wanted change: the ZYVO banner stays in the background. 
ew_chat_bg still backs the
  empty state, and ragment_chat.xml now has an ImageView (src @drawable/zyvo_watermark, tint
  #17171D, alpha .55, 44dp side margins, centerInside) as the FIRST child of the list FrameLayout
  so the mark also sits behind the transcript.
- Untracked leftovers from the rejected redesign (bg_logo_chip, bg_agent_pill, bg_suggest_chip,
  ic_g_*, MaxHeightScrollView.java, bg_body_fade/bg_fade_bottom) are NOT referenced by any live
  layout/Java after the revert - harmless, delete on request.
- Verified: XML parse OK + :app:compileDebugJavaWithJavac exit 0. APK is the user's to build.
- Banner tuning (2026-11-18, user): "logo ta halka uproe ar niche lomba" -> ragment_chat.xml watermark now has
  ndroid:scaleY="1.3" (taller, pivots on the view centre) + ndroid:translationY="-56dp" (sits higher).
  Kept: alpha 0.55, tint #17171D, 44dp side margins, centerInside. XML parse OK.
## 2026-11-21 - 16 KB page-size compatibility (Android 15 / Play checklist)
- APK Analyzer warning pointed at `libconscrypt_jni.so` LOAD segments being 4 KB
  aligned. Source = `org.conscrypt:conscrypt-android:2.5.2` (prebuilt AAR; 2.5.2 is
  the last release, there is no newer artifact). Needed for adb wireless pairing
  (`Conscrypt.exportKeyingMaterial`), so it cannot be dropped.
- Fix tool: `tools/patch-16k-elf.js <so> [--check]` - inserts zero padding so every
  PT_LOAD satisfies `p_vaddr === p_offset (mod 16384)` and sets `p_align = 16384`.
  Section bytes stay byte-identical (sha256 per section verified on all 4 ABIs); only
  file offsets / header table positions move. Handles ELF32 + ELF64 LE.
- Gradle wiring (`app/build.gradle`, second `afterEvaluate`): per-variant task
  `realignConscrypt16k<Variant>` patches
  `intermediates/stripped_native_libs/**/libconscrypt_jni.so` right after
  `strip<Variant>DebugSymbols`; `package<Variant>` depends on it. Requires `node`
  on PATH (CI has it, local Studio has it). Verified with `gradlew -q :app:tasks --all`
  -> both tasks registered; `gradlew -q help` configures clean.
- The other packaged libs are already 16 KB aligned (NDK 29): libzyvo.so,
  libopentui.so, libc++_shared.so, libtagfix.so.
- `targetSdkVersion=28` -> the November 2025 Play rule (targets Android 15+) does NOT
  apply today, and the test phone (Android 12) uses 4 KB pages, so old APKs ran fine.
  This fix is future-proofing, not a current crash fix.
- `useLegacyPackaging true` -> libs are compressed and extracted at install, so zip
  `-P 16` alignment is irrelevant; only the ELF PT_LOAD geometry matters.
- PITFALL (2026-11-21, first build attempt): inside `app/build.gradle` the relative
  `projectDir` is <repo>/app, so `new ProcessBuilder("node", "tools/patch-16k-elf.js", ...)`
  failed with `Cannot find module ...\app\tools\patch-16k-elf.js` and
  `realignConscrypt16kDebug` broke the build. Fix: `rootProject.projectDir` +
  `new File(repoRoot, "tools/patch-16k-elf.js")`. Re-verified with `gradlew -q help`
  (config green) and the patcher run directly on the real stripped intermediates
  (arm64/x86_64/armeabi-v7a/x86 all now 16 KB aligned).
## 2026-11-21 - FULL AUDIT of the 27 local commits (nothing pushed)
- State: master = 403cae1, origin/master = 1fa221b -> **27 commits ahead, all LOCAL**.
  Working tree clean except 2 untracked: `app/src/main/assets/skills.tar.gz` (2.0 MB) and
  `gradle/gradle-daemon-jvm.properties`.
- Verified green this session: `:app:compileDebugJavaWithJavac` BUILD SUCCESSFUL (online mode;
  `--offline` fails to resolve AGP 8.13.2 in this environment). All res/*.xml parse OK and every
  @drawable/@mipmap reference resolves (55-drawable cleanup is safe).
- Engine `libzyvo.so` (173,184,047 B, 2026-11-21 15:53) contains ALL prompt blocks (Execution policy,
  Modding core, EARNING, PC CARE, Phone/Android incl. TELEGRAM CHAT BOTS) -> local .so is current.
- Local APKs in app/build/outputs/apk/debug are STALE (2026-11-16 12:38) - no build after the prompt work.
- Repo hygiene problem: HEAD commit 403cae1 ADDED `app/src/main/jniLibs/arm64-v8a/libzyvo.so.orig-bak`
  (173 MB blob, ~59 MB loose) because .gitignore only covers `*.so` / `*.zip`. Before any push:
  `git rm --cached` that path + add `*.orig-bak` / `*.tar.gz` to .gitignore, then strip the blob
  from the local history (filter-repo/filter-branch) so 59 MB does not go to GitHub.
- Big local diffs vs origin: design/ 497 files (Grok icon study + zyvo-brand), app/ 111 files,
  tools/ (patch-16k-elf.js + system-prompt.md), AGENTS.md.
## 2026-11-21 - USER RULE: NEVER PUSH (repeated) — SUPERSEDED 2026-11-28
- "kuno kisu push na, sob local, Android Studio te hobe" -> do NOT push/commit-to-remote
  on this repo unless he explicitly says so. Everything stays on this PC; he builds/tests
  in Android Studio. (Local commits are fine only when he asks.)
- CANCELLED 2026-11-28 by the user - see the 2026-11-28 section below.
## 2026-11-21 - PERF PASS (chat + drawer, code only, compile green)
- ChatAdapter: parsed markdown is now CACHED per row (ChatItem.mdCache/mdSrc,
  mdBodyCache/mdBodySrc for finished thinking rows, codeCache/codeSrc for the code
  card). Scroll no longer re-parses markdown or re-highlights code. Uses
  `markwon.toMarkdown` + `setParsedMarkdown` (verified in Markwon 4.6.2 via javap).
  density/colors/interpolator are cached fields - NOTE: density must be assigned
  BEFORE `Markwon.builder(...).build()` because MarkwonTheme calls `dp()`.
- SessionAdapter: the old `visible()` was called inside onBindViewHolder AND
  getItemCount, i.e. the whole session list was filtered + sorted once per row per
  pass. Now `rebuild()` runs only on setItems/setFilter; stable ids enabled;
  setCurrent/setBusySession notify only the two affected rows (no full rebind, so the
  busy spinner no longer restarts).
- ChatFragment: revealTick now walks a `revealPending` LinkedHashSet instead of the
  whole transcript 60x/s (queue fed by applyDelta + the part-sync path, cleared on
  session switch / flushReveal). Reveal pacing itself is UNCHANGED (16 ms, /30, cap 120).
- Both RecyclerViews: setHasFixedSize(true) + item view cache (chat 20, drawer 16).
- Verified: `:app:compileDebugJavaWithJavac` BUILD SUCCESSFUL. No APK built (user rule).

## 2026-11-28 - WORKFLOW CHANGED (user): zyvo verifies + commits + pushes, GitHub CI builds
- User cancelled BOTH old rules ("zyvo never runs gradlew" + "never push"): "sob check
  kroe verify kore github e commit build koro". Android Studio / Java / Android SDK are
  NO LONGER installed on this PC (Program Files\Android and AppData\Local\Android gone,
  no java on PATH, no JDK in the registry) -> GitHub Actions "Zyvo Termux Build" is the
  ONLY compiler now.
- Pre-push gate that still works without Java: all res XML parse, brace/paren balance of
  changed Java, secret scan of everything that would be published, CI asset URLs alive.
- Standing user instruction: the flagged findings STAY AS-IS, do not fix ("ami e disi"):
  garbled double-encoded chars in zyvo_strings.xml (Â· / â†’) + AGENTS.md, the bundled adb
  key (assets/shizuku/adbkey, in origin history since the first push), stock Termux
  workflows (Unit tests + Dependency submission) failing on every push.
- Push mechanics 2026-11-28: tip commit amended (403cae1 -> 8454fc5) to drop the 173 MB
  `libzyvo.so.orig-bak` blob (GitHub rejects >100 MB files); file kept on disk;
  `*.orig-bak` added to .gitignore. 26 commits pushed as one fast-forward to master.

## 2026-11-28 - REPO CONSOLIDATION (user decision)
- Overlapping repos existed: zyvo-app (11-11 identity fork com.zyvo.app, never built),
  zyvo-code (11-12 small experiment, superseded), zyvo-termux (active, apk-96 + secrets).
- User chose SAME-APP updates (com.termux identity stays, no new package) with the work
  living in **zyvo9/zyvo-app** ("zyvo app" repo). zyvo-code DELETED (user-approved).
  zyvo-termux KEPT: it holds apk-90..96 release history and the update-checker URL of
  already-installed apps.
- Local remotes: origin = zyvo-termux, `app` = zyvo-app. **Future pushes go to `app`**
  (zyvo-app, default branch master after consolidation); zyvo-termux only when the user
  explicitly asks for a mirror.
- ChatActivity.checkUpdate() now reads zyvo9/zyvo-app releases for future builds.
- zyvo-app needs the ZYVO_OPENCODE_KEY secret re-added by the user (GitHub secrets
  cannot be read/copied via API) - until then its APKs build WITHOUT the lane key.
## 2026-11-28 - MuMu/emulator x86 support (VERIFIED working)
- MuMu 12 = Android 15, kernel x86_64 with ARM translation (abilist: x86_64,arm64-v8a).
- x86_64 engine (Bun) needs AVX2: this PC is i7-2600 (no AVX2) and MuMu's vCPU does not
  expose it -> instant SIGILL at spawn. x86_64 engine is UNUSABLE on pre-Haswell hosts.
- FIX: arm64 engine runs under MuMu translation once the tagfix preload is skipped.
  EngineManager now preloads libtagfix.so only when SUPPORTED_ABIS[0] == arm64-v8a
  (real phones unchanged). On MuMu: engine goes READY in ~33s (translated), port 4096
  LISTEN confirmed, chat connected.
- Rule: on emulators install the ARM64-V8A APK, never the x86_64 one.
- Real-x86-phones need an AVX2-free baseline engine build (upstream ZYVO-AI) - not done.
- adb to MuMu: connect 127.0.0.1:16384 (also 7555/5555); restarting MuMu resets the VM -
  reinstall + relaunch, the app may vanish from a fresh instance.
