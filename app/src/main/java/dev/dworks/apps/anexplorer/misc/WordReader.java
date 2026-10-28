package dev.dworks.apps.anexplorer.misc;

import android.util.Base64;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Word 97-2003 (.doc) file as HTML with its formatting: bold, italic, underline, colours,
 * sizes, fonts, alignment, headings, lists, tables with merged and shaded cells, inline pictures
 * and links. A Java port of the binary reader in Paperwren's doc.ts (MIT, Copyright (c) 2026
 * Paperwren contributors), which follows [MS-DOC]. Floating pictures are shown inline at their
 * anchor. Page geometry, headers, footnotes, drawn shapes and EMF/WMF pictures are not read: the
 * text flows instead of being paginated.
 */
public final class WordReader {

    private static final Charset ANSI = Charset.forName("windows-1252");
    private static final Charset UTF16 = Charset.forName("UTF-16LE");
    private static final String[] ICO = {null, "#000000", "#0000ff", "#00ffff", "#00ff00", "#ff00ff",
            "#ff0000", "#ffff00", "#ffffff", "#000080", "#008080", "#008000", "#800080", "#800000",
            "#808000", "#808080", "#c0c0c0"};
    private static final String[] ALIGN = {"left", "center", "right", "justify"};
    private static final Pattern LINK = Pattern.compile("HYPERLINK\\s+(?:\\\\l\\s+)?\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    private WordReader() {
    }

    /** One property modifier: its opcode and where its operand sits */
    private static final class Sprm {
        final int op;
        final int at;
        final int size;

        Sprm(int op, int at, int size) {
            this.op = op;
            this.at = at;
            this.size = size;
        }
    }

    /** Character formatting */
    private static final class Chp {
        boolean bold, italic, strike, underline, hidden, deleted, caps, special;
        int hps = 20;
        String color, highlight;
        int font = -1;
        int script;
        int pic = -1;
        int symbol = -1;

        Chp copy() {
            final Chp c = new Chp();
            c.bold = bold;
            c.italic = italic;
            c.strike = strike;
            c.underline = underline;
            c.hidden = hidden;
            c.deleted = deleted;
            c.caps = caps;
            c.special = special;
            c.hps = hps;
            c.color = color;
            c.highlight = highlight;
            c.font = font;
            c.script = script;
            return c;
        }
    }

    /** Paragraph formatting */
    private static final class Pap {
        int istd;
        String align;
        boolean inTable, ttp, innerCell, innerTtp, pageBreakBefore, rtl;
        int depth, ilvl, ilfo, indent;
        int[] edges;
        boolean[][] cells;
        String[] shades;

        Pap copy() {
            final Pap p = new Pap();
            p.istd = istd;
            p.align = align;
            p.inTable = inTable;
            p.ttp = ttp;
            p.innerCell = innerCell;
            p.innerTtp = innerTtp;
            p.pageBreakBefore = pageBreakBefore;
            p.rtl = rtl;
            p.depth = depth;
            p.ilvl = ilvl;
            p.ilfo = ilfo;
            p.indent = indent;
            return p;
        }
    }

    /** A range of file offsets with one set of properties */
    private static final class Run {
        int start, end, from, to, istd;
    }

    private static final class Style {
        int sti = 0xFFF;
        boolean paragraph;
        int base = 0xFFF;
        int[] pap, chp;
    }

    private static final class Piece {
        int cpStart, chars, fc;
        boolean wide;
    }

    private static final class Span {
        String text;
        Run run;
        String href;
        /** A floating picture anchored here, as an img tag; the span has no text then */
        String image;
    }

    private static final class Field {
        final StringBuilder instruction = new StringBuilder();
        boolean inResult;
        String href;
    }

    private static final class Para {
        String html;
        boolean empty;
        Pap pap;
        int mark;
    }

    private static final class Cell {
        final List<String> blocks;
        int colSpan = 1;
        int rowSpan = 1;
        int width;
        String fill;

        Cell(List<String> blocks) {
            this.blocks = blocks;
        }
    }

    private static int u16(byte[] b, int at) {
        return BinaryOffice.u16(b, at);
    }

    private static int i16(byte[] b, int at) {
        return (short) u16(b, at);
    }

    private static int u32(byte[] b, int at) {
        return BinaryOffice.u32(b, at);
    }

    private static int u8(byte[] b, int at) {
        return at < 0 || at >= b.length ? 0 : b[at] & 0xFF;
    }

    // ---- property modifiers ----

    /** Walks a grpprl; a malformed tail is dropped */
    private static List<Sprm> sprms(byte[] b, int start, int end) {
        final List<Sprm> out = new ArrayList<>();
        int pos = start;
        end = Math.min(end, b.length);
        while (pos + 2 <= end) {
            final int op = u16(b, pos);
            pos += 2;
            int size;
            switch (op >> 13) {
                case 0:
                case 1:
                    size = 1;
                    break;
                case 2:
                case 4:
                case 5:
                    size = 2;
                    break;
                case 3:
                    size = 4;
                    break;
                case 7:
                    size = 3;
                    break;
                default:
                    if (op == 0xD608 || op == 0xD606) {
                        size = u16(b, pos) - 1;
                        pos += 2;
                    } else if (op == 0xC615) {
                        size = u8(b, pos);
                        pos += 1;
                        if (size == 255) {
                            return out;
                        }
                    } else {
                        size = u8(b, pos);
                        pos += 1;
                    }
            }
            if (size < 0 || pos + size > end) {
                break;
            }
            out.add(new Sprm(op, pos, size));
            pos += size;
        }
        return out;
    }

