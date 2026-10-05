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

## Platform notes

The dashboard is plain HTTP on a LAN address, so the network security config
allows cleartext everywhere: a domain list cannot name every private address.

Links to other sites open in the phone's browser; only the TV's own origin
stays in the app. The app adds `Glasshouse-Android/<version>` to the WebView's
user agent.

A TV's dashboard is full screen: the system bars are hidden until swiped in
from an edge. The only control is a back button in the strip beside the
camera cutout, space the page could not use; on a screen without a cutout the
strip is the button's height. Switching TVs is done from the list.

WebView timers are paused while the app is in the background, which stops the
dashboard's polling.
