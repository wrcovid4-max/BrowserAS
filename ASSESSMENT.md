# Technical assessment, feasibility, status and test plan

Legend: **F** fully feasible · **L** feasible with limitations · **S** needs a separate per-platform implementation · **N** not currently feasible.
"Code" = written in this bundle, **never compiled or tested**. A + means it is also covered by a unit test.
A = Android (Chromium WebView), W = Apple (WebKit).

## Engine facts that shape everything
- Android WebView ≠ desktop Chrome: **no extension host, no Chrome Sync, no site-isolation or storage-partition controls, no web notifications/WebUSB/Bluetooth/Serial/screen capture**. Request interception, Safe Browsing, SSL callbacks, per-profile data and document-start scripts are available (version dependent; the Security Check reports what is missing).
- WKWebView exposes **no per-request hook**: only compiled content rules, so ad/tracker/cookie blocks cannot be counted, CNAME cloaking cannot be detected and downloadable filter lists cannot be used. Certificate errors are final (no bypass). Safe Browsing is WebKit's own and not observable.
- iOS/iPadOS require WebKit (alternative engines only under region-specific entitlements; unverified here). No Chromium switch is offered on Apple platforms.
- Chrome Web Store and Safari App Store installs are not implemented. Unpacked folders/ZIPs only.

## Shields, privacy and network (spec §3, §11)
| Feature | Class | A | W | Notes |
|---|---|---|---|---|
| Native ad / tracker / social / embedded-tracker blocking | A:L W:L | Code, counted | Code, not countable | Built-in domain lists; Android adds user lists. First-party ads on unlisted hosts are not caught. |
| Third-party cookie blocking | F | Code | Code (rule) | Per-site override only on Android (per WebView). |
| Cookie / DOM-storage / network-state / cache partitioning | N (app-controllable) | engine-managed | engine-managed (ITP) | Reported as Unsupported in Security Check; Profiles give hard isolation instead. |
| Fingerprinting: canvas, WebGL, audio, fonts, browser APIs | L | Code, event-counted | Code, event-counted | JS hooks at page start; bypassable (workers, iframes before injection). Noise seeded per site + app run. |
| WebRTC privacy | L | Code | Code | Relay-only / disabled via JS wrapper; may break calls. |
| Tracking query-parameter removal | F | Code | Code | ~30 known parameters. |
| Bounce-tracking protection | L | Code | Code | Unwraps known link wrappers (Facebook, Google, Reddit, …) and flags long redirect chains. Not a full bounce-storage purge. |
| CNAME-cloaking detection | A:L W:N | Code (DNS CNAME query, cached) | — | First request per host waits up to ~1.2 s. |
| Referrer minimisation | L | Code | Code | `<meta name=referrer>` at page start; pages can override. |
| Social-media tracker blocking | F | Code | Code | |
| Global Privacy Control / Do Not Track | L | Code | Code | `navigator` property + main-frame request header (Android). Not all sites read it. |
| Cookie-consent banner blocking | L | Code | Code | Hides known banners; does not click "reject". |
| Configurable / auto-updating / custom filter lists | A:L W:N | Code | — | Domain-level only (hosts files, `\|\|domain^`). Weekly auto-update, ETag. |
| Standard / Aggressive / Custom levels, on by default, per-site toggle | F | Code | Code | |
| Real, event-based stats; reset | F | Code | Code | WebKit cannot count tracker/ad blocks, so none are shown. |

## Website security (spec §4–§6)
| Feature | Class | A | W | Notes |
|---|---|---|---|---|
| HTTPS-First / Strict, automatic upgrade, HTTP warning, secure indicator | F | Code | Code | Strict removes "proceed". Local addresses skipped. |
| TLS validation + certificate interstitial | F | Code (proceed only outside Strict, double-confirm) | Code (no bypass) | Never calls proceed silently; sub-resource SSL errors always cancelled. |
| HSTS, CT, mixed content, secure WebSocket | engine | engine | engine | Enforced by the engine where supported; mixed content set to never-allow. Not independently verifiable. |
| Secure DNS / DoH | N for page loads | Private DNS status read in Security Check | system setting | Apps cannot set DoH for WebView/WKWebView. |
| IDN homograph + lookalike warnings | F | Code+ | Code | Punycode, mixed scripts, brand skeleton / edit distance (small brand list). Heuristic. |
| Suspicious-redirect warning | L | Code | Code | |
| Safe Browsing, phishing/malware warning page | L | Code (custom page, explains provider) | engine page | Google Safe Browsing via WebView (hash prefixes); Apple's Fraudulent Website Warning. No history sent by this app. |
| Dangerous-download warning | L | Code+ | Code | Extension/double-extension/HTTP checks; no file scanning. |
| Suspicious notification requests | N | web notifications unsupported | unsupported | Always blocked by absence. |
| Renderer sandbox, Site Isolation, SOP/CORS/COOP/COEP/CORP/CSP | engine | engine | engine | Not configurable or verifiable from the app; documented as such. |