    /** A toggle operand: 0 off, 1 on, 0x80 as the style, 0x81 its opposite */
    private static boolean toggle(int operand, boolean style) {
        return operand == 0x80 ? style : operand == 0x81 ? !style : operand != 0;
    }

    private static String colorRef(byte[] b, int at) {
        return u8(b, at + 3) == 0xFF ? null
                : String.format("#%02x%02x%02x", u8(b, at), u8(b, at + 1), u8(b, at + 2));
    }

    private static void applyChp(Chp chp, byte[] b, List<Sprm> list, Chp style) {
        for (Sprm s : list) {
            final int v = u8(b, s.at);
            switch (s.op) {
                case 0x0835:
                    chp.bold = toggle(v, style.bold);
                    break;
                case 0x0836:
                    chp.italic = toggle(v, style.italic);
                    break;
                case 0x0837:
                case 0x2A53:
                    chp.strike = toggle(v, style.strike);
                    break;
                case 0x083B:
                    chp.caps = toggle(v, style.caps);
                    break;
                case 0x083C:
                    chp.hidden = toggle(v, style.hidden);
                    break;
                case 0x0800:
                    chp.deleted = v != 0;
                    break;
                case 0x2A3E:
                    chp.underline = v != 0;
                    break;
                case 0x4A43:
                    chp.hps = u16(b, s.at) != 0 ? u16(b, s.at) : chp.hps;
                    break;
                case 0x2A42:
                    chp.color = v < ICO.length ? ICO[v] : null;
                    break;
                case 0x6870:
                    chp.color = colorRef(b, s.at);
                    break;
                case 0x2A0C:
                    chp.highlight = v > 0 && v < ICO.length ? ICO[v] : null;
                    break;
                case 0x4A4F:
                    chp.font = u16(b, s.at);
                    break;
                case 0x2A48:
                    chp.script = v;
                    break;
                case 0x0855:
                    chp.special = v != 0;
                    break;
                case 0x6A03:
                    chp.pic = u32(b, s.at);
                    break;
                case 0x6A09:
                    chp.font = u16(b, s.at);
                    chp.symbol = u16(b, s.at + 2);
                    break;
                default:
                    break;
            }
        }
    }

    private static void applyPap(Pap pap, byte[] b, List<Sprm> list) {
        for (Sprm s : list) {
            final int v = u8(b, s.at);
            switch (s.op) {
                case 0x2461:
                case 0x2403:
                    pap.align = v < ALIGN.length ? ALIGN[v] : pap.align;
                    break;
                case 0x2416:
                    pap.inTable = v != 0;
                    break;
                case 0x2417:
                    pap.ttp = v != 0;
                    break;
                case 0x244B:
                    pap.innerCell = v != 0;
                    break;
                case 0x244C:
                    pap.innerTtp = v != 0;
                    break;
                case 0x6649:
                    pap.depth = u32(b, s.at);
                    break;
                case 0x260A:
                    pap.ilvl = v;
                    break;
                case 0x460B:
                    pap.ilfo = u16(b, s.at);
                    break;
                case 0x840F:
                case 0x845E:
                    pap.indent = i16(b, s.at);
                    break;
                case 0x2407:
                    pap.pageBreakBefore = v != 0;
                    break;
                case 0x2441:
                    pap.rtl = v != 0;
                    break;
                case 0xD608: {
                    final int count = v;
                    if (count == 0 || 1 + (count + 1) * 2 > s.size) {
                        break;
                    }
                    final int[] edges = new int[count + 1];
                    for (int i = 0; i <= count; i++) {
                        edges[i] = i16(b, s.at + 1 + i * 2);
                    }
                    final boolean[][] cells = new boolean[count][];
                    final int tc = s.at + 1 + (count + 1) * 2;
                    for (int i = 0; i < count; i++) {
                        final int o = tc + i * 20;
                        final int flags = o + 2 <= s.at + s.size ? u16(b, o) : 0;
                        // first, merged, vertically merged, vertical restart
                        cells[i] = new boolean[]{(flags & 1) != 0, (flags & 2) != 0, (flags & 0x20) != 0,
                                (flags & 0x40) != 0};
                    }
                    pap.edges = edges;
                    pap.cells = cells;
                    break;
                }
                case 0xD612:
                case 0xD670: {
                    final List<String> shades = new ArrayList<>();
                    for (int o = s.at; o + 10 <= s.at + s.size; o += 10) {
                        final int pattern = u16(b, o + 8);
                        shades.add(pattern == 0xFFFF ? null : pattern == 1 ? colorRef(b, o) : colorRef(b, o + 4));
                    }
                    pap.shades = shades.toArray(new String[0]);
                    break;
                }
                default:
                    break;
            }
        }
    }

    // ---- property run tables ----

