# Improvements

What's left to do, found while fixing the TV focus bugs. The app targets Android TV only.

## Features
- Stream video from network storage (SMB, FTP, WebDAV) without copying it first.
- Background transfers with a notification, and pause and resume.
- A web page served alongside the FTP server, so a phone can upload files to the TV from its browser.
- Continue Watching in the Android TV launcher's Watch Next row, built on the watch history.
- A media session in the players, so the remote's media keys, Assistant and the system "now playing" card work, plus playback speed and a sleep timer.
- Download subtitles for a video, using the title, season and episode already matched with TMDB.
- Find SMB and FTP servers on the network (`NsdManager`) instead of typing an address with the remote.
- Browse DLNA/UPnP media servers.
- A parental PIN that locks the app, hidden folders or deleting.
- Check for a new release on GitHub (the old update check was removed).

## Quality
- Run a focus test on an Android TV emulator in CI, driving the D-pad and checking where focus lands.
- Set the release secrets in GitHub (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) so the release workflow can sign builds.
- Move across storages: check on a TV that a folder whose listing fails is kept, and that a move to or from a server keeps the source when the copy fails (fixed in code, only a local copy tested).
- More unit tests: the sidebar grouping in `RootsExpandableAdapter.processRoots` needs a `Context` for its titles; passing the titles in would make it testable.
- SMB: check the new messages for a refused login and a server that won't list its shares (built, not seen on a real server).
- Translations: the strings added recently are English only.
- Trakt: real sign-in, the code screen, matching and both-way sync are untested, and Trakt's current terms and limits haven't been checked.
- Text fields: check the on-screen keyboard's left key still moves the cursor, and the Note editor, Save field and dark theme with the remote-focus changes.
- A unit test for `SortingCursorWrapper`; it needs Android's cursor classes, so it needs Robolectric or an instrumented test.
- Split `DocumentsActivity` and `DirectoryFragment` (each over 2,000 lines), starting with the search and toolbar code.
- Measure startup on the weakest TV (the Grundig) before optimizing it.

## Retest on the real TVs
Update the Xiaomi and Grundig TVs to the latest build, then check:
- SMB browsing, copy and move.
- Starred, and the bookmark and starred badges.
- Signing in to the cloud services with the build's keys.
- The Menu key opening an item's menu.
- Done or Enter creating and renaming.
- The empty state of the Recycle Bin.
- The Starred folder showing up with the first star and going with the last (focus lands on the sidebar afterwards).
- The interface text moved into string resources.
- Right from the sidebar moving into the file list.

## Untested
- FTPS on port 990 (implicit TLS) and TLS session reuse.
- An SFTP server whose host key changed.
- The ADB storage dialog.
- A custom Quick Share download folder, and receiving text over Quick Share.
- The APKs sidebar entry.
- Right and Left from the sidebar in a right-to-left language.
- Unstarring one of two Starred items, and focus in the list afterwards.
