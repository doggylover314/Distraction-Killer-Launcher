# Distraction Killer Launcher

A deliberately boring Android home screen. It shows a clock, the date, battery,
optionally the weather, and a plain text list of apps. Everything else about
your phone is unchanged. This is not a kiosk mode, and it does not try to stop
you reaching Android's own Settings.

Two modes, switchable from inside the app:

- **Allowlist**: only the apps you tick show up.
- **Blocklist**: everything shows up except the apps you tick.

Both lists are stored separately, so you can flip between modes without
rebuilding either one. Changing the mode, either list, or the password requires
the password first.

No ads, no analytics, no third-party SDKs, no account. The one network call in
the whole app is the optional weather lookup, described below.

## Requirements

- Android Studio (any version new enough for Android Gradle Plugin 8.12)
- JDK 17 or newer (recent Android Studio bundles one)
- Android SDK Platform 35
- A phone on Android 8.0 or newer (minSdk 26, targetSdk 35)

## Build a debug APK

From the project root:

```bash
./gradlew assembleDebug
```

Your APK lands at `app/build/outputs/apk/debug/app-debug.apk`, signed with the
standard debug keystore, which is all a sideload needs.

In Android Studio the equivalent is **Build → Build Bundle(s) / APK(s) → Build
APK(s)**, or just press Run with your phone connected.

To check nothing is broken first:

```bash
./gradlew test lint
```

Behind that sit 34 JVM unit tests over the password hashing, the allowlist and
blocklist rules, the search box and the weather parsing, plus Android Lint
across the module.

## Install on a phone

1. On the phone: **Settings → About phone**, tap **Build number** seven times to
   unlock Developer options, then **Settings → System → Developer options →
   USB debugging**, on.
2. Plug the phone in and accept the "Allow USB debugging?" prompt.
3. Then:

```bash
adb devices              # your phone should be listed as "device", not "unauthorized"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` reinstalls over an existing copy and keeps your settings. For a genuinely
clean slate, run `adb uninstall com.distractionkiller.launcher` first.

No cable handy? Copy the APK to the phone and open it from a file manager; you
will have to allow install-from-unknown-sources for that app.

## Make it your home screen

On Android 15: **Settings → Apps → Default apps → Home app → Distraction Killer Launcher**.

Some manufacturers move it. Samsung has it under **Settings → Apps → Choose
default apps → Home app**. Cannot find the menu? Press the home button once. While more than one launcher
is installed the system usually offers a picker.

Going back to your old launcher is the same screen, in reverse. Distraction Killer Launcher never
blocks that, by design.

## Building a release APK

`./gradlew assembleRelease` produces an **unsigned** APK, which a phone will
refuse to install. For personal use the debug APK above is simpler. If you do
want a signed release build, create a keystore:

```bash
keytool -genkey -v -keystore distraction-killer.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias distraction-killer
```

then add a `signingConfigs` block to `app/build.gradle.kts` pointing at it, with
the password kept in `local.properties` or an environment variable rather than
committed. Android Studio's **Build → Generate Signed App Bundle / APK** wizard
does the same thing through a dialog.

## Forgotten password

There is no recovery, on purpose. Locked out? **Settings → Apps → Distraction Killer Launcher →
Storage → Clear storage** wipes the password hash and both app lists, and the
next launch starts over from first-run setup. Your actual apps and data are
untouched.

## Weather and the network

Weather is on by default and needs two things: the coarse-location permission,
and one HTTPS call to `api.open-meteo.com`, which takes no API key and no
account. Latitude and longitude are rounded to two decimal places, roughly a kilometre,
before being sent, and any reading is cached for 30 minutes. Nothing else leaves the device, ever.

Turn **Show weather** off in Settings and the app makes no network calls at all,
and never asks for location. Deny the permission and it drops weather from the
header and stops asking.

## Layout

```
app/src/main/java/com/focushome/launcher/
├── HomeActivity.kt            the home screen; CATEGORY_HOME lives here
├── SettingsActivity.kt        password gate and the editor behind it
├── data/
│   ├── AppFilter.kt           mode + lists -> what to show (pure, tested)
│   ├── AppRepository.kt       PackageManager queries, default allowlist
│   ├── LaunchableApp.kt
│   ├── LauncherMode.kt
│   ├── PasswordHasher.kt      salted SHA-256 (pure, tested)
│   └── Prefs.kt               every persisted setting
├── ui/                        Compose screens
└── weather/
    ├── WeatherCodes.kt        WMO code -> text (pure, tested)
    ├── WeatherParser.kt       response parsing (pure, tested)
    ├── WeatherRepository.kt   location + the one HTTP call
    └── WeatherSnapshot.kt
```

Logic worth testing sits in classes with no Android imports, so it runs under
plain JUnit. No emulator, no Robolectric.

## Things worth knowing before you rely on it

**The password is a speed bump, not security.** It is a salted SHA-256 hash, as
specified. Enough that the password is not sitting in `shared_prefs` in plain
text, and nowhere near enough to survive anyone with root or an offline attack
on the file. Swapping `PasswordHasher.hash` for PBKDF2 is a three-line job if you ever want
it.

**It does not block anything.** Hidden apps stay installed and stay reachable
from search, notifications, recents and Android Settings. Removing an app from
the home screen removes the prompt to open it. That is the entire mechanism.

**Two comments in the source flag API details I could not verify.** First,
`android:enableOnBackInvokedCallback` in the manifest: I was unsure whether API
35 flips its default, so the manifest sets it explicitly. Second, the WMO
weather-code table, transcribed from Open-Meteo's documentation rather than an
official WMO source. Both are marked in place.

**Nothing here has run on a real device.** The build compiles, lint is clean and
the unit tests pass, but no phone or emulator has ever launched it. Expect a
rough edge or two.