    /** Reads every FKP page listed by a PlcfBte{Chpx,Papx} */
    private static List<Run> readRuns(byte[] word, byte[] table, int fc, int lcb, boolean paragraphs) {
        final List<Run> runs = new ArrayList<>();
        if (lcb < 12 || fc < 0 || fc + lcb > table.length) {
            return runs;
        }
        final int n = (lcb - 4) / 8;
        for (int i = 0; i < n; i++) {
            final int page = (u32(table, fc + (n + 1) * 4 + i * 4) & 0x3FFFFF) * 512;
            if (page < 0 || page + 512 > word.length) {
                continue;
            }
            final int count = u8(word, page + 511);
            final int boxes = page + (count + 1) * 4;
            for (int k = 0; k < count; k++) {
                final Run run = new Run();
                run.start = u32(word, page + k * 4);
                run.end = u32(word, page + (k + 1) * 4);
                final int offset = u8(word, boxes + k * (paragraphs ? 13 : 1)) * 2;
                if (offset > 0) {
                    int at = page + offset;
                    int cb = u8(word, at++);
                    if (paragraphs) {
                        if (cb == 0) {
                            cb = u8(word, at++) * 2;
                        } else {
                            cb = cb * 2 - 1;
                        }
                        run.istd = u16(word, at);
                        run.from = at + 2;
                    } else {
                        run.from = at;
                    }
                    run.to = Math.min(at + cb, page + 511);
                }
                if (run.end > run.start) {
                    runs.add(run);
                }
            }
        }
        Collections.sort(runs, (a, b) -> Integer.compare(a.start, b.start));
        return runs;
    }

