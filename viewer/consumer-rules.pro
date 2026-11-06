# Media3 loads the ffmpeg renderers by reflection
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class org.jellyfin.media3.** { *; }

# libVLC is called from native code
-keep class org.videolan.libvlc.** { *; }