## Private browsing, passwords, permissions (spec §7–§9)
| Feature | Class | A | W | Notes |
|---|---|---|---|---|
| Private tabs: no history, no persistent cookies/storage, separate permissions, deleted on close, private downloads not listed | F | Code | Code | Android needs WebView multi-profile; leftover private profiles purged at start. |
| Biometric / device-credential lock, auto-lock, manual lock, clear all | F | Code (BiometricPrompt) | Code (LocalAuthentication) | Device with no screen lock: reports Unsupported. |
| Not marketed as anonymity | — | UI text | UI text | |
| Password manager, generator, strength/reuse, breach check, CSV import/export, encrypted export | F | Code+ (Keystore key, 30 s auth window) | Code (Keychain, app-gated) | Apple gate is app-level; Android key is hardware-gated. Passkeys cannot be exported. |
| Autofill | L | Code (own fill on exact HTTPS origin) + system autofill | Code | Private tabs never autofill or save. |
| Passkeys / WebAuthn | L | WebView-version dependent | Needs Apple entitlements | Not claimed; listed "Not checked". |
| Permission Centre: defaults, per-site, allow/ask/block, temporary, history, expiry, inactivity revoke, reset | F | Code | Code | Per-site rules exist for camera, mic, location (A), MIDI (A), pop-ups, JavaScript, third-party cookies (A). |
| Notifications, clipboard, auto-downloads, background sync, motion, USB, Bluetooth, serial, screen capture | N | shown as Not supported / engine-managed | same | Listed honestly in the Centre. |

## Extensions (spec §10)
| Feature | Class | Notes |
|---|---|---|
| Chrome MV3 (Android) | L, large gap | Content scripts only (+ small `chrome.storage.local`/`runtime` shim). No background workers, popups, tabs/webRequest/DNR. Main-world execution. |
| Safari/Chrome-style web extensions (Apple) | L | WebKit `WKWebExtension` runtime (iOS 18.4 / macOS 15.4 / visionOS 2.4). Tab/window adapter not built, so tab APIs are limited. |
| Never silent install, per-site approval, broad access off by default with warning, optional-permission display, publisher info (self-declared), static security scan (heuristic), update by re-import without silent new access, not in private tabs, enable/disable/remove | F | Code (A+ for manifest and pattern logic; W code) | |

## Dashboard and Security Check (spec §12–§13)
Security & Privacy Centre, current-site panel, real-event stats and a Security Check with Secure / Attention / Unsupported / Not checked exist on both platforms. Each row derives from a real signal (engine query, OS API, stored settings); unverifiable items are shown as Unsupported or Not checked, never as Secure.

## Arc-style experience, Gemini, design (spec §14–§16)
Vertical tabs, Spaces, groups, pinned/favourites/temporary, tab previews, drag-and-drop, split view, Reader, Profiles, downloads manager, bookmarks, history, recently closed, session restoration, search-engine choice, import/export, command bar: Code on both (Apple: no recently-closed list or custom toolbar; neither platform has custom homepage, tab suspension beyond the LRU session cap, or a customisable toolbar). Gemini sidebar: Code (resizable only as a fixed-width panel). Design: Material 3 Expressive + dynamic colour (A), Liquid Glass + App Intents (W).

## Platform-specific opportunities (reported per your iPad rule)
Pointer hover, hardware shortcuts, drag/drop, multi-window Little windows, Scribble in fields, Stage Manager / external display (model-dependent), Apple Pencil hover (M2 iPads only), App Intents for Siri/Shortcuts/Spotlight/Action button, Foundation Models on-device assistant, Liquid Glass. Android: predictive back, adaptive window size classes, Material You icons, PiP, app shortcuts, share target, process-text ("Ask Gemini"), Android XR runs as a 2D panel.

## Phase 6 verification checklist (NOT yet done)
1. Compile both projects; fix errors. 2. Certificate: open an expired/self-signed test host (badssl.com): interstitial appears, Strict has no proceed. 3. Safe Browsing: Google's test pages. 4. Tracker blocking: load a test page, confirm stats rise only on real blocks (Android). 5. Fingerprinting: compare canvas hash before/after. 6. Permissions: camera prompt, temporary expiry, revoke. 7. Private: close last private tab, confirm cookies gone; background → locked. 8. Profiles: log into the same site in two Profiles. 9. Extensions: install a sample content-script extension, confirm it only runs on approved sites and never in private tabs. 10. Vault: unlock, fill on exact origin only. 11. iPad layouts, Android tablet/foldable, WebKit-specific and Chromium-specific behaviour.

## Dependencies and external services
Android: AndroidX (core, activity, lifecycle, webkit 1.14, biometric 1.1), Compose BOM 2026.09, Material 3 1.5, kotlinx serialization/coroutines. Apple: system frameworks only (+ XcodeGen tooling). Services (all optional): Google Gemini API, Google Safe Browsing (inside WebView), Have I Been Pwned range API, filter-list hosts you add, your search engine's suggest endpoint. No analytics, no accounts.
