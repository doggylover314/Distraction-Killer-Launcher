# Distraction Killer Launcher

A deliberately boring Android home screen. It shows a clock, the date, battery,
optionally the weather, and a plain text list of apps. Version 1.0 hid apps and
blocked nothing. Version 1.1 adds an optional accessibility service that sends
hidden apps back to the home screen, filters websites wherever one is on
screen, and keeps the Android screens that could undo all of this behind the
password.

Two modes for the app list, switchable from inside the app:

- **Allowlist**: only the apps you tick show up.
- **Blocklist**: everything shows up except the apps you tick.

Both lists are stored separately, so you can flip between modes without
rebuilding either one. Changing the mode, either list, the website rules, the
enforcement switches or the password requires the password first. Changing how
the screen looks does not.

No ads, no analytics, no third-party SDKs, no account. Its one network call is
the optional weather lookup, described below. Doing its job means the
accessibility service reads what is on screen; it neither stores nor sends any
of it.

## Requirements

- Android Studio (any version new enough for Android Gradle Plugin 8.12)
- JDK 17 or newer (recent Android Studio bundles one)
- Android SDK Platform 35
- A phone on Android 8.0 or newer (minSdk 26, targetSdk 35)

## Build

From the project root:

```bash
./gradlew assembleDebug
```

Your APK lands at `app/build/outputs/apk/debug/app-debug.apk`, signed with your
machine's debug keystore, which is all a sideload needs.

In Android Studio the equivalent is **Build → Build Bundle(s) / APK(s) → Build
APK(s)**, or just press Run with your phone connected.

To check nothing is broken first:

```bash
./gradlew test lint
```

Behind that sit 80 JVM unit tests: password hashing, the allowlist and
blocklist rules, the search box, the launch counter, weather parsing, the URL
matcher, the site rules, the app-enforcement rule, the Settings-screen detector
and its unlock window, plus a pass over the bundled preset files themselves.
Android Lint runs across the module.

### The ready-made APK

`dist/distraction-killer-launcher-1.1-debug.apk` is the debug build, signed
with the debug key of the machine that built it. It installs like any other
APK. What it will not do is accept an update signed with a different debug key,
and every Android Studio installation generates its own. So if you install the
dist APK and later want to install a build of your own, either run
`adb uninstall com.distractionkiller.launcher` first (you lose the password and
the lists), or copy `~/.android/debug.keystore` from the machine that built the
dist APK onto yours before you build.

### A release APK

`./gradlew assembleRelease` produces an **unsigned** APK, which a phone will
refuse to install. No release key is in the repo; generating one was not
possible in the environment that produced 1.1. To make your own:

```bash
keytool -genkey -v -keystore distraction-killer.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias distraction-killer
```

