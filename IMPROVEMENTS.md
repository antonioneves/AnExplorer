# Improvements

What's left to do, found while fixing the TV focus bugs. The app targets Android TV only.

## Features
- Stream video from network storage (SMB, FTP, WebDAV) without copying it first.
- An image slideshow, started from a folder of photos.
- Voice search with the remote's microphone button.
- A storage analyzer showing which folders take the most space.
- Background transfers with a notification, and pause and resume.
- A web page served alongside the FTP server, so a phone can upload files to the TV from its browser.
- A history of Quick Share transfers.

## Quality
- Run a focus test on an Android TV emulator in CI, driving the D-pad and checking where focus lands.
- Set the release secrets in GitHub (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) so the release workflow can sign builds.
- Audit every move path to confirm the source is deleted only after the copy is confirmed complete.
- More unit tests: the sidebar grouping in `RootsExpandableAdapter.processRoots` needs a `Context` for its titles; passing the titles in would make it testable.
- Show a friendlier message when listing an SMB server's shares fails, instead of the raw smbj error.
- Translations: the strings added recently are English only.

## Retest on the real TVs
Update the Xiaomi and Grundig TVs to the latest build, then check:
- SMB browsing, copy and move.
- Starred, and the bookmark and starred badges.
- Signing in to the cloud services with the build's keys.
- The Menu key opening an item's menu.
- Done or Enter creating and renaming.
- The empty states of Starred and the Recycle Bin.
- Focus staying in Starred after unstarring.
- The interface text moved into string resources.
- Right from the sidebar moving into the file list.

## Untested
- FTPS on port 990 (implicit TLS) and TLS session reuse.
- An SFTP server whose host key changed.
- The ADB storage dialog.
- A custom Quick Share download folder, and receiving text over Quick Share.
- The APKs sidebar entry.
- Right and Left from the sidebar in a right-to-left language.
- Unstarring one of two Starred items.

## Known issues
- **APKs: Up from the app tabs goes to the view toggle in the toolbar.** Left as it is, because it's the only way to reach the toolbar from there. An explicit `nextFocusUp` could point it somewhere better.
- **Empty sidebar after switching theme**: reported, but didn't reproduce. The Network & Cloud group was once seen briefly empty while the roots loaded.
- After a theme switch recreates the screen, focus lands on the `settings_scroll` ScrollView.
- After opening a storage from a Home card, focus goes to the sidebar instead of the list.
- In the nested Home gallery, Down doesn't always move to the next row.
- Focus goes back to the sidebar after tapping the toolbar with touch.
- After a touch tap, the first D-pad key only leaves touch mode.
- A touch long-press behaves oddly in the file list.
- Home shortcut cards have no long-press or Menu action, and Menu in the sidebar does nothing.
- Starred is lost when a file is renamed or moved.
- The Recycle Bin and WebDAV have limits (no bin on network storage).
- The Quick Share Stop button isn't aligned with the rest of the screen.
- The System root's icon and its place in the sort order.
- The search box's close animation.
- The Locked folder is opt-in (the `lockedFolder` setting, off by default).
- `RootsCache` logs a NullPointerException for the recents provider (`Cursor.moveToNext()` on a null cursor).
- The app's first load on the emulator is slow.

## Development notes
- `uiautomator dump` can report stale focus. `adb shell dumpsys activity top` and the view with the `F` flag is more reliable.
- The Television_720p AVD could use less RAM.
