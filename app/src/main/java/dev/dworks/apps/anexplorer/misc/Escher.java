package dev.dworks.apps.anexplorer.misc;

import static dev.dworks.apps.anexplorer.misc.BinaryOffice.u16;
import static dev.dworks.apps.anexplorer.misc.BinaryOffice.u32;

import android.util.Base64;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OfficeArt ("Escher") records, the drawing layer that legacy Word and PowerPoint files share
 * ([MS-ODRAW]): walking the record tree, reading a shape's property table and pulling the
 * displayable pictures out of the picture store. Follows the same part of Paperwren (MIT,
 * Copyright (c) 2026 Paperwren contributors).
 */
final class Escher {

    static final int BSTORE = 0xF001;
    static final int DG = 0xF002;
    static final int SPGR_CONTAINER = 0xF003;
    static final int SP_CONTAINER = 0xF004;
    static final int BSE = 0xF007;
    static final int SPGR = 0xF009;
    static final int SP = 0xF00A;
    static final int OPT = 0xF00B;
    static final int CLIENT_TEXTBOX = 0xF00D;
    static final int CHILD_ANCHOR = 0xF00F;
    static final int CLIENT_ANCHOR = 0xF010;
    static final int CLIENT_DATA = 0xF011;
    static final int TERTIARY_OPT = 0xF122;
    static final int BLIP_EMF = 0xF01A;
    static final int BLIP_WMF = 0xF01B;

    private Escher() {
    }

    /** One record header: the low 4 bits of {@link #ver} are 0xF for a container */
    static final class Rec {
        final int ver, inst, type, body, end;

        Rec(int ver, int inst, int type, int body, int end) {
            this.ver = ver;
            this.inst = inst;
            this.type = type;
            this.body = body;
            this.end = end;
        }
    }

    /** @return the records directly inside [start, end), stopping at the first that would run past it */
    static List<Rec> children(byte[] b, int start, int end) {
        final List<Rec> out = new ArrayList<>();
        int pos = start;
        while (pos + 8 <= end) {
            final int head = u16(b, pos);
            final long length = u32(b, pos + 4) & 0xFFFFFFFFL;
            final int body = pos + 8;
            if (body + length > end) {
                break;
            }
            out.add(new Rec(head & 0xF, head >> 4, u16(b, pos + 2), body, (int) (body + length)));
            pos = (int) (body + length);
        }
        return out;
    }

    /** Adds every record of that type under [start, end), at any depth */
    static void descendants(byte[] b, int start, int end, int type, List<Rec> out, int depth) {
        if (depth > 24) {
            return;
        }
        for (Rec rec : children(b, start, end)) {
            if (rec.type == type) {
                out.add(rec);
            } else if (rec.ver == 0xF) {
                descendants(b, rec.body, rec.end, type, out, depth + 1);
            }
        }
    }

    static Rec find(List<Rec> recs, int type) {
        for (Rec rec : recs) {
            if (rec.type == type) {
                return rec;
            }
        }
        return null;
    }

    /** A shape's simple properties (OfficeArtFOPT); complex properties are skipped */
    static void readProps(byte[] b, Rec rec, Map<Integer, Integer> into) {
        for (int i = 0; i < rec.inst; i++) {
            final int at = rec.body + i * 6;
            if (at + 6 > rec.end) {
                break;
            }
            final int head = u16(b, at);
            if ((head & 0x8000) == 0) {
                into.put(head & 0x3FFF, u32(b, at + 2));
            }
        }
    }

    /**
     * The picture store: one [offset in the delay stream or -1, size, embedded body, embedded end]
     * entry per picture, in blip-index order.
     */
    static List<int[]> blipStore(byte[] b, int start, int end) {
        final List<int[]> out = new ArrayList<>();
        final List<Rec> stores = new ArrayList<>();
        descendants(b, start, end, BSTORE, stores, 0);
        if (stores.isEmpty()) {
            return out;
        }
        for (Rec rec : children(b, stores.get(0).body, stores.get(0).end)) {
            if (rec.type != BSE) {
                // Some writers put the picture directly in the store
                out.add(new int[]{-1, rec.end - rec.body, rec.body, rec.end});
                continue;
            }
            final int nameLength = b[rec.body + 33] & 0xFF;
            final List<Rec> inner = children(b, rec.body + 36 + nameLength, rec.end);
            out.add(new int[]{u32(b, rec.body + 28), u32(b, rec.body + 20),
                    inner.isEmpty() ? -1 : inner.get(0).body, inner.isEmpty() ? -1 : inner.get(0).end});
        }
        return out;
    }

    /**
     * @param container the stream the store was read from
     * @param delay     the stream that holds pictures not embedded in the store, or null
     * @return the picture with that 1-based store index as a data URI, or null if it cannot be shown
     */
    static String storedImage(List<int[]> store, int index, byte[] container, byte[] delay) {
        if (index < 1 || index > store.size()) {
            return null;
        }
        final int[] entry = store.get(index - 1);
        if (entry[2] >= 0) {
            return blip(container, entry[2], entry[3]);
        }
        if (delay != null && entry[0] >= 0 && entry[0] + 8L <= delay.length) {
            final long length = u32(delay, entry[0] + 4) & 0xFFFFFFFFL;
            final int body = entry[0] + 8;
            return blip(delay, body, (int) Math.min(delay.length, body + length));
        }
        return null;
    }

