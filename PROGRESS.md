# Progress tracker

**Working rule (set by the project owner):** take the most complete unfinished area to 100%, then pick the next most complete one and take it to 100%. This holds even when new requests arrive; new requests join the table below and wait their turn.

"100%" means: code written, wired into the UI, unit tests written, and documented. It does **not** mean compiled or device-tested. Nothing in this repository has been compiled or run yet; that verification is a separate pass.

| # | Area | Status |
|---|---|---|
| 1 | Original browser, Security & Privacy spec, Hydrogen logos | 100% |
| 2 | **Agent platform:** model gateway, injection defence, permission rules, tool list | **100%** (Android). Apple port is tracked in row 12. |
| 3 | External agent control (Claude, ChatGPT, Muse… via MCP) | **100%** (Android). Apple port is tracked in row 12. |
| 4 | Wi-Fi speed in toolbar | **100%** (Android + Apple) |
| 5 | Backgrounds and colour themes | **100%** (Android + Apple) |
| 6 | PDF viewer and editor | **100%** (Android + Apple) |
| 7 | Apple Pay / Google Pay | not started |
| 8 | Handoff (Apple) | not started |
| 9 | Continuity between Android devices | not started |
| 10 | Agent runtime, multi-agent teams, deep research, memory, adaptive UI, Agent Centre/Studio, integrations | **100%** (Android). Apple port is row 12. |
| 11 | Specialist-agent details (scheduling, marketplace, OAuth connectors) | not started; some will stay "needs credentials" |
| 12 | Apple versions of the agent features | not started |
| 13 | Final single-file bundle and per-platform code-size report | not regenerated after row 14 |
| 14 | **Rendering-engine switch (Chromium / Gecko) and safety hardening** | **written, Android only; not compiled** |

## Row 2 — what exists
- **Model gateway** (`core/ai/Providers.kt`): Gemini (function calling with thought-signature echo), OpenAI-compatible (OpenAI, OpenRouter, Ollama, LM Studio, vLLM) and Anthropic adapters behind one interface; default + ordered fallback; one retry on transient errors; real token usage from provider responses (`Usage.kt`); model listing; key per provider in the Android Keystore.
- **Settings → AI**: add/enable/delete providers, base URL, model, list models, test connection, default and fallback chips, usage totals, reset.
- **Injection defence** (`core/agent/Safety.kt`): scanner for common injection phrases, fencing of web text as untrusted data, card-number/secret redaction.
- **Permission rules**: `PolicyEngine` enforced in code before any tool call (tool allow-list, scopes, read-only, domain allow/block, password/card fields denied, purchases blocked or approval-gated, send/delete/publish always approval-gated, form submit gated); SSRF guard for server-side fetches.
- **Tool list**: 17 typed browser tools with scopes, MCP and provider-neutral exports.
- **Tests** (written, not run): policy matrix, injection, redaction, SSRF, catalogue schemas, gateway fallback/retry/usage.

Known limits of row 2: the runtime that *calls* these (agent loop, approvals UI) is row 10; Gemini REST function-calling shape was checked against Google's reference, but provider adapters have never talked to a real server.

## Row 3 — what exists
- MCP server over HTTP (JSON-RPC 2.0): `initialize` with version negotiation (2024-11-05 … 2026-07-28), per-request style also accepted, `tools/list` filtered by granted scopes, `tools/call`, `ping`; text and image results.
- Auth: 256-bit pairing tokens shown once (hash only stored), scopes (read / interact / navigate / tabs), expiry, revoke, rate limit, loopback-only by default, optional Wi-Fi access, browser-originated (Origin) requests refused.
- 17 tools executed against real tabs: agents work in their own **Agent Space** (background tabs, never stealing focus) unless the user allows all tabs; private tabs are never reachable.
- Safety: policy checked in code for every call; element risk flags read from the page before clicking; password/card fields unreadable and unwritable; purchases blocked by default; sending/deleting/payments always need approval; form submits need approval unless Auto; security warnings can't be bypassed; fenced + scanned page text; saved-login fill needs the user's biometric and never reveals the password; approvals auto-decline after 90 s.
- Visibility and control: banner with Stop while an agent acts, Stop-all / Resume, auto-pause timer, full activity log, per-agent revoke.
- stdio bridge `agent/hydrogen_mcp_bridge.py` for stdio-only clients; Settings → Agents UI.
- Tests written (not run): MCP dispatch, scope refusal, notifications, errors, token hashing/expiry, HTTP parsing, helper formatting/risk.
Known limits: the server runs only while Hydrogen is running; reaching a phone from another machine needs Wi-Fi access or your own tunnel (no cloud relay); Streamable-HTTP SSE streaming and OAuth for remote connectors are not implemented (static bearer tokens only); behaviour with real clients is untested.

