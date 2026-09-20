# Signing

The release key is **not** in this repository, and must not be put back.

## Why

An earlier build committed the key and its password here, on the assumption
that the repository was private. It is public. A published signing key lets
anyone build an APK that Android accepts as an *update* to an install, which
keeps the app's data and skips the uninstall that a mismatched signature would
otherwise force.

That first key is still reachable in git history, and that is fine: nothing is
signed with it any more. It was replaced before any build reached a phone, so
it now signs nothing that exists. Rewriting history to scrub it would buy
nothing and would break every existing clone.

## Building a signed release

You need two files that git ignores: `distraction-killer.jks` and
`keystore.properties`. Copy `keystore.properties.example` to
`keystore.properties` and fill in the password that came with the keystore.

Environment variables take precedence, which is handier for CI:

```bash
DK_KEYSTORE=/path/to/distraction-killer.jks \
DK_STORE_PASSWORD=... \
DK_KEY_ALIAS=distraction-killer \
DK_KEY_PASSWORD=... \
./gradlew assembleRelease
```

Without either, `assembleRelease` still runs and produces an unsigned APK,
which a phone will refuse to install. Debug builds and the test suite are
unaffected.

## Keep a backup

Losing the keystore means you can never update an installed copy again; you
would have to uninstall, losing the password and both app lists. Keep it
somewhere durable and private, such as a password manager.

## Rotating

```bash
keytool -genkeypair -v -keystore signing/distraction-killer.jks -storetype PKCS12 \
        -keyalg RSA -keysize 3072 -validity 10000 -alias distraction-killer
```

Update `keystore.properties` afterwards. A new key cannot update an existing
install, so uninstall the old build from the phone first.
