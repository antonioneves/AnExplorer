# Improvements

What's left to do, found while fixing the TV focus bugs. The app targets Android TV only.

## Features
- Stream video from network storage (SMB, FTP, WebDAV) without copying it first.
- Background transfers with a notification, and pause and resume.
- A web page served alongside the FTP server, so a phone can upload files to the TV from its browser.
- A media session in the players, so the remote's media keys, Assistant and the system "now playing" card work, plus playback speed and a sleep timer.
- Find SMB and FTP servers on the network (`NsdManager`) instead of typing an address with the remote.
- Browse DLNA/UPnP media servers.
- Check for a new release on GitHub (the old update check was removed).

## Quality
- Run a focus test on an Android TV emulator in CI, driving the D-pad and checking where focus lands.
- Set the release secrets in GitHub (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) so the release workflow can sign builds.
- Move across storages: check on a TV that a folder whose listing fails is kept, and that a move to or from a server keeps the source when the copy fails (fixed in code, only a local copy tested).
- More unit tests: the sidebar grouping in `RootsExpandableAdapter.processRoots` can't run under Robolectric, because it loads the app's manifest and that declares the platform permission `MANAGE_DOCUMENT`; it needs a test manifest without it, or the grouping moved out of the adapter.
- SMB: check the new messages for a refused login and a server that won't list its shares (built, not seen on a real server).
- Translations: the strings added recently are English only.
- Trakt: real sign-in, the code screen, matching and both-way sync are untested, and Trakt's current terms and limits haven't been checked.
- Split `DocumentsActivity` and `DirectoryFragment` (each over 2,000 lines), starting with the search and toolbar code.
- Measure startup on the weakest TV (the Grundig) before optimizing it.

## Retest on the real TVs
Update the Xiaomi and Grundig TVs to the latest build, then check:
- SMB browsing, copy and move.
- Starred, and the bookmark and starred badges.
- Signing in to the cloud services with the build's keys.
- The Menu key opening an item's menu.
- The empty state of the Recycle Bin.
- The interface text moved into string resources.
- Continue Watching in the launcher's Watch Next row: check the poster and that a video from a network storage resumes (only a local video was tested, on the emulator).
- Right from the sidebar moving into the file list.

## Untested
- Subtitle download (SubDL, then OpenSubtitles): built and the button hides without keys, but no service was called; it needs real keys. Also not available in the VLC player.
- FTPS on port 990 (implicit TLS) and TLS session reuse.
- An SFTP server whose host key changed.
- The ADB storage dialog (the emulator has the Settings screen, so it only shows on a TV that lacks it).
- Receiving files into a custom Quick Share folder, and receiving text over Quick Share (choosing the folder works, tested on the emulator; it needs a phone to send).
