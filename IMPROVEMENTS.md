# Improvements

What's left to do, found while fixing the TV focus bugs. The app targets Android TV only.

## Features
None left.

## Quality
- Run a focus test on an Android TV emulator in CI, driving the D-pad and checking where focus lands.
- Set the release secrets in GitHub (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) so the release workflow can sign builds.
- Move across storages: check on a TV that a folder whose listing fails is kept, and that a move to or from a server keeps the source when the copy fails (fixed in code, only a local copy tested).
- More unit tests: the sidebar grouping in `RootsExpandableAdapter.processRoots` can't run under Robolectric, because it loads the app's manifest and that declares the platform permission `MANAGE_DOCUMENT`; it needs a test manifest without it, or the grouping moved out of the adapter.
- SMB: check the new messages for a refused login and a server that won't list its shares (built, not seen on a real server).
- Install an update from Settings: after the download the app asks "Install it now?" and opens the system installer (built; no release exists on GitHub yet, so the whole path from the check to the installer was not run).
- Transfer queue: with one copy running, a second paste waits and the paste bar and the notification list what is waiting (seen on the emulator, two 400 MB copies in the same storage). Copies inside local storage are now read and written by the app so the bar shows a percentage; that's slower than the provider's own copy on a very slow drive.
- Translations: the strings added recently are English only.
- Trakt: real sign-in, the code screen, matching and both-way sync are untested, and Trakt's current terms and limits haven't been checked.
- `DocumentsActivity` (2,446 lines) and `DirectoryFragment` (2,100) are still big. The background tasks, voice search and episode selection were moved out; what's left (`styleTvSearchView`, the toolbar action mode, the show-folder adapter wiring, the long menu handlers) is tied to the activity's and fragment's state and needs a wider rewrite. Episode selection in a show folder was only built, not run: the emulator has no show folder.
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
- Subtitle download (SubDL, then OpenSubtitles): built and the button hides without keys, but no service was called; it needs real keys. The VLC player has the same button.
- Sleep timer: the dialog opens, but its pause after the delay wasn't waited out. The media session was seen active with ExoPlayer only, not with VLC. Speed and audio/subtitle tracks in VLC: the menu shows, a change on a real VLC-only file isn't tested.
- DLNA/UPnP media servers: browsing, names and file opening worked against a fake server on the emulator; the SSDP search, a real server (Plex, Jellyfin, a router's USB share), seeking while streaming and a very long list weren't tried. A server on the VLC fallback: VLC opens the file on the main thread, which fails for any network storage.
- Find servers on the network: the menu entry and the "none found" message work on the emulator, but a real server was never seen; the list, the filled-in form and a connection made from it need a TV on a LAN with an SMB/FTP server that announces itself over mDNS (Windows shares usually don't).
- Background transfers: copying from a DLNA server (fake, throttled, on the emulator) showed the percentage, paused, resumed to the exact size and cancelled without leaving a half file. The notification (progress, Pause/Resume/Cancel, result), moves, folders, other servers, leaving the app mid-copy and a pause longer than the server's timeout weren't tried. Copies within one storage go through the provider and show no percentage.
- Web upload page (FTP server screen shows `http://<ip>:8080`): on the emulator a script uploaded several files in one go, including 3 MB that matched byte for byte, got `name (1).ext` for a clash and saved `../evil.txt` as `evil.txt` in the shared folder; Stop closed the port. A real phone's browser (the page, its progress bars, drag and drop), the login prompt for a non-anonymous server, a very large file and two uploads at once weren't tried. The page also browses subfolders, downloads files and uploads into the folder you are in (checked with a script: listing, byte-for-byte download, upload into a subfolder, and `..`, `%2e%2e` and absolute paths all stay inside the shared folder or get 404). It shows a note that the page is not encrypted. The page itself wasn't looked at in a browser.
- Streaming from network storage: a video from a DLNA server (fake, on the emulator) plays and seeks, including a file with its index at the end. FTP, SFTP, SMB and WebDAV share the same code but were not tried with a video, nor was a copy or move from a server (it now reads through the same seekable descriptor), nor a slow connection. On Android 7 and older (before 8.0) the file is still piped and can't be seeked.
- Update check: Settings > About > Check for updates. Against a fake GitHub on the emulator it showed the notes and downloaded the right APK for the CPU into Downloads; against the real repo (no release yet) it says it's the latest. The workflow now publishes a release when a tag like `v4.1.0` is pushed, which hasn't run yet; the first tag needs to match `versionName`. Installing is still by opening the downloaded file.
- FTPS on port 990 (implicit TLS) and TLS session reuse.
- An SFTP server whose host key changed.
- The ADB storage dialog (the emulator has the Settings screen, so it only shows on a TV that lacks it).
- Receiving files into a custom Quick Share folder, and receiving text over Quick Share (choosing the folder works, tested on the emulator; it needs a phone to send).