then add a `signingConfigs` block to `app/build.gradle.kts` and point the
release build type at it:

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("DK_KEYSTORE") ?: "distraction-killer.jks")
            storePassword = System.getenv("DK_STORE_PASSWORD")
            keyAlias = "distraction-killer"
            keyPassword = System.getenv("DK_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // existing minify and proguard settings stay as they are
        }
    }
}
```

Keep the passwords in environment variables or `local.properties`, never in
git. Android Studio's **Build → Generate Signed App Bundle / APK** wizard does
the same thing through a dialog.

## Install on a phone

1. On the phone: **Settings → About phone**, tap **Build number** seven times to
   unlock Developer options, then **Settings → System → Developer options →
   USB debugging**, on.
2. Plug the phone in and accept the "Allow USB debugging?" prompt.
3. Then:

```bash
adb devices              # your phone should be listed as "device", not "unauthorized"
adb install -r dist/distraction-killer-launcher-1.1-debug.apk
```

Swap in `app/build/outputs/apk/debug/app-debug.apk` if you built it yourself.
`-r` reinstalls over an existing copy and keeps your settings, provided the
signing key matches (see above). For a genuinely clean slate, run
`adb uninstall com.distractionkiller.launcher` first.

No cable handy? Copy the APK to the phone and open it from a file manager; you
will have to allow install-from-unknown-sources for that app.

## Make it your home screen

On Android 15: **Settings → Apps → Default apps → Home app → Distraction Killer
Launcher**.

Some manufacturers move it. Samsung has it under **Settings → Apps → Choose
default apps → Home app**. Cannot find the menu? Press the home button once.
While more than one launcher is installed the system usually offers a picker.

Going back is no longer the same screen in reverse. Once the accessibility
service is on and **Lock Android Settings** is ticked, the Home app screen is
one of the pages the launcher sends you home from, and so is the "Set X as
your default home app?" dialog another launcher can raise on its own. Instead:
**Settings** on the home screen → **Protected settings** → password → **Switch
home app**, which opens a 10-minute window and jumps straight to the Home app
picker. That is the whole mechanism behind "changing the launcher back requires
the password".

## First-run setup

1. First launch asks for a password of at least 4 characters. Pick one you
   will remember; the recovery route below is deliberately awkward.
2. A starter allowlist is worked out on the device: your dialler, SMS app and
   camera, plus WhatsApp if it is installed. Everything else stays hidden until
   you tick it.
3. Open **Settings → Protected settings (password)**. Under **Enforcement** the
   screen says whether the accessibility service is on. If it is not, two
   buttons sit under that line, **App info** and **Accessibility settings**.
   Both open a 10-minute unlock window before jumping into Android Settings, so
   the Settings lock cannot bounce you off the very page you need.
4. On Android 13 and newer a sideloaded app is greyed out in the Accessibility
   list until you open its App info page, tap the ⋮ menu and choose **Allow
   restricted settings**. That is what the **App info** button is for. I believe
   the same applies to builds installed over adb, but have not been able to
   confirm it.
5. Back in the Accessibility list, tap **Distraction Killer Launcher** and
   switch it on.

Until step 5 is done the app is exactly what 1.0 was: a home screen that hides
things and blocks nothing.

## Two tiers of settings

Tapping **Settings** on the home screen opens **Appearance**, with no password.
Everything there changes how the home screen looks and nothing about what it
can reach:

- Theme: follow system, light or dark.
- App name size, and centre alignment.
- Clock on or off, following the system's 12/24-hour setting or forcing one.
- Day and date, battery, weather, Fahrenheit.
- Focus note: one line of your own under the date.
- Search box on the home screen. It filters the apps already shown and cannot
  find hidden ones, which is why it needs no password.
- "Opens today": a small count next to each app, reset at midnight.
- Package names under each app name.

A **Protected settings (password)** button at the top leads to the password
prompt and then to everything that changes what the phone can reach: the home
screen mode and both app lists (unchanged from 1.0), the **Enforcement**
section, the **Websites** section, and **Change password**. Once the settings
screen leaves the foreground it re-locks.

## Enforcement

All of it lives in one Android accessibility service,
`blocker/EnforcementService.kt`. It is inert until you switch it on under
**Android Settings → Accessibility → Distraction Killer Launcher**, and an
accessibility service is the only way a normal, non-root app is allowed to see
what is on screen. Three switches sit under Protected settings. Each defaults
to on, each can be turned off on its own, and none of them does anything while
the service is off.

### Send hidden apps home

Any app not shown on the home screen that reaches the foreground by some other
route (a notification, the share sheet, search, **Android Settings → Open**) is
sent back to the home screen with a toast. Only apps with a launcher icon are
candidates, so a file picker, a permission dialog or a share sheet belonging to
an allowed app is never touched. Only real application windows are acted on,
too. A keyboard popping up is not an app arriving (Gboard has a launcher icon,
and a Home press for every text field would make the phone unusable), and a
picture-in-picture window is skipped because an accessibility service cannot
close one. That last point is a real gap: a hidden app's PiP video, once
playing, keeps playing.

After the Home press the service looks at the foreground again once its
600 ms action throttle has passed. Without that second look, a splash screen
that hands over to its real activity a few hundred milliseconds later would
slip through and stay.

On top of the list rules a fixed set is always exempt: Android Settings, the
dialler and in-call screen, every clock app (everything that handles the alarm
intents, so a ringing alarm is never bounced, plus the stock clock packages
by name: Google, AOSP, Samsung), Pixel's Personal Safety app, every enabled
keyboard, system UI, the permission dialogs and the package installer. If
another launcher has become the default home app it is exempt as well, because
"home" then means that launcher and sending it home would loop forever; the
service logs a warning when it notices. For the exact lists see
`AppEnforcement.FIXED_EXEMPTIONS` and `AppRepository.systemExemptPackages`.

### Lock Android Settings

The lock watches more than the Settings app. Since Android 10 the **Default
apps** and **Home app** pickers are drawn by PermissionController (the role
manager's UI), so that package is watched. So is the package installer, whose
uninstall confirmation is where every uninstall route ends up, whether it
started from another launcher's app drawer or from a file manager. And so are
the system chooser and the intent resolver, which draw the "Select a Home app"
sheet. The full set of package names is `SettingsLockDetector.LOCKABLE_PACKAGES`.

Within those windows, two kinds of screen count as locked: one where some
visible text exactly matches an entry in a keyword list (in practice the
title), and one that mentions the app by name anywhere, such as its App info
page, the accessibility toggle or the uninstall dialog. A third case is matched
by phrase rather than by our name. A launcher can raise a "Set X as your
default home app?" request itself through the role manager, with no trip
through Settings, and that dialog names the *other* launcher.

By default the keywords are "Home app", "Default home app", "Default apps",
"Choose default apps", "Launcher", "Select a Home app", "Select Home app", and,
new in this round, "Multiple users", "Users", "Add user", "Add guest" and
"Guest". A guest or second user is an unrestricted phone, and **Settings →
System → Multiple users** is the gateway to one. The user switcher in Quick
Settings is drawn by System UI, which the service never touches, so that route
stays open.

A locked screen gets a Home press straight away, plus a toast. Earlier builds
pressed Back first; on the home-app picker a quick tap on another launcher
could beat that, and Home is final.

There is one exception, a timed window. **Allow changes for 10 min** and
**Switch home app** in Protected settings both open one (the constant is
`SETTINGS_UNLOCK_MINUTES`), and so do the **App info** and **Accessibility
settings** buttons. While it is open the lock stands down completely and the
screen says "allowed for about N min". The window is measured in time since
boot (`SystemClock.elapsedRealtime`), with the moment it was granted stored
alongside its end, so moving the date or the clock cannot stretch it, and a
reboot ends it early rather than late.

One more stand-down, this one automatic. The app's own location prompt for the
weather line is drawn by PermissionController and names the app, which is
exactly what the lock watches for. `HomeActivity` tells the service to ignore
locked screens for 60 seconds before it asks, so the prompt is not sent home.

Detection is by on-screen text, because Android's Settings app does not expose
distinct activity names for its sub-screens. That makes it English-first.
Because the keyword list is editable under **Locked Settings screens**, a phone
in another language can have its own titles added; until then the lock does
nothing useful there.

### Website filtering

Any window that contains a WebView is inspected, browser or not. That covers
the in-app browsers of WhatsApp, Instagram, X, the Google app and the like, and
Chrome Custom Tabs. Browsers themselves are still found by asking the system
what can open an `https` link, topped up with a fixed list of known package
names, and they get the fast path.

The URL is read from the first of these that turns up: a known address-bar id
(Chromium's `url_bar`, shared by Chrome, Brave, Edge, Vivaldi, Kiwi; Firefox's
toolbar id; and best-effort ids for Samsung Internet, Opera and DuckDuckGo);
any view whose id looks like a URL field; any editable field holding a host;
and finally any plain text holding a host outside the web content itself. That
last rule is what catches in-app browsers and Custom Tabs, which show the
domain in an ordinary TextView. Text inside the WebView is never read, so a
page that merely mentions `reddit.com` cannot trigger a block. The walk stops
after 500 nodes per window.

Browsers are re-checked every 300 ms while their content changes. Other apps
are walked on a window change and then at most every 1.2 seconds, which is
enough to catch an in-app browser without taxing a chat list being scrolled.
In split-screen every window belonging to the package is inspected, the
unfocused half included.

A page is judged only once it has loaded and the address bar is no longer
focused, so typing is not interrupted. A blocked site gets a Back press; if the
same host is still there within two seconds, Home. Either way a toast names the
host. In allowlist mode a real address bar showing something that is not a
host (`data:`, `file:`, `view-source:`, `chrome://`) is treated as a page that
is not on the list and blocked; the browser's own new-tab and blank states are
let through. An e-mail address on screen is never treated as a URL, and an
internationalised domain is compared in its ASCII (punycode) form, which is
the form the lists hold.

