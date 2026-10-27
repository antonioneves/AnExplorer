package dev.dworks.apps.anexplorer.misc;

import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import java.util.HashMap;
import java.util.Locale;

public final class MimeTypes {

    public static final String ALL_MIME_TYPES = "*/*";
    private static final HashMap<String, String> MIME_TYPES = new HashMap<String, String>();
    public static final String BASIC_MIME_TYPE = "application/octet-stream";

    private MimeTypes() {}

    static {
        MIME_TYPES.put("asm", "text/x-asm");
        MIME_TYPES.put("def", "text/plain");
        MIME_TYPES.put("in", "text/plain");
        MIME_TYPES.put("rc", "text/plain");
        MIME_TYPES.put("list", "text/plain");
        MIME_TYPES.put("log", "text/plain");
        MIME_TYPES.put("pl", "text/plain");
        MIME_TYPES.put("prop", "text/plain");
        MIME_TYPES.put("properties", "text/plain");
        MIME_TYPES.put("rc", "text/plain");

        MIME_TYPES.put("epub", "application/epub+zip");
        MIME_TYPES.put("ibooks", "application/x-ibooks+zip");

        MIME_TYPES.put("ifb", "text/calendar");
        MIME_TYPES.put("eml", "message/rfc822");
        MIME_TYPES.put("msg", "application/vnd.ms-outlook");

        MIME_TYPES.put("ace", "application/x-ace-compressed");
        MIME_TYPES.put("bz", "application/x-bzip");
        MIME_TYPES.put("bz2", "application/x-bzip2");
        MIME_TYPES.put("cab", "application/vnd.ms-cab-compressed");
        MIME_TYPES.put("gz", "application/x-gzip");
        MIME_TYPES.put("lrf", BASIC_MIME_TYPE);
        MIME_TYPES.put("jar", "application/java-archive");
        MIME_TYPES.put("xz", "application/x-xz");
        MIME_TYPES.put("Z", "application/x-compress");

        MIME_TYPES.put("bat", "application/x-msdownload");
        MIME_TYPES.put("ksh", "text/plain");
        MIME_TYPES.put("sh", "application/x-sh");

        MIME_TYPES.put("db", BASIC_MIME_TYPE);
        MIME_TYPES.put("db3", BASIC_MIME_TYPE);

        MIME_TYPES.put("otf", "application/x-font-otf");
        MIME_TYPES.put("ttf", "application/x-font-ttf");
        MIME_TYPES.put("psf", "application/x-font-linux-psf");

        MIME_TYPES.put("cgm", "image/cgm");
        MIME_TYPES.put("btif", "image/prs.btif");
        MIME_TYPES.put("dwg", "image/vnd.dwg");
        MIME_TYPES.put("dxf", "image/vnd.dxf");
        MIME_TYPES.put("fbs", "image/vnd.fastbidsheet");
        MIME_TYPES.put("fpx", "image/vnd.fpx");
        MIME_TYPES.put("fst", "image/vnd.fst");
        MIME_TYPES.put("mdi", "image/vnd.ms-mdi");
        MIME_TYPES.put("npx", "image/vnd.net-fpx");
        MIME_TYPES.put("xif", "image/vnd.xiff");
        MIME_TYPES.put("pct", "image/x-pict");
        MIME_TYPES.put("pic", "image/x-pict");

        MIME_TYPES.put("adp", "audio/adpcm");
        MIME_TYPES.put("au", "audio/basic");
        MIME_TYPES.put("snd", "audio/basic");
        MIME_TYPES.put("m2a", "audio/mpeg");
        MIME_TYPES.put("m3a", "audio/mpeg");
        MIME_TYPES.put("oga", "audio/ogg");
        MIME_TYPES.put("spx", "audio/ogg");
        MIME_TYPES.put("aac", "audio/x-aac");
        MIME_TYPES.put("mka", "audio/x-matroska");

        MIME_TYPES.put("jpgv", "video/jpeg");
        MIME_TYPES.put("jpgm", "video/jpm");
        MIME_TYPES.put("jpm", "video/jpm");
        MIME_TYPES.put("mj2", "video/mj2");
        MIME_TYPES.put("mjp2", "video/mj2");
        MIME_TYPES.put("mpa", "video/mpeg");
        MIME_TYPES.put("ogv", "video/ogg");
        MIME_TYPES.put("flv", "video/x-flv");
        MIME_TYPES.put("mkv", "video/x-matroska");

        // Formats Nova Video Player plays; the platform map lacks most of them
        MIME_TYPES.put("ts", "video/mp2t");
        MIME_TYPES.put("m2ts", "video/mp2t");
        MIME_TYPES.put("mts", "video/mp2t");
        MIME_TYPES.put("trp", "video/mp2t");
        MIME_TYPES.put("vob", "video/mpeg");
        MIME_TYPES.put("m2v", "video/mpeg");
        MIME_TYPES.put("ps", "video/mp2p");
        MIME_TYPES.put("f4v", "video/x-f4v");
        MIME_TYPES.put("wmv", "video/x-ms-wmv");
        MIME_TYPES.put("dvr-ms", "video/x-ms-wmv");
        MIME_TYPES.put("asf", "video/x-ms-asf");
        MIME_TYPES.put("rmvb", "video/vnd.rn-realvideo");
        MIME_TYPES.put("rm", "video/vnd.rn-realvideo");
        MIME_TYPES.put("divx", "video/x-msvideo");
        MIME_TYPES.put("ogm", "video/ogg");
        MIME_TYPES.put("wtv", "video/wtv");
        MIME_TYPES.put("amv", "video/x-amv");
        MIME_TYPES.put("h264", "video/h264");
        MIME_TYPES.put("264", "video/h264");
        MIME_TYPES.put("mpg4", "video/mp4");
        MIME_TYPES.put("mpeg4", "video/mp4");
        MIME_TYPES.put("3gp", "video/3gpp");
        MIME_TYPES.put("3g2", "video/3gpp2");

        MIME_TYPES.put("ac3", "audio/ac3");
        MIME_TYPES.put("eac3", "audio/eac3");
        MIME_TYPES.put("dts", "audio/vnd.dts");
        MIME_TYPES.put("opus", "audio/ogg");
        MIME_TYPES.put("ape", "audio/x-ape");
        MIME_TYPES.put("wv", "audio/x-wavpack");
        MIME_TYPES.put("dsf", "audio/x-dsf");
        MIME_TYPES.put("wma", "audio/x-ms-wma");
        MIME_TYPES.put("m4b", "audio/mp4");
        MIME_TYPES.put("adts", "audio/aac");
        MIME_TYPES.put("mid", "audio/midi");
        MIME_TYPES.put("midi", "audio/midi");
    }

    public static String getMimeTypeFromExtension(String extension) {
        String type = null;

        if (!TextUtils.isEmpty(extension)) {
            final String extensionLowerCase = extension.toLowerCase(Locale.getDefault());
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionLowerCase);
            if (type == null) {
                //extra mime types map
                type = MIME_TYPES.get(extensionLowerCase);
            }
        }
        return type;
    }
}