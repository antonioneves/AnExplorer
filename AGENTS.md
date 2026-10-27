# AnExplorer

An Android file manager that now targets **Android TV only**. Every change is designed for D-pad and remote control and tested on a TV. Phone and tablet layouts aren't a goal.

## Layout
- `app/`: the app (Java). Package `dev.dworks.apps.anexplorer`.
  - `provider/`: DocumentsProvider-style storage backends, one per storage type: external, USB, media, network, cloud, recents and so on.
  - `network/`: the network clients, all extending `NetworkClient`: FTP, FTPS (which extends FTP), SFTP and WebDAV. Saved connections are `NetworkConnection` rows in `ExplorerProvider`'s database.
  - `fragment/`: the screens (`DirectoryFragment`, `HomeFragment`, `CreateConnectionFragment`, `QuickShareFragment`, …). `DocumentsActivity` is the main activity.
  - `misc/`: helpers. `DocumentCopier` copies and moves between different storages; `RootsCache` holds the list of roots.
- `protocol/`: the Quick Share (Nearby Share) protocol: protobuf definitions plus framing. This is the only module with unit tests.
- `web/`: the website. It isn't part of the app build.

The SDK levels and version are set in the root `build.gradle` (`ext`). `minSdk` is 23 and `compileSdk`/`targetSdk` is 37.

## Build and test
- Debug build: `./gradlew -q :app:assembleDebug`, then `adb -s <serial> install -r app/build/outputs/apk/debug/*.apk`.
- Protocol tests: `./gradlew :protocol:test`.
- The release build is minified with R8. `keystore.properties` holds the signing secrets: never print it, log it or commit changes to it.
- To check a release build on a device that has the debug build: re-sign a copy of the release APK with `~/.android/debug.keystore` (alias `androiddebugkey`, password `android`) using `apksigner`, then install it over the debug build.
- Verify changes on a running TV: the Android TV emulator (`emulator-5554`) or a real TV over adb. Drive the TV with `adb shell input keyevent` (D-pad), and check focus as well as looks.

## Conventions
- Match the surrounding code: plain Java, short Javadoc saying *why*, and few comments.
- Every user-facing string goes in `res/values/strings.xml`. Don't hard-code text.
- Check new UI in both the dark and light themes.
- Network operations that change files must not lose data. For a move, delete the source only after the copy is confirmed complete.
- Bump `ExplorerProvider`'s database version when its schema changes, and add a migration.

## Git
- Commit and push to `origin` after each verified change. Never push to `upstream` (the original DWorkS repo).
- Write commit messages as a plain sentence about what changed for the user, for example "Show the newest arrivals first in Recents".

## Devices
- Real TVs and phones on the network can hold personal data. Don't open, copy or delete user files on them. Don't format or erase drives unless explicitly asked.
- If a TV was asleep before a test, put it back to sleep afterwards (`adb shell input keyevent SLEEP`).
