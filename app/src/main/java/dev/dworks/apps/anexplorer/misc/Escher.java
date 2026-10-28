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

    /** The image inside a BLIP record body, found by its signature after the UIDs */
    private static String blip(byte[] b, int body, int end) {
        for (int i = body + 16; i < Math.min(end, body + 60); i++) {
            final String type = sniff(b, i, end);
            if (type != null) {
                return "data:" + type + ";base64," + Base64.encodeToString(b, i, end - i, Base64.NO_WRAP);
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
