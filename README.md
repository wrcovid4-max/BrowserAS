# Hydrogen (repo: TheBrowserAS)

A privacy-first, Arc-inspired browser. **Android / Android XR** use Chromium (Android System WebView). **iPhone, iPad, Mac, Apple Vision Pro** use Apple WebKit (`WKWebView`).
Branding: the Hydrogen "H" mark (see `branding/` and `apple/ICONS.md`). Package ids and the repo name are unchanged.

> **Status: written, never compiled, never run.** It was authored with no Android SDK, Kotlin/Swift compiler, Xcode or device. Expect compile errors on the first Gradle sync and Xcode build, and treat every "implemented" row below as *implemented in code, unverified*. Security-critical behaviour (certificate interstitials, Safe Browsing, permission prompts, vault unlock, private-session deletion, extension scoping) must be tested on real devices before anyone relies on it.

Targets: Android Studio (AGP 9.1, Gradle 9.3, Kotlin 2.4, Compose 1.12, Material 3 1.5 Expressive, compileSdk 37, minSdk 30) and Xcode 27 (iOS/iPadOS 17+, macOS 14+, visionOS 2+, Liquid Glass, App Intents).

## Layout

```
core/             Kotlin library: model, Air Traffic Control, shield engine (filters, URL cleaning, lookalike checks, page scripts),
                  stats, password tools + Keystore vault, extension runtime/scan, sync, Gemini client, reader
engine-chromium/  Chromium engine: HTTPS-First, SSL + Safe Browsing interstitials, tracker/ad/CNAME blocking, permissions, scripts
app/              Compose Material 3 Expressive UI, ViewModel, Security & Privacy Centre, Permission Centre, Passwords, Little window
apple/            SwiftUI app (XcodeGen spec): iOS / iPadOS / macOS / visionOS, WebKit, Liquid Glass, App Intents, WKWebExtension
branding/         icon generators and outputs (Apple asset catalog, Android adaptive vectors, visionOS layers, .ico/.icns)
```

## What exists in code (unverified)

| Area | Android (Chromium) | Apple (WebKit) |
|---|---|---|
| Vertical tabs, Spaces, groups, pinned/favourites/temporary, command bar, shortcuts, split view, tab switcher with previews, drag-and-drop | ✅ | ✅ |
| Air Traffic Control (wildcards, exceptions, override; **Profile change always asks**) + unit tests | ✅ | ✅ |
| Profiles with isolated cookies/storage | ✅ (needs WebView multi-profile) | ✅ (`WKWebsiteDataStore(forIdentifier:)`) |
| Shield levels Standard / Aggressive / Custom, per-site toggle, default ON | ✅ | ✅ |
| Third-party tracker / ad / social blocking | ✅ request interception, **counted** | ✅ compiled content rules, **not countable** |
| Filter lists (presets, custom URL/paste, weekly auto-update) | ✅ domain rules only | ❌ not possible with WKWebView |
| Third-party cookie blocking | ✅ | ✅ rule |
| Fingerprinting (canvas, WebGL, audio, fonts, APIs) | ✅ JS hooks, event-counted | ✅ same hooks |
| WebRTC relay/off, referrer minimisation, GPC/DNT, cookie-banner + ad-slot hiding | ✅ | ✅ |
| Tracking-parameter removal, tracking-redirect unwrap, suspicious-redirect warning | ✅ | ✅ |
| CNAME-cloaking protection | ✅ DNS CNAME lookup | ❌ no per-request hook |
| HTTPS-First / Strict, HTTP warning, certificate interstitial (no silent bypass), IDN / lookalike warning | ✅ | ✅ (no cert bypass at all) |
| Safe Browsing | ✅ engine (custom warning page) | ✅ engine (WebKit's own warning) |
| Dangerous-download warning | ✅ | ✅ |
| Private browsing (throw-away profile / non-persistent store) + biometric lock + clear-all | ✅ | ✅ |
| Password manager, generator, strength/reuse, breach check (k-anonymity), CSV import/export, fill | ✅ Keystore, hardware-gated | ✅ Keychain, app-gated |
| Website Permission Centre (defaults, per-site, expiry, inactivity revoke, history, reset) | ✅ | ✅ |
| Extensions | ✅ content-scripts-only Chrome-style runtime, per-site approval | ✅ WebKit WKWebExtension runtime (iOS 18.4+) |
| Security & Privacy Centre, current-site panel, real-event stats, Security Check | ✅ | ✅ (no tracker counts) |
| Reader, offline reading, notes/research boards, bookmarks import/export, downloads (pause/resume), history, find, print, Little window | ✅ | ✅ (find not built) |
| Gemini sidebar (latest Flash default, consent, translate, compare tabs, history) | ✅ | ✅ (+ on-device Apple Intelligence option) |
| File sync (shared format, optional passphrase) | ✅ | ✅ |
| Material 3 Expressive, dynamic colour, adaptive window sizes, predictive back, PiP, shortcuts, share/process-text targets | ✅ | — |
| Liquid Glass, App Intents (Siri/Shortcuts/Spotlight), multi-window, Pencil Scribble | — | ✅ |

## Gemini

Default model `gemini-3.8-flash` (latest Flash in Google's docs, 14 Nov 2026). The key is never in source or binary (Keystore / Keychain, or your own proxy via `GEMINI_PROXY_URL` on Android). Page text is sent only after you confirm a dialog listing it, never from private tabs, never passwords or form fields. `alt=sse` streaming and the model-list call follow the REST convention: confirm on first run.

## Things I could not do

- Read the translate / Ask-AI code in your **CashMemerAS** repo (GitHub blocked automated access to its folders). Those features are new code built from the Gemini docs.
- Compile, run or test anything. No screenshots, no device results.
- Make iOS-only items that need Apple approval: passkeys and default-browser status need entitlements from Apple.