## Websites and presets

Websites get their own **Blocklist / Allowlist** mode, separate from the
app-list mode. Patterns are registrable domains: `reddit.com` covers
`www.reddit.com`, `old.reddit.com` and every other subdomain, and does not
cover `notreddit.com`. A leading `www.` or `*.` is stripped when you add one.

Allowlist mode blocks everything not listed, so add your essentials first. An
empty allowlist, meaning no custom sites and no presets switched on, is treated
as "nothing configured" and blocks nothing, because blocking the whole web is
only ever a mistake.

Presets are bundled lists under `app/src/main/assets/presets/`: `index.tsv` is
the catalog, and each preset is one `.txt` file with a domain per line. They
are toggled one at a time, and the screen shows how many domains each holds.

Block presets:

- **distractions**, **news**, **shopping**, **games**: hand-curated for this
  project, public domain.
- **adult**, **gambling**: derived at build time from the StevenBlack/hosts
  extensions (MIT), collapsed to registrable domains by
  `tools/build_presets.py`. Expect the adult list to be large, around 42,000
  domains. Licence text and the transformation applied are in
  `THIRD_PARTY_NOTICES.md`.
- **proxies**, shown as "Proxies & mirrors": Google Translate, web caches,
  archive.org and archive.today, 12ft.io and the alternative front ends
  (Nitter, Invidious, Piped, Libreddit). Hand-curated, 24 domains,
  and the only preset switched on by default. Address-bar matching sees one
  host name, and every site on this list shows a blocked page under its own.
  For the same reason archive.org was dropped from the learning preset, and a
  unit test checks that nothing in an allow preset is also a proxy.

