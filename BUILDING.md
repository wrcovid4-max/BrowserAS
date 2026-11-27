# Building Hydrogen

## Unpack (if you received the single-file bundle)
```
python3 - <<'PY'
import re, os
H = '#' * 5
t = open('Hydrogen_all_in_one.txt', encoding='utf-8').read()
for m in re.finditer('^' + H + r' FILE: (.+?) ' + H + r'\n(.*?)\n' + H + ' END FILE ' + H + '$', t, re.S | re.M):
    p = m.group(1); os.makedirs(os.path.dirname(p) or '.', exist_ok=True); open(p, 'w', encoding='utf-8').write(m.group(2) + '\n')
PY
pip install pillow numpy && python3 branding/make_icons.py     # writes the Apple AppIcon PNGs, visionOS layers, .ico/.icns
```

## Android Studio (AS)
1. Open the folder with `settings.gradle.kts`. Let Studio write `local.properties`; install SDK Platform 37 and JDK 17.
2. Studio offers to create the Gradle wrapper jar (only `gradle-wrapper.properties` is included; Gradle 9.3.1).
3. Versions in `gradle/libs.versions.toml` were checked against Android Developers docs; if a version can't be resolved, accept Studio's nearest suggestion (webkit, activity, lifecycle, biometric are the ones I could not confirm).
4. Run on a **physical device** (the 2015 MacBook Air is tight for emulators). Check that the installed Android Studio supports your macOS.
5. Settings → Gemini: paste your key, "Refresh models". Everything builds and runs with every key blank.
6. Unit tests: `./gradlew :core:test` (Air Traffic Control, URL resolver, match patterns, extension manifest).
7. Security features need a recent Android System WebView: multi-profile (Profiles, private tabs), document-start scripts (fingerprinting, extensions) and web-message listener. The Security Check reports which are missing.

## Xcode 27
1. `brew install xcodegen && cd apple && xcodegen generate` creates `Hydrogen.xcodeproj` (iOS, macOS, visionOS targets).
2. Set your signing team on each target. Camera/microphone/location/Face ID strings are already in the spec.
3. Deployment: iOS/iPadOS 17, macOS 14, visionOS 2. A Mac that cannot run macOS 14+ or Xcode 27 cannot build this; Xcode Cloud or another Mac works.
4. Check each device's maximum OS before installing. Web extensions need iOS 18.4 / macOS 15.4 / visionOS 2.4.
5. visionOS icon: drag `branding/visionos/layer_back.png` and `layer_front.png` into the visionOS App Icon slots.

## Git
`main` only, no extra branches. `local.properties`, keystores and `CLAUDE.md` are gitignored.

## Gecko engine (optional, off by default)
Gecko is off by default (`hydrogen.gecko=false` in `gradle.properties`) because it makes the APK very large. Set it to `true` and sync to include it. When on, the Android build includes `:engine-gecko` (GeckoView, from maven.mozilla.org). To build without it, add `hydrogen.gecko=false` to `gradle.properties`. `geckoview` is set to `+` in `gradle/libs.versions.toml`; after the first successful sync, pin the version Gradle resolved, because GeckoView's API changes between majors and `GeckoEngineSession.kt` was written without compiling.

Debug builds install as `com.thebrowser.debug`, so they can sit beside any older Hydrogen build without a signature clash.
