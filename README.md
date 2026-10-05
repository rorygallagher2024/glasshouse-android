# Glasshouse for Android

A phone app for [Glasshouse](https://github.com/rorygallagher2024/lg-webos-dashboard),
the dashboard for rooted LG webOS TVs. It keeps a list of TVs and opens each
one's web dashboard full screen. The dashboard itself is the same page a browser gets;
the app adds the list, switching between TVs, and the file picker the
sideload tab needs.

On first launch an introduction explains that the TV needs the Glasshouse
server installed, with a link to the
[installation guide](https://rorygallagher2024.github.io/lg-webos-dashboard/install/).
The About screen links to the source, the documentation and the
[privacy policy](PRIVACY.md).

The app follows the dashboard's look: monochrome, Manrope type, and black or
light grey to match the phone's theme. On a TV's dashboard the header and the
navigation bar take the page's own background, so they follow the dashboard's
theme setting too.

## Adding a TV

Scanning any QR code on the TV's dashboard adds that TV, token included: the
codes are links to the web dashboard and carry `k=<token>` when one is set.
Scanning a TV already in the list updates its token. A TV can also be added by
address; a bare host gets the server's default port, 8080.

The list, tokens included, stays in the app's private storage and is left out
of Android backups.

## Building

```sh
./gradlew testDebugUnitTest assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. The `android`
workflow builds the same on every push and pull request and keeps the APK as an artifact. Those builds are signed with each runner's own
debug key, so a newer one only installs after the older one is uninstalled,
which clears its list.

## Releasing

Release builds are signed with the Play upload key. Locally, a
`keystore.properties` beside this README names it (the file and any keystore
are gitignored):

```properties
storeFile=/path/to/upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

`./gradlew bundleRelease` then writes the bundle for Play to
`app/build/outputs/bundle/release/`.

The `release` workflow does the same on GitHub, from the secrets
`UPLOAD_KEYSTORE_BASE64` (the keystore, base64-encoded),
`UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS` and `UPLOAD_KEY_PASSWORD`.
Pushing a tag `v<versionName>` builds the bundle and an APK and attaches both
to a GitHub release; the tag must match `versionName`, and `versionCode` must
go up with every upload to Play. Running the workflow by hand builds the same
files as an artifact without a release.

## Platform notes

The dashboard is plain HTTP on a LAN address, so the network security config
allows cleartext everywhere: a domain list cannot name every private address.

Links to other sites open in the phone's browser; only the TV's own origin
stays in the app. The app adds `Glasshouse-Android/<version>` to the WebView's
user agent.

WebView timers are paused while the app is in the background, which stops the
dashboard's polling.