Allow presets: **essentials**, **work**, **learning**, all hand-curated.

Presets belong to a mode. An allow preset that is switched on has no effect
while the mode is Blocklist, and the other way round. Both kinds share one
"enabled" set, so flipping the mode and back keeps your choices.

`tools/build_presets.py` writes every list and the catalog, and `--check`
validates whatever is on disk without touching it. A full run downloads the two
StevenBlack extensions and the Public Suffix List into `tools/.cache/`, so it
needs the network once. The hand-curated lists, the proxies preset included,
live in tables at the top of the script; edit them there, not in the `.txt`
files, or the next run will overwrite your change.

## Weather and the network

Weather is on by default and needs two things: the coarse-location permission,
and one HTTPS call to `api.open-meteo.com`, which takes no API key and no
account. Latitude and longitude are rounded to two decimal places, roughly a
kilometre, before being sent, and any reading is cached for 30 minutes. Nothing
else leaves the device, ever.

Turn **Weather** off in Appearance settings and the app makes no network calls
at all, and never asks for location. Deny the permission and it drops weather
from the header and stops asking. The prompt itself is a system dialog that
names the app, so the home screen tells the accessibility service to stand
down for a minute before asking; see the Settings lock above.

## Forgotten password, and getting out

There is no recovery inside the app, on purpose. In 1.0 the way out was
**Settings → Apps → Distraction Killer Launcher → Storage → Clear storage**,
which wipes the password hash and every list and starts you over from first-run
setup. That still works, with a catch. If the accessibility service is on and
**Lock Android Settings** is ticked, that App info page mentions the app by
name, so the launcher sends you home from it. Uninstalling from another
launcher's drawer or from a file manager ends at the package installer's
confirmation, which is locked as well. Opening a 10-minute window needs the
password you have forgotten.

Safe Mode is the route.

1. Long-press the power button, then long-press **Power off** and confirm
   **Reboot to safe mode**. Exact gestures vary a little by manufacturer;
   the phrase to look for is "safe mode".
2. Safe Mode boots with third-party apps disabled, which includes the
   accessibility service, so nothing is enforced.
3. Go to **Settings → Apps → Distraction Killer Launcher** and either **Storage
   → Clear storage** (keeps the app, resets it) or **Uninstall**.
4. Reboot normally.

Your other apps and their data are untouched throughout. Read this section
before you rely on the lock; it is the one way to get locked out.

## Layout

