# Signing

`distraction-killer.jks` is the release key and `keystore.properties` holds its
password. Both are committed so any checkout can build an APK that installs
over the one already on the phone.

Release builds matter here for more than tidiness. A debug APK is signed with
the Android debug key, which every SDK install on earth shares, and its
manifest carries `android:debuggable="true"`. Play Protect treats both as
warning signs, so a debug build of an app that also asks for an accessibility
service is a good candidate to be refused at install time.

To rotate the key:

```bash
keytool -genkeypair -v -keystore signing/distraction-killer.jks -storetype PKCS12 \
        -keyalg RSA -keysize 2048 -validity 10000 -alias distraction-killer
```

Update the passwords in `keystore.properties` afterwards. A new key will not
match the installed app, so uninstall the old build from the phone before
installing one signed with the replacement.