    /** CSS for a two-colour gradient fill (the shaded fill types), or null if the fill is not one */
    static String gradient(Map<Integer, Integer> props, String fore, String back) {
        final Integer type = props.get(0x0180);
        if (type == null || type < 4 || type > 7) {
            return null;
        }
        if (type == 5 || type == 6) {
            return "radial-gradient(" + fore + "," + back + ")";
        }
        final Integer angle = props.get(0x018B);
        // OfficeArt measures clockwise from the left-to-right direction, CSS from the top
        return "linear-gradient(" + (90 + (angle == null ? 0 : angle / 65536.0)) + "deg," + fore + "," + back + ")";
    }

    /** CSS (with a leading semicolon) that cuts a box to the preset geometry of that shape type, or "" */
    static String geometry(int type, double minSide) {
        switch (type) {
            case 3:
                return ";border-radius:50%";
            case 2:
                return ";border-radius:" + String.format(java.util.Locale.ROOT, "%.2f", minSide * 0.1667) + "px";
            case 4:
                return ";clip-path:polygon(50% 0,100% 50%,50% 100%,0 50%)";
            case 5:
                return ";clip-path:polygon(50% 0,100% 100%,0 100%)";
            case 6:
                return ";clip-path:polygon(0 0,100% 100%,0 100%)";
            case 13:
                return ";clip-path:polygon(0 25%,60% 25%,60% 0,100% 50%,60% 100%,60% 75%,0 75%)";
            case 15:
                return ";clip-path:polygon(0 0,75% 0,100% 50%,75% 100%,0 100%)";
            case 55:
                return ";clip-path:polygon(0 0,75% 0,100% 50%,75% 100%,0 100%,25% 50%)";
            default:
                return "";
        }
    }

    /** The image inside a BLIP record body: a bitmap found by its signature, or a drawn metafile */
    private static String blip(byte[] b, int body, int end) {
        final int type = body >= 8 ? u16(b, body - 6) : 0;
        if (type == BLIP_EMF || type == BLIP_WMF) {
            return metafile(b, body, end, (u16(b, body - 8) >> 4) == (type == BLIP_EMF ? 0x3D5 : 0x217));
        }
        for (int i = body + 16; i < Math.min(end, body + 60); i++) {
            final String mime = sniff(b, i, end);
            if (mime != null) {
                return "data:" + mime + ";base64," + Base64.encodeToString(b, i, end - i, Base64.NO_WRAP);
            }
        }
        return null;
    }

    /** An EMF or WMF BLIP: one or two UIDs, a 34-byte header, then the picture, usually deflated */
    private static String metafile(byte[] b, int body, int end, boolean twoUids) {
        final int head = body + (twoUids ? 32 : 16);
        if (head + 34 > end) {
            return null;
        }
        final int size = u32(b, head), packed = u32(b, head + 28), compression = b[head + 32] & 0xFF;
        final int data = head + 34;
        byte[] picture;
        if (compression == 0) {
            if (size <= 0 || size > 32 * 1024 * 1024) {
                return null;
            }
            final java.util.zip.Inflater inflater = new java.util.zip.Inflater();
            try {
                inflater.setInput(b, data, Math.min(packed > 0 ? packed : end - data, end - data));
                picture = new byte[size];
                int got = 0;
                while (got < size && !inflater.finished()) {
                    final int n = inflater.inflate(picture, got, size - got);
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                        break;
                    }
                    got += n;
                }
                if (got < size) {
                    picture = java.util.Arrays.copyOf(picture, got);
                }
            } catch (java.util.zip.DataFormatException e) {
                return null;
            } finally {
                inflater.end();
            }
        } else {
            picture = java.util.Arrays.copyOfRange(b, data, end);
        }
        return Metafile.toSvg(picture);
    }

    /**
     * The first picture under [start, end) in a run of records, as a data URI, or null. Used where
     * a picture is stored beside its shape instead of in the picture store.
     */
    static String firstImage(byte[] b, int start, int end) {
        return firstImage(b, start, end, 0);
    }

    private static String firstImage(byte[] b, int start, int end, int depth) {
        if (depth > 6) {
            return null;
        }
        for (Rec rec : children(b, start, end)) {
            String found = null;
            if (rec.type >= 0xF018 && rec.type <= 0xF117 && rec.ver != 0xF) {
                found = blip(b, rec.body, rec.end);
            } else if (rec.type == BSE) {
                final int nameLength = rec.body + 33 < rec.end ? b[rec.body + 33] & 0xFF : 0;
                found = firstImage(b, rec.body + 36 + nameLength, rec.end, depth + 1);
            } else if (rec.ver == 0xF) {
                found = firstImage(b, rec.body, rec.end, depth + 1);
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String sniff(byte[] b, int i, int end) {
        if (i + 4 > end) {
            return null;
        }
        if ((b[i] & 0xFF) == 0x89 && b[i + 1] == 'P' && b[i + 2] == 'N' && b[i + 3] == 'G') {
            return "image/png";
        }
        if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xD8 && (b[i + 2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b[i] == 'G' && b[i + 1] == 'I' && b[i + 2] == 'F' && b[i + 3] == '8') {
            return "image/gif";
        }
        return null;
    }
}
