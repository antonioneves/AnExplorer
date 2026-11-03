# Improvements

What's left to do, found while fixing the TV focus bugs. The app targets Android TV only.

## Features
- A web page served alongside the FTP server, so a phone can upload files to the TV from its browser.

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
- Subtitle download (SubDL, then OpenSubtitles): built and the button hides without keys, but no service was called; it needs real keys. The VLC player has the same button.
- Sleep timer: the dialog opens, but its pause after the delay wasn't waited out. The media session was seen active with ExoPlayer only, not with VLC. Speed and audio/subtitle tracks in VLC: the menu shows, a change on a real VLC-only file isn't tested.
- DLNA/UPnP media servers: browsing, names and file opening worked against a fake server on the emulator; the SSDP search, a real server (Plex, Jellyfin, a router's USB share), seeking while streaming and a very long list weren't tried. A server on the VLC fallback: VLC opens the file on the main thread, which fails for any network storage.
- Find servers on the network: the menu entry and the "none found" message work on the emulator, but a real server was never seen; the list, the filled-in form and a connection made from it need a TV on a LAN with an SMB/FTP server that announces itself over mDNS (Windows shares usually don't).
- Background transfers: copying from a DLNA server (fake, throttled, on the emulator) showed the percentage, paused, resumed to the exact size and cancelled without leaving a half file. The notification (progress, Pause/Resume/Cancel, result), moves, folders, other servers, leaving the app mid-copy and a pause longer than the server's timeout weren't tried. Copies within one storage go through the provider and show no percentage.
- Streaming from network storage: a video from a DLNA server (fake, on the emulator) plays and seeks, including a file with its index at the end. FTP, SFTP, SMB and WebDAV share the same code but were not tried with a video, nor was a copy or move from a server (it now reads through the same seekable descriptor), nor a slow connection. On Android 7 and older (before 8.0) the file is still piped and can't be seeked.
- Update check: Settings > About > Check for updates. Against a fake GitHub on the emulator it showed the notes and downloaded the right APK for the CPU into Downloads; against the real repo (no release yet) it says it's the latest. The workflow now publishes a release when a tag like `v4.1.0` is pushed, which hasn't run yet; the first tag needs to match `versionName`. Installing is still by opening the downloaded file.
- FTPS on port 990 (implicit TLS) and TLS session reuse.
- An SFTP server whose host key changed.
- The ADB storage dialog (the emulator has the Settings screen, so it only shows on a TV that lacks it).
- Receiving files into a custom Quick Share folder, and receiving text over Quick Share (choosing the folder works, tested on the emulator; it needs a phone to send).