## Row 4 — what exists
Settings option (off by default) shows a speed chip in the toolbar; tap it to re-test. Android: "Wi-Fi link rate" (radio's negotiated maximum, no network use) or "Measured speed" (about 2 MB HTTPS download from a test URL you can change; default Cloudflare's public endpoint). Apple: measured speed only (no Wi-Fi link-rate API for apps). Wi-Fi-only switch, interval, honest labels, failure messages. Unit tests for the math/format and HTTPS-only rule (written, not run).

## Row 5 — what exists
Settings → Appearance on both platforms: light/dark/system, solid colours, 8 gradients, your own picture (copied/permission-persisted, darkness slider), tint by the active Space, translucent sidebar, accent colour override (Android also keeps Material You wallpaper colours as an option), reset. The backdrop shows behind the sidebar, toolbar and start page; web pages keep their own background. Tests for presets, contrast helper and settings round-trip (written, not run).

## Row 6 — what exists
PDFs that a page would download (or a file you pick with "Open PDF…") open in Hydrogen's own viewer, using the signed-in tab's cookies; setting to turn it off. **Android:** platform PdfRenderer for viewing, zoom, search (text layer), forms (fill AcroForm fields), pen / highlighter / signature / text, undo, rotate and delete pages, print, "Save copy" via PDFBox (edits are baked into the copy; original untouched). **Apple (PDFKit):** continuous view, text-aware highlight / underline / strike, text, pen and signature (iOS/iPadOS/visionOS; Apple Pencil works), native form filling, search, rotate / reorder / delete / append pages, print, "Save a copy". Honest limits: Android's highlighter is a freehand marker (no text selection) and forms are filled from a list, not by tapping the field; saved Android edits are flattened, not re-editable; macOS has no pen tool; scanned PDFs have no searchable text; no OCR; no digital (certificate) signatures. Unit tests for coordinate/page-order maths (written, not run).

## Row 10 — what exists (Android)
Agent runtime (plan, typed tools, policy + approvals enforced in code, pause/resume/cancel, step log, verification, error recovery, background execution with a foreground service); multi-agent teams (supervisor, dependency-aware parallel workers, workers can't exceed the supervisor's permissions, independent cross-check, consolidated report with disagreements and gaps); deep research (search, fetch, numbered citations, SSRF-safe, report to Markdown/PDF); 12 specialist agents with honest limits; Agent Studio (custom agents, permissions, domains, tools, test run); user-controlled memory (add/edit/delete, retention, secrets refused, project/session/long-term); integrations (MCP servers with per-tool approval, webhooks); adaptive UI controller with the eight states (human browsing by default, compact chip, panels only when relevant, never over typing or touch, hidden jobs keep running); audit log; emergency stop. Tests written (not run): runtime, policy, cancellation, pause, adaptive UI, orchestration parsing/limits, memory, research parsing, report, templates.
Not built: scheduled tasks, agent marketplace, Google/Microsoft/GitHub/Notion/Slack sign-in connectors (need OAuth client IDs), system notifications, sandboxed code execution, spreadsheet/slide/image generation, page-change monitoring, semantic history search.