```
app/src/main/java/com/distractionkiller/launcher/
├── HomeActivity.kt              the home screen; CATEGORY_HOME lives here
├── SettingsActivity.kt          Appearance -> password prompt -> Protected
├── blocker/
│   ├── AccessibilityStatus.kt   is the service on; links into Android Settings
│   ├── AppEnforcement.kt        "send this app home?" rule (pure, tested)
│   ├── EnforcementService.kt    the accessibility service itself
│   ├── PresetParser.kt          index.tsv and .txt formats (pure, tested)
│   ├── SettingsLockDetector.kt  locked-screen detection, unlock window (pure, tested)
│   ├── SiteRules.kt             website mode + lists -> verdict (pure, tested)
│   ├── UrlMatcher.kt            on-screen text -> host (pure, tested)
│   └── WebsitePresets.kt        reads assets/presets
├── data/
│   ├── AppFilter.kt             mode + lists -> what to show (pure, tested)
│   ├── AppRepository.kt         PackageManager queries, exemptions, browsers
│   ├── Appearance.kt            theme, text size and clock format enums
│   ├── LaunchCounter.kt         "opens today" (pure, tested)
│   ├── LaunchableApp.kt
│   ├── LauncherMode.kt
│   ├── PasswordHasher.kt        salted SHA-256 (pure, tested)
│   └── Prefs.kt                 every persisted setting
├── ui/                          Compose screens
│   ├── AppearanceSettingsScreen.kt
│   ├── ProtectedSettingsScreen.kt
│   ├── HomeScreen.kt, HomeUiConfig.kt, StatusHeader.kt, SystemStatus.kt
│   ├── PasswordScreens.kt, SettingsComponents.kt
│   └── theme/Theme.kt
└── weather/
    ├── WeatherCodes.kt          WMO code -> text (pure, tested)
    ├── WeatherParser.kt         response parsing (pure, tested)
    ├── WeatherRepository.kt     location + the one HTTP call
    └── WeatherSnapshot.kt

app/src/main/assets/presets/     index.tsv + one .txt per preset
app/src/main/res/xml/enforcement_service.xml
dist/                            the ready-made debug APK
tools/build_presets.py           regenerates the presets (Python 3, stdlib only)
THIRD_PARTY_NOTICES.md
```

Logic worth testing sits in classes with no Android imports, so it runs under
plain JUnit. No emulator, no Robolectric.

## Things worth knowing before you rely on it

**The password is a speed bump, not security.** It is a salted SHA-256 hash.
Enough that the password is not sitting in `shared_prefs` in plain text, and
nowhere near enough to survive anyone with root or an offline attack on the
file. Swapping `PasswordHasher.hash` for PBKDF2 is a three-line job if you ever
want it.

**Safe Mode beats everything.** Android disables every third-party
accessibility service in Safe Mode, and from there anyone holding the phone can
clear the app's storage or uninstall it. No password is asked. Turning the phone
off and on into Safe Mode takes about a minute. `adb uninstall` and a factory
reset are not blocked either. What the lock does is make getting around it
deliberate and slow; it does not make it impossible.

**The service knows about a few more gaps.** For the seconds after boot before
Android starts the accessibility service, nothing is enforced. The Quick
Settings user switcher lives in System UI and can start a guest session without
passing through the locked Settings page. A hidden app's picture-in-picture
video keeps playing, because an accessibility service cannot close it. A page
that goes fullscreen before the service has judged it hides its address bar,
and with no URL on screen there is nothing to block. The proxies preset lists
the proxies it knows about; one that is not on it shows a blocked site under
its own host name like any other.

**Nothing here has run on a real device.** The build compiles, lint is clean
and 80 unit tests pass, but no phone or emulator has ever launched it. Expect a
rough edge or two, and expect the first one to be in the accessibility service.

**Settings detection is text-based and English-first.** Manufacturer Settings
apps in other languages will sail past the lock until you add their screen
titles to the keyword list. Even in English, an OEM that gives the page a new
title is a gap until that title is added, and an OEM whose Settings live in a
package not on the lockable list is a gap until that name is added too.

**The non-Chromium address-bar ids are unverified.** Chromium's `url_bar` and
Firefox's toolbar id are known. Samsung Internet, Opera and DuckDuckGo got ids
written from memory, which may be wrong; the generic fallback should still
catch those browsers, but that too is untested. The same goes for in-app
browsers and Custom Tabs: the rule assumes the app shows the domain as plain
text outside the WebView, and an app that shows only the page title is not
judged at all.

**Two comments in the source flag API details I could not verify.** First,
`android:enableOnBackInvokedCallback` in the manifest: I was unsure whether API
35 flips its default, so the manifest sets it explicitly. Second, the WMO
weather-code table, transcribed from Open-Meteo's documentation rather than an
official WMO source. Both are marked in place.
