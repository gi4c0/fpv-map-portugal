# FPV Map Portugal

Android app showing Portuguese UAS (drone) geographical zones on a map, so FPV pilots can check where they may fly.

- Zones from ANAC's official ED-318 feed, including AAN temporary no-fly zones (NOTAM).
- Tap anywhere to see every zone covering that point: restriction, altitude limits, schedule, required notice and authority contacts, in English.
- Search by zone ID, zone name or place name.
- Works offline: ships with a bundled snapshot, keeps the last downloaded feed, and refreshes on launch.
- Shows your location; adapts to foldables, portrait and landscape.

## Data

Zones come from https://dnt.anac.pt/index.php?action=download-json. Expired zones are hidden; upcoming ones are shown. The basemap is [OpenFreeMap](https://openfreemap.org/).

This app is an aid, not an authoritative source. Always confirm restrictions with ANAC and AAN before flying.

## Build

Requires the Android SDK (compile SDK 37) and JDK 17+. The app runs on Android 10 (API 29) and later.

```sh
./gradlew assembleDebug        # build debug APK
./gradlew installDebug         # install on a connected device
./gradlew testDebugUnitTest    # run unit tests
```

`assembleRelease` signs with the debug key. Configure your own keystore in `app/build.gradle.kts` before distributing.
