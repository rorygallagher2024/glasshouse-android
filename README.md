# Glasshouse for Android

A phone app for [Glasshouse](https://github.com/rorygallagher2024/lg-webos-dashboard),
the dashboard for rooted LG webOS TVs. It keeps a list of TVs and opens each
one's web dashboard full screen. The dashboard itself is the same page a browser gets;
the app adds the list, switching between TVs, and the file picker the
sideload tab needs.

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

## Platform notes

The dashboard is plain HTTP on a LAN address, so the network security config
allows cleartext everywhere: a domain list cannot name every private address.

Links to other sites open in the phone's browser; only the TV's own origin
stays in the app. The app adds `Glasshouse-Android/<version>` to the WebView's
user agent.

WebView timers are paused while the app is in the background, which stops the
dashboard's polling.