## Row 14 — what exists (Android only; Apple not touched)
- **Settings → About → Rendering engine:** chips for Chromium, Gecko and WebKit. WebKit is shown disabled because it exists only on Apple platforms; Windows/Chromebook use the same Android build. The choice is stored in SharedPreferences (`EngineChoice`) and applied after a restart (`EngineFactory`); if Gecko fails to start, Hydrogen falls back to Chromium and says why.
- **`:engine-gecko` module** (GeckoView from maven.mozilla.org, version `+` until pinned after the first sync): navigation, progress, titles, downloads, permissions, file chooser, find in page, snapshot, popup handling, tracking-parameter stripping, Gecko content blocking mapped from the shield switches, real block events as shield counters. Optional: `hydrogen.gecko=false` in gradle.properties builds Chromium only.
- **Gecko-mode limits (honest):** no page-script evaluation, so no password fill, extensions or agent page tools; no print; no cookie read (authenticated downloads); Gecko's own certificate and Safe Browsing pages are used; Gecko updates only with the app. macOS engine switching is not built.
- **Hardening:** SSRF guard now covers IPv6 unique-local, CGNAT, 0/8, benchmarking and reserved ranges, local names and embedded credentials; relative redirects resolved and capped; agent click risk now takes the host from the tab (never from the page) and escalates payment/destructive risk from the page address (`UrlRisk`); the vault's origin index stores salted hashes instead of site names (an older plaintext index is rebuilt on the next save).
- Tests written (not run): extra SSRF ranges, `UrlRisk`.
Known remaining: DNS rebinding between the check and the fetch is reduced, not eliminated; the injection scanner is phrase-based and only flags.

## Android launcher icon — Material 3 Expressive pass
- Adaptive icon (108dp): solid background layer + foreground + **monochrome layer for Android 13+ themed icons**, light and night variants; whole mark fits inside the 66dp safe-zone circle (checked in `branding/android_preview.png` under circle, squircle and rounded-square masks).
- Expressive styling: bolder bars and line, fully rounded ends, larger nodes (generated by `branding/make_android_vectors.py`).
- Android 12+ splash screen icon (`ic_splash_icon`, light/dark, `values-v31/themes.xml`), 512px Play Store icon (`branding/playstore_icon_512.png`).
- Known limit: in the themed (single-colour) icon the line merges with the bars; colour versions are unaffected. Not yet seen on a real launcher.

## Row 15 — Hide Distracting Items, horizontal tabs, hover preview and memory, passkeys (Android; Apple not done)
- **Hide Distracting Items:** ⋮ → Hide Distracting Items. Tap an element (the page does not react), **Larger** picks its parent, **Hide**, **Undo**, **Done**. Remembered per site and re-applied on later visits; ⋮ → Show hidden items, or Settings → Appearance, brings them back. Private tabs hide for that visit only and save nothing. Needs page scripts, so it does not work in Gecko mode. The picker script was tested in a simulated page (jsdom), not on a phone.
- **Horizontal tabs:** Settings → Appearance → Tab layout. A tab strip above the toolbar (scrolls, pinned tabs first, long-press menu, + button); switching to it hides the sidebar, which the menu button still opens for Spaces.
- **Preview on hover:** a mouse, trackpad or stylus resting on a tab (sidebar or strip) shows a card with a page preview, title and address. Touch screens never hover.
- **Memory on hover:** Android gives an app no per-tab memory figure. The card shows the page's JavaScript heap, number of page elements, and whether the tab is Active, In background or Sleeping (not loaded). Labelled as a lower bound.
- **Passkeys:** WebAuthn turned on through Credential Manager (androidx.credentials added). Tries browser mode first, falls back to app mode. App mode works only on sites that list Hydrogen in their Digital Asset Links; passkeys on every site need Hydrogen on Google's privileged-browser allowlist. Off in private tabs. Status shown in Settings.
- **First-run build fixes:** `DEFAULT_PROFILE_NAME` (not in androidx.webkit) replaced by the literal "Default"; Gecko `lateinit` warning removed.

## First build, second pass (43 errors in :app)
- `BrowserViewModel.kt`: the import block was corrupted (a block repeated, `java.net.URLArticle`, missing `ReaderArticle`), which caused 18 errors and the cascade in `BrowserApp.kt`. Rewritten cleanly.
- `ManageSection` (declared in the `com.thebrowser` package) imported in `Sidebar.kt`, `GeminiPanel.kt`, `Overlays.kt`.
- `AgentController.kt`: cross-module smart cast on `caller.profile` replaced with `let`.
- `PdfEngine.kt`: `PdfOp` now declares `page`. `PdfScreen.kt`: `maxWidth` read before the nested Box.
- `Overlays.kt`: `AndroidView` called with named `modifier =` and `factory =`.
- Checked by script across the repo: no duplicate or unresolved project imports left. Still not compiled; more errors may appear once these are cleared.