    private static Run runAt(List<Run> runs, int fc) {
        int lo = 0;
        int hi = runs.size() - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            final Run r = runs.get(mid);
            if (fc < r.start) {
                hi = mid - 1;
            } else if (fc >= r.end) {
                lo = mid + 1;
            } else {
                return r;
            }
        }
        return null;
    }

    // ---- style sheet, lists, fonts ----

    private static List<Style> readStyles(byte[] table, int fc, int lcb) {
        final List<Style> styles = new ArrayList<>();
        if (lcb < 4 || fc < 0 || fc + lcb > table.length) {
            return styles;
        }
        final int end = fc + lcb;
        final int cbStshi = u16(table, fc);
        final int stshi = fc + 2;
        final int count = u16(table, stshi);
        final int baseSize = u16(table, stshi + 2);
        int pos = stshi + cbStshi;
        for (int i = 0; i < count && pos + 2 <= end; i++) {
            final int cbStd = u16(table, pos);
            final int std = pos + 2;
            pos = std + cbStd;
            if (cbStd == 0 || pos > end) {
                styles.add(new Style());
                continue;
            }
            final Style style = new Style();
            style.sti = u16(table, std) & 0x0FFF;
            final int second = u16(table, std + 2);
            final int kind = second & 0x000F;
            style.base = second >> 4;
            style.paragraph = kind == 1;
            final int cupx = u16(table, std + 4) & 0x000F;
            int at = std + baseSize;
            at += 2 + (u16(table, at) + 1) * 2;
            for (int k = 0; k < cupx && at + 2 <= pos; k++) {
                if (((at - std) & 1) != 0) {
                    at++;
                }
                final int cb = u16(table, at);
                final int body = at + 2;
                at = body + cb;
                if (at > pos) {
                    break;
                }
                if (kind == 1 && k == 0) {
                    style.pap = new int[]{body + 2, at};
                } else if (kind == 1 && k == 1 || kind == 2 && k == 0) {
                    style.chp = new int[]{body, at};
                }
            }
            styles.add(style);
        }
        return styles;
    }

    /** The number format of each level, per list override */
    private static Map<Integer, int[]> readLists(byte[] table, int fcLst, int lcbLst, int fcLfo, int lcbLfo) {
        final Map<Integer, int[]> byLfo = new HashMap<>();
        if (lcbLst < 2 || lcbLfo < 4 || fcLst < 0 || fcLfo < 0) {
            return byLfo;
        }
        try {
            final int count = u16(table, fcLst);
            final int[] ids = new int[count];
            final int[] levels = new int[count];
            int pos = fcLst + 2;
            for (int i = 0; i < count; i++) {
                ids[i] = u32(table, pos);
                levels[i] = (u8(table, pos + 26) & 1) != 0 ? 1 : 9;
                pos += 28;
            }
            final Map<Integer, int[]> formats = new HashMap<>();
            for (int i = 0; i < count; i++) {
                final int[] nfcs = new int[levels[i]];
                for (int l = 0; l < levels[i]; l++) {
                    nfcs[l] = u8(table, pos + 4);
                    pos += 28 + u8(table, pos + 25) + u8(table, pos + 24);
                    pos += 2 + u16(table, pos) * 2;
                }
                formats.put(ids[i], nfcs);
            }
            final int lfoCount = u32(table, fcLfo);
            for (int i = 0; i < lfoCount && i < 4096; i++) {
                final int[] nfcs = formats.get(u32(table, fcLfo + 4 + i * 16));
                if (nfcs != null) {
                    byLfo.put(i + 1, nfcs);
                }
            }
        } catch (RuntimeException e) {
            // Lists degrade to plain bullets
        }
        return byLfo;
    }

    private static List<String> readFonts(byte[] table, int fc, int lcb) {
        final List<String> fonts = new ArrayList<>();
        if (lcb < 4 || fc < 0 || fc + lcb > table.length) {
            return fonts;
        }
        final int count = u16(table, fc);
        int pos = fc + 4;
        for (int i = 0; i < count && pos < fc + lcb; i++) {
            final int cb = u8(table, pos);
            final int name = pos + 40;
            int end = name;
            while (end + 1 < pos + 1 + cb && u16(table, end) != 0) {
                end += 2;
            }
            fonts.add(end > name ? new String(table, name, end - name, UTF16) : "");
            pos += 1 + cb;
        }
        return fonts;
    }

    private static String roman(int n) {
        final int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        final String[] glyphs = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
        final StringBuilder out = new StringBuilder();
        int left = Math.max(1, Math.min(3999, n));
        for (int i = 0; i < values.length; i++) {
            while (left >= values[i]) {
                out.append(glyphs[i]);
                left -= values[i];
            }
        }
        return out.toString();
    }

    private static String letters(int n) {
        final StringBuilder out = new StringBuilder();
        for (int left = Math.max(1, n); left > 0; left = (left - 1) / 26) {
            out.insert(0, (char) ('a' + (left - 1) % 26));
        }
        return out.toString();
    }

    /** The marker a list level shows for its n-th item */
    private static String marker(int nfc, int n, int level) {
        switch (nfc) {
            case 0:
                return n + ".";
            case 1:
                return roman(n).toUpperCase() + ".";
            case 2:
                return roman(n) + ".";
            case 3:
                return letters(n).toUpperCase() + ".";
            case 4:
                return letters(n) + ".";
            case 22:
                return String.format("%02d.", n);
            default:
                return new String[]{"•", "◦", "▪"}[level % 3];
        }
    }

    // ---- pictures ----

    /** The first PNG or JPEG inside a PICF's shape data, as a data URI, or null */
    private static String readPicture(byte[] data, int offset) {
        if (offset < 0 || offset + 0x44 > data.length) {
            return null;
        }
        final int lcb = u32(data, offset);
        final int header = u16(data, offset + 4);
        final int end = Math.min(data.length, offset + lcb);
        if (header < 0x2C || offset + header >= end) {
            return null;
        }
        for (int i = offset + header; i + 8 < end; i++) {
            final boolean png = u8(data, i) == 0x89 && u8(data, i + 1) == 0x50 && u8(data, i + 2) == 0x4E
                    && u8(data, i + 3) == 0x47;
            final boolean jpeg = u8(data, i) == 0xFF && u8(data, i + 1) == 0xD8 && u8(data, i + 2) == 0xFF;
            if (png || jpeg) {
                return "data:image/" + (png ? "png" : "jpeg") + ";base64,"
                        + Base64.encodeToString(data, i, end - i, Base64.NO_WRAP);
            }
        }
        return null;
    }

    /**
     * Floating pictures: an anchor character (0x08) at a character position names a shape, and the
     * shape names a picture in the drawing's picture store.
     *
     * @return the img tag of each picture by the character position of its anchor
     */
    private static Map<Integer, String> floatingPictures(byte[] word, byte[] table, int[] spaPair, int[] dggPair) {
        final Map<Integer, String> out = new HashMap<>();
        final int fcSpa = spaPair[0], lcbSpa = spaPair[1], fcDgg = dggPair[0], lcbDgg = dggPair[1];
        try {
            if (lcbSpa < 34 || lcbDgg <= 16 || fcSpa < 0 || fcDgg < 0 || (long) fcDgg + lcbDgg > table.length
                    || (long) fcSpa + lcbSpa > table.length) {
                return out;
            }
            final int dggEnd = fcDgg + lcbDgg;
            final List<int[]> store = Escher.blipStore(table, fcDgg, dggEnd);
            // Drawings follow the first container, each after a one-byte tag
            final Map<Integer, Integer> shapes = new HashMap<>();
            int pos = fcDgg;
            while (pos + 8 <= dggEnd) {
                final List<Escher.Rec> first = Escher.children(table, pos, dggEnd);
                if (first.isEmpty()) {
                    break;
                }
                final Escher.Rec rec = first.get(0);
                final List<Escher.Rec> containers = new ArrayList<>();
                Escher.descendants(table, rec.body, rec.end, Escher.SP_CONTAINER, containers, 0);
                for (Escher.Rec container : containers) {
                    final List<Escher.Rec> parts = Escher.children(table, container.body, container.end);
                    final Escher.Rec head = Escher.find(parts, Escher.SP);
                    final Escher.Rec opt = Escher.find(parts, Escher.OPT);
                    if (head != null && opt != null) {
                        final Map<Integer, Integer> props = new HashMap<>();
                        Escher.readProps(table, opt, props);
                        final Integer pib = props.get(0x0104);
                        if (pib != null && pib != 0) {
                            shapes.put(u32(table, head.body), pib);
                        }
                    }
                }
                pos = rec.end + 1;
            }
            final int count = (lcbSpa - 4) / 30;
            for (int i = 0; i < count; i++) {
                final int spa = fcSpa + (count + 1) * 4 + i * 26;
                final Integer pib = shapes.get(u32(table, spa));
                final String uri = pib == null ? null : Escher.storedImage(store, pib, table, word);
                if (uri != null) {
                    // Twips to CSS px
                    final int width = (u32(table, spa + 12) - u32(table, spa + 4)) / 15;
                    final int height = (u32(table, spa + 16) - u32(table, spa + 8)) / 15;
                    out.put(u32(table, fcSpa + i * 4), "<img src=\"" + uri + "\""
                            + (width > 0 && height > 0 ? " width=" + width + " height=" + height : "") + ">");
                }
            }
        } catch (RuntimeException e) {
            // Floating pictures are optional; the text still shows
        }
        return out;
    }

    // ---- document ----

    private static List<Piece> readPieces(byte[] word, byte[] table, int fcClx, int lcbClx) throws IOException {
        final List<Piece> pieces = new ArrayList<>();
        if (lcbClx <= 0 || fcClx < 0 || fcClx + lcbClx > table.length) {
            return pieces;
        }
        int pos = fcClx;
        final int end = fcClx + lcbClx;
        while (pos < end && u8(table, pos) == 0x01) {
            pos += 3 + u16(table, pos + 1);
        }
        if (u8(table, pos) != 0x02) {
            throw new IOException("No piece table");
        }
        final int plc = pos + 5;
        final int n = (u32(table, pos + 1) - 4) / 12;
        for (int i = 0; i < n; i++) {
            final int cpStart = u32(table, plc + i * 4);
            final int cpEnd = u32(table, plc + (i + 1) * 4);
            final int raw = u32(table, plc + (n + 1) * 4 + i * 8 + 2);
            final boolean compressed = (raw & 0x40000000) != 0;
            final Piece piece = new Piece();
            piece.fc = compressed ? (raw & 0x3FFFFFFF) / 2 : raw & 0x3FFFFFFF;
            piece.wide = !compressed;
            piece.cpStart = cpStart;
            piece.chars = cpEnd - cpStart;
            if (piece.chars > 0 && piece.fc >= 0
                    && (long) piece.fc + (long) piece.chars * (compressed ? 1 : 2) <= word.length) {
                pieces.add(piece);
            }
        }
        return pieces;
    }

    private static String esc(String text) {
        final StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\n':
                    out.append("<br>");
                    break;
                case '\t':
                    out.append("&emsp;");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    /** Drops the in-text anchors of pictures, footnotes, comments and drawn objects */
    private static String stripAnchors(String text) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c != 1 && c != 2 && c != 5 && c != 8) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean safeHref(String url) {
        final String lower = url.toLowerCase();
        return lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("mailto:");
    }

    private static String runHtml(Chp chp, String font, String href, String text) {
        final StringBuilder style = new StringBuilder();
        if (chp.bold) {
            style.append("font-weight:bold;");
        }
        if (chp.italic) {
            style.append("font-style:italic;");
        }
        if (chp.underline || chp.strike) {
            style.append("text-decoration:").append(chp.underline ? "underline " : "")
                    .append(chp.strike ? "line-through" : "").append(';');
        }
        if (chp.hps != 20) {
            style.append("font-size:").append(chp.hps / 2f).append("pt;");
        }
        if (chp.color != null) {
            style.append("color:").append(chp.color).append(';');
        }
        if (chp.highlight != null) {
            style.append("background:").append(chp.highlight).append(';');
        }
        if (font != null && !font.isEmpty()) {
            style.append("font-family:'").append(font.replace("'", "").replace("\"", "")).append("',sans-serif;");
        }
        String html = esc(text);
        if (chp.script == 1) {
            html = "<sup>" + html + "</sup>";
        } else if (chp.script == 2) {
            html = "<sub>" + html + "</sub>";
        }
        if (style.length() > 0) {
            html = "<span style=\"" + style + "\">" + html + "</span>";
        }
        return href != null ? "<a href=\"" + esc(href) + "\">" + html + "</a>" : html;
    }

    /** @return the document as an HTML fragment */
    public static String html(byte[] file) throws IOException {
        final BinaryOffice.Ole2 ole = new BinaryOffice.Ole2(file);
        final byte[] word = ole.stream("WordDocument");
        if (word == null || word.length < 0x1AA || u16(word, 0) != 0xA5EC) {
            throw new IOException("Not a Word document");
        }
        final int flags = u16(word, 0x0A);
        if ((flags & 0x0100) != 0) {
            throw new IOException("Encrypted");
        }
        if (u16(word, 2) < 0x00C1) {
            throw new IOException("Word 6/95");
        }
        final byte[] table = ole.stream((flags & 0x0200) != 0 ? "1Table" : "0Table");
        if (table == null) {
            throw new IOException("No table stream");
        }
        final byte[] stored = ole.stream("Data");
        final byte[] data = stored != null ? stored : word;

        final int pairCount = u16(word, 0x98);
        final int[][] pairs = new int[75][2];
        for (int i = 0; i < pairs.length && i < pairCount && 0xA2 + i * 8 <= word.length; i++) {
            pairs[i][0] = u32(word, 0x9A + i * 8);
            pairs[i][1] = u32(word, 0x9E + i * 8);
        }
        final List<Piece> pieces = readPieces(word, table, pairs[33][0], pairs[33][1]);
        final List<Run> chpRuns = readRuns(word, table, pairs[12][0], pairs[12][1], false);
        final List<Run> papRuns = readRuns(word, table, pairs[13][0], pairs[13][1], true);
        final List<Style> styles = readStyles(table, pairs[1][0], pairs[1][1]);
        final List<String> fonts = readFonts(table, pairs[15][0], pairs[15][1]);
        final Map<Integer, int[]> lists = readLists(table, pairs[73][0], pairs[73][1], pairs[74][0], pairs[74][1]);
        final int mainEnd = u32(word, 0x4C);

        final Map<Integer, Object[]> styleCache = new HashMap<>();
        final StyleResolver resolver = new StyleResolver(styles, table, styleCache);

        final List<Para> paragraphs = new ArrayList<>();
        final StringBuilder runs = new StringBuilder();
        final List<Field> fields = new ArrayList<>();
        final List<Span> spans = new ArrayList<>();
        final Map<Integer, String> pictures = new HashMap<>();
        final Map<Integer, String> floating = floatingPictures(word, table, pairs[40], pairs[50]);

        for (Piece piece : pieces) {
            if (piece.cpStart >= mainEnd) {
                break;
            }
            final int chars = Math.min(piece.chars, mainEnd - piece.cpStart);
            final int step = piece.wide ? 2 : 1;
            final String text = new String(word, piece.fc, chars * step, piece.wide ? UTF16 : ANSI);
            int i = 0;
            while (i < chars) {
                final int fc = piece.fc + i * step;
                final Run run = runAt(chpRuns, fc);
                final int stop = run != null ? Math.min(chars, i + (run.end - fc + step - 1) / step) : i + 1;
                final StringBuilder buffer = new StringBuilder();
                final int limit = Math.min(chars, Math.max(stop, i + 1));
                for (; i < limit; i++) {
                    final char code = text.charAt(i);
                    final int at = piece.fc + i * step;
                    final Field top = fields.isEmpty() ? null : fields.get(fields.size() - 1);
                    if (code == 0x13) {
                        emit(buffer, fields, spans, run);
                        fields.add(new Field());
                    } else if (code == 0x14) {
                        emit(buffer, fields, spans, run);
                        if (top != null) {
                            top.inResult = true;
                            final Matcher link = LINK.matcher(top.instruction);
                            top.href = link.find() && safeHref(link.group(1)) ? link.group(1) : null;
                        }
                    } else if (code == 0x15) {
                        emit(buffer, fields, spans, run);
                        if (top != null) {
                            fields.remove(fields.size() - 1);
                        }
                    } else if (top != null && !top.inResult) {
                        top.instruction.append(code);
                    } else if (code == 0x0D || code == 0x07 || code == 0x0C) {
                        emit(buffer, fields, spans, run);
                        flushSpans(at, spans, runs, papRuns, resolver, word, table, styles, fonts, data, pictures);
                        endParagraph(at, code, runs, paragraphs, papRuns, resolver, word);
                    } else if (code == 0x08) {
                        emit(buffer, fields, spans, run);
                        final String image = floating.get(piece.cpStart + i);
                        if (image != null) {
                            final Span span = new Span();
                            span.text = "";
                            span.image = image;
                            spans.add(span);
                        }
                    } else if (code == 0x0B) {
                        buffer.append('\n');
                    } else if (code == 0x1E) {
                        buffer.append('-');
                    } else if (code == 0xA0) {
                        buffer.append(' ');
                    } else if (code == 0x09 || code >= 0x20 || code <= 0x08) {
                        buffer.append(code);
                    }
                }
                emit(buffer, fields, spans, run);
            }
        }
        if (!spans.isEmpty() || runs.length() > 0) {
            final Piece last = pieces.isEmpty() ? null : pieces.get(pieces.size() - 1);
            final int at = last == null ? 0 : last.fc + (last.chars - 1) * (last.wide ? 2 : 1);
            flushSpans(at, spans, runs, papRuns, resolver, word, table, styles, fonts, data, pictures);
            endParagraph(at, 0x0D, runs, paragraphs, papRuns, resolver, word);
        }
        return blocks(paragraphs, styles, lists);
    }

    private static void emit(StringBuilder buffer, List<Field> fields, List<Span> spans, Run run) {
        if (buffer.length() == 0) {
            return;
        }
        boolean hidden = false;
        for (Field f : fields) {
            hidden |= !f.inResult;
        }
        if (!hidden) {
            final Span span = new Span();
            span.text = buffer.toString();
            span.run = run;
            span.href = fields.isEmpty() ? null : fields.get(fields.size() - 1).href;
            spans.add(span);
        }
        buffer.setLength(0);
    }

    /** Style formatting, resolved through the "based on" chain */
    private static final class StyleResolver {
        private final List<Style> styles;
        private final byte[] table;
        private final Map<Integer, Object[]> cache;

        StyleResolver(List<Style> styles, byte[] table, Map<Integer, Object[]> cache) {
            this.styles = styles;
            this.table = table;
            this.cache = cache;
        }

        Object[] resolve(int istd, int depth) {
            final Object[] cached = cache.get(istd);
            if (cached != null) {
                return cached;
            }
            final Style style = istd >= 0 && istd < styles.size() ? styles.get(istd) : null;
            final Object[] base = style != null && style.base != 0xFFF && style.base != istd && depth < 12
                    ? resolve(style.base, depth + 1) : new Object[]{new Chp(), new Pap()};
            final Chp chp = ((Chp) base[0]).copy();
            final Pap pap = ((Pap) base[1]).copy();
            pap.istd = istd;
            if (style != null && style.pap != null) {
                applyPap(pap, table, sprms(table, style.pap[0], style.pap[1]));
            }
            if (style != null && style.chp != null) {
                applyChp(chp, table, sprms(table, style.chp[0], style.chp[1]), (Chp) base[0]);
            }
            final Object[] out = {chp, pap};
            cache.put(istd, out);
            return out;
        }
    }

    private static void flushSpans(int markFc, List<Span> spans, StringBuilder runs, List<Run> papRuns,
                                   StyleResolver resolver, byte[] word, byte[] table, List<Style> styles,
                                   List<String> fonts, byte[] data, Map<Integer, String> pictures) {
        final Run para = runAt(papRuns, markFc);
        final Chp style = (Chp) resolver.resolve(para != null ? para.istd : 0, 0)[0];
        for (Span span : spans) {
            if (span.image != null) {
                runs.append(span.image);
                continue;
            }
            final Chp chp = style.copy();
            if (span.run != null && span.run.to > span.run.from) {
                final List<Sprm> list = sprms(word, span.run.from, span.run.to);
                for (Sprm s : list) {
                    if (s.op == 0x4A30) {
                        final int index = u16(word, s.at);
                        final Style charStyle = index < styles.size() ? styles.get(index) : null;
                        if (charStyle != null && charStyle.chp != null) {
                            applyChp(chp, table, sprms(table, charStyle.chp[0], charStyle.chp[1]), style);
                        }
                    }
                }
                applyChp(chp, word, list, style);
            }
            if (chp.hidden || chp.deleted) {
                continue;
            }
            final String font = chp.font >= 0 && chp.font < fonts.size() ? fonts.get(chp.font) : null;
            if (chp.special && chp.pic >= 0 && span.text.indexOf('\u0001') >= 0) {
                if (!pictures.containsKey(chp.pic)) {
                    pictures.put(chp.pic, readPicture(data, chp.pic));
                }
                final String image = pictures.get(chp.pic);
                if (image != null) {
                    runs.append("<img src=\"").append(image).append("\">");
                }
                continue;
            }
            String text = stripAnchors(span.text);
            if (chp.symbol >= 0 && chp.special) {
                text = String.valueOf((char) ((chp.symbol & 0xFF00) == 0xF000 ? chp.symbol & 0xFF : chp.symbol));
            }
            if (chp.caps) {
                text = text.toUpperCase();
            }
            if (!text.isEmpty()) {
                runs.append(runHtml(chp, font, span.href, text));
            }
        }
        spans.clear();
    }

    private static void endParagraph(int markFc, int mark, StringBuilder runs, List<Para> paragraphs,
                                     List<Run> papRuns, StyleResolver resolver, byte[] word) {
        final Run run = runAt(papRuns, markFc);
        final int istd = run != null ? run.istd : 0;
        final Pap pap = ((Pap) resolver.resolve(istd, 0)[1]).copy();
        pap.istd = istd;
        if (run != null && run.to > run.from) {
            applyPap(pap, word, sprms(word, run.from, run.to));
        }
        final Para para = new Para();
        para.html = runs.toString();
        para.empty = runs.length() == 0;
        para.pap = pap;
        para.mark = mark;
        paragraphs.add(para);
        runs.setLength(0);
    }

    // ---- headings, lists and tables ----

    private static final class Builder {
        final StringBuilder out = new StringBuilder();
        final Map<Integer, int[]> counters = new HashMap<>();
        boolean pendingBreak;
        List<String> cell = new ArrayList<>();
        List<Cell> row = new ArrayList<>();
        List<List<String>> rowContent = new ArrayList<>();
        final List<List<Cell>> tableRows = new ArrayList<>();
        final List<int[][]> rowEdges = new ArrayList<>();
        final Map<Integer, Cell> open = new HashMap<>();
    }

    private static String block(Para p, List<Style> styles, Map<Integer, int[]> lists, Builder b) {
        final Style style = p.pap.istd >= 0 && p.pap.istd < styles.size() ? styles.get(p.pap.istd) : null;
        final int sti = style != null ? style.sti : 0;
        final int heading = sti >= 1 && sti <= 9 ? Math.min(6, sti) : 0;
        final String tag = heading > 0 ? "h" + heading : "p";
        final StringBuilder css = new StringBuilder();
        if (p.pap.align != null) {
            css.append("text-align:").append(p.pap.align).append(';');
        }
        if (p.pap.rtl) {
            css.append("direction:rtl;");
        }
        String content = p.html;
        if (p.pap.ilfo > 0 && p.pap.ilfo < 0x07FF && !p.empty) {
            final int level = Math.min(8, p.pap.ilvl);
            int[] counts = b.counters.get(p.pap.ilfo);
            if (counts == null || counts.length <= level) {
                counts = counts == null ? new int[level + 1] : Arrays.copyOf(counts, level + 1);
            }
            counts[level]++;
            Arrays.fill(counts, level + 1, counts.length, 0);
            b.counters.put(p.pap.ilfo, counts);
            final int[] nfcs = lists.get(p.pap.ilfo);
            final int nfc = nfcs != null && level < nfcs.length ? nfcs[level] : 23;
            css.append("margin-left:").append(28 * (level + 1)).append("px;text-indent:-22px;");
            content = "<span class=m>" + esc(marker(nfc, counts[level], level)) + "</span>" + content;
        } else if (p.pap.indent > 0) {
            css.append("margin-left:").append(Math.round(p.pap.indent / 1440f * 96)).append("px;");
        }
        final StringBuilder out = new StringBuilder();
        if (p.pap.pageBreakBefore || b.pendingBreak) {
            out.append("<hr>");
        }
        b.pendingBreak = false;
        out.append('<').append(tag);
        if (css.length() > 0) {
            out.append(" style=\"").append(css).append('"');
        }
        out.append('>').append(p.empty ? "&nbsp;" : content).append("</").append(tag).append('>');
        return out.toString();
    }

    private static String blocks(List<Para> paragraphs, List<Style> styles, Map<Integer, int[]> lists) {
        final Builder b = new Builder();
        for (Para p : paragraphs) {
            final boolean inTable = p.pap.inTable || p.pap.depth > 0;
            if (!inTable) {
                endTable(b);
                if (p.mark == 0x0C) {
                    if (!p.empty) {
                        b.out.append(block(p, styles, lists, b));
                    }
                    b.pendingBreak = true;
                    continue;
                }
                b.out.append(block(p, styles, lists, b));
                continue;
            }
            if (p.pap.innerTtp) {
                continue;
            }
            if (p.mark != 0x07) {
                if (!p.empty || !p.pap.innerCell) {
                    b.cell.add(block(p, styles, lists, b));
                }
                continue;
            }
            if (!p.pap.ttp) {
                if (!p.empty || b.cell.isEmpty()) {
                    b.cell.add(block(p, styles, lists, b));
                }
                b.rowContent.add(b.cell);
                b.cell = new ArrayList<>();
                continue;
            }
            endRow(b, p);
        }
        endTable(b);
        for (List<String> content : b.rowContent) {
            for (String html : content) {
                b.out.append(html);
            }
        }
        for (String html : b.cell) {
            b.out.append(html);
        }
        return b.out.toString();
    }

    /** Applies the row's cell definitions: merges, widths and shading */
    private static void endRow(Builder b, Para p) {
        final List<Cell> cells = new ArrayList<>();
        final int[] edges = p.pap.edges;
        final boolean known = edges != null && edges.length > b.rowContent.size();
        final List<int[]> spans = new ArrayList<>();
        for (int c = 0; c < b.rowContent.size(); c++) {
            final boolean[] def = p.pap.cells != null && c < p.pap.cells.length ? p.pap.cells[c] : null;
            final int left = known ? edges[c] : c;
            final int right = known ? edges[c + 1] : c + 1;
            if (def != null && def[1] && !def[0] && !spans.isEmpty()) {
                spans.get(spans.size() - 1)[1] = right;
                continue;
            }
            final Cell above = b.open.get(left);
            if (def != null && def[2] && !def[3] && above != null) {
                above.rowSpan++;
                continue;
            }
            final Cell made = new Cell(b.rowContent.get(c));
            made.width = known ? Math.max(8, Math.round((right - left) / 1440f * 96)) : 0;
            made.fill = p.pap.shades != null && c < p.pap.shades.length ? p.pap.shades[c] : null;
            if (def != null && def[2] && def[3]) {
                b.open.put(left, made);
            } else {
                b.open.remove(left);
            }
            cells.add(made);
            spans.add(new int[]{left, right});
        }
        if (!cells.isEmpty()) {
            b.tableRows.add(cells);
            b.rowEdges.add(known ? spans.toArray(new int[0][]) : null);
        }
        b.rowContent = new ArrayList<>();
        b.cell = new ArrayList<>();
    }

    /** Word gives each row its own cell edges, so one shared grid of them turns merges into column spans */
    private static void endTable(Builder b) {
        if (!b.tableRows.isEmpty()) {
            boolean all = true;
            final TreeSet<Integer> grid = new TreeSet<>();
            for (int[][] edges : b.rowEdges) {
                if (edges == null) {
                    all = false;
                    break;
                }
                for (int[] e : edges) {
                    grid.add(Math.round(e[0] / 15f) * 15);
                    grid.add(Math.round(e[1] / 15f) * 15);
                }
            }
            final List<Integer> columns = new ArrayList<>(grid);
            if (all && columns.size() > 1) {
                for (int r = 0; r < b.tableRows.size(); r++) {
                    final List<Cell> cells = b.tableRows.get(r);
                    for (int c = 0; c < cells.size(); c++) {
                        final int[] e = b.rowEdges.get(r)[c];
                        final int span = columns.indexOf(Math.round(e[1] / 15f) * 15)
                                - columns.indexOf(Math.round(e[0] / 15f) * 15);
                        cells.get(c).colSpan = Math.max(1, span);
                    }
                }
            }
            b.out.append("<table>");
            for (List<Cell> cells : b.tableRows) {
                b.out.append("<tr>");
                for (Cell cell : cells) {
                    b.out.append("<td");
                    if (cell.colSpan > 1) {
                        b.out.append(" colspan=").append(cell.colSpan);
                    }
                    if (cell.rowSpan > 1) {
                        b.out.append(" rowspan=").append(cell.rowSpan);
                    }
                    if (cell.fill != null || cell.width > 0) {
                        b.out.append(" style=\"");
                        if (cell.fill != null) {
                            b.out.append("background:").append(cell.fill).append(';');
                        }
                        if (cell.width > 0) {
                            b.out.append("min-width:").append(cell.width).append("px;");
                        }
                        b.out.append('"');
                    }
                    b.out.append('>');
                    for (String html : cell.blocks) {
                        b.out.append(html);
                    }
                    b.out.append("</td>");
                }
                b.out.append("</tr>");
            }
            b.out.append("</table>");
        }
        b.tableRows.clear();
        b.rowEdges.clear();
        b.open.clear();
    }
}
