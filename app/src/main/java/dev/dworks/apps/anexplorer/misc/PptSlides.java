package dev.dworks.apps.anexplorer.misc;

import static dev.dworks.apps.anexplorer.misc.BinaryOffice.u16;
import static dev.dworks.apps.anexplorer.misc.BinaryOffice.u32;
import static dev.dworks.apps.anexplorer.misc.Escher.CHILD_ANCHOR;
import static dev.dworks.apps.anexplorer.misc.Escher.CLIENT_ANCHOR;
import static dev.dworks.apps.anexplorer.misc.Escher.CLIENT_DATA;
import static dev.dworks.apps.anexplorer.misc.Escher.CLIENT_TEXTBOX;
import static dev.dworks.apps.anexplorer.misc.Escher.DG;
import static dev.dworks.apps.anexplorer.misc.Escher.OPT;
import static dev.dworks.apps.anexplorer.misc.Escher.SP;
import static dev.dworks.apps.anexplorer.misc.Escher.SPGR;
import static dev.dworks.apps.anexplorer.misc.Escher.SPGR_CONTAINER;
import static dev.dworks.apps.anexplorer.misc.Escher.SP_CONTAINER;
import static dev.dworks.apps.anexplorer.misc.Escher.TERTIARY_OPT;
import static dev.dworks.apps.anexplorer.misc.Escher.children;
import static dev.dworks.apps.anexplorer.misc.Escher.descendants;
import static dev.dworks.apps.anexplorer.misc.Escher.find;
import static dev.dworks.apps.anexplorer.misc.Escher.readProps;

import dev.dworks.apps.anexplorer.misc.Escher.Rec;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Draws a PowerPoint 97-2003 deck (.ppt, .pps) as HTML slides: each shape sits at its place, with
 * its fill, outline and pictures, and the text keeps the master's styles and its own formatting.
 * It is a Java port of the legacy PowerPoint reader of Paperwren (MIT, Copyright (c) 2026
 * Paperwren contributors), which follows [MS-PPT] and [MS-ODRAW].
 * <p>
 * Speaker notes follow their slide. EMF and WMF pictures are drawn through {@link Metafile}, and
 * an embedded chart or other OLE object shows the picture PowerPoint stored for it (an empty
 * dashed box if there is none). Shaded fills are two-colour gradients; patterns use the first
 * colour. Animations and transitions are not read: every shape shows as it ends up.
 */
final class PptSlides {

    private static final Charset ANSI = Charset.forName("windows-1252");
    private static final Charset UTF16 = Charset.forName("UTF-16LE");
    private static final int UNSET = Integer.MIN_VALUE;
    private static final int MAX_SLIDES = 300;
    private static final int MAX_DEPTH = 12;

    // [MS-PPT] record types
    private static final int DOCUMENT = 0x03E8;
    private static final int DOCUMENT_ATOM = 0x03E9;
    private static final int SLIDE = 0x03EE;
    private static final int SLIDE_ATOM = 0x03EF;
    private static final int NOTES = 0x03F0;
    private static final int ENVIRONMENT = 0x03F2;
    private static final int SLIDE_PERSIST = 0x03F3;
    private static final int DRAWING_GROUP = 0x040B;
    private static final int DRAWING = 0x040C;
    private static final int COLOR_SCHEME = 0x07F0;
    private static final int PLACEHOLDER = 0x0BC3;
    private static final int OUTLINE_TEXT_REF = 0x0F9E;
    private static final int TEXT_HEADER = 0x0F9F;
    private static final int TEXT_CHARS = 0x0FA0;
    private static final int STYLE_TEXT_PROP = 0x0FA1;
    private static final int MASTER_STYLE = 0x0FA3;
    private static final int TEXT_BYTES = 0x0FA8;
    private static final int FONT_ENTITY = 0x0FB7;
    private static final int SLIDE_LIST = 0x0FF0;
    private static final int USER_EDIT = 0x0FF5;
    private static final int PERSIST_DIRECTORY = 0x1772;

    private static final String[] ALIGN = {"left", "center", "right", "justify"};
    private static final String[] DEFAULT_SCHEME = {"#ffffff", "#000000", "#808080", "#000000",
            "#bbe0e3", "#333399", "#009999", "#99cc00"};

    /** Paragraph properties; {@link #UNSET} means the record does not say */
    private static final class PF {
        int bullet = UNSET, bulletChar = UNSET, bulletFont = UNSET, align = UNSET, lineSpacing = UNSET;
        int spaceBefore = UNSET, spaceAfter = UNSET, leftMargin = UNSET, rtl = UNSET;

        /** @return these properties, with the ones they leave out taken from {@code base} */
        PF over(PF base) {
            final PF out = new PF();
            out.bullet = bullet != UNSET ? bullet : base.bullet;
            out.bulletChar = bulletChar != UNSET ? bulletChar : base.bulletChar;
            out.bulletFont = bulletFont != UNSET ? bulletFont : base.bulletFont;
            out.align = align != UNSET ? align : base.align;
            out.lineSpacing = lineSpacing != UNSET ? lineSpacing : base.lineSpacing;
            out.spaceBefore = spaceBefore != UNSET ? spaceBefore : base.spaceBefore;
            out.spaceAfter = spaceAfter != UNSET ? spaceAfter : base.spaceAfter;
            out.leftMargin = leftMargin != UNSET ? leftMargin : base.leftMargin;
            out.rtl = rtl != UNSET ? rtl : base.rtl;
            return out;
        }
    }

    /** Character properties; the colour is the raw 32-bit value, or -1 when unset */
    private static final class CF {
        int bold = UNSET, italic = UNSET, underline = UNSET, font = UNSET, size = UNSET;
        long color = -1;

        CF over(CF base) {
            final CF out = new CF();
            out.bold = bold != UNSET ? bold : base.bold;
            out.italic = italic != UNSET ? italic : base.italic;
            out.underline = underline != UNSET ? underline : base.underline;
            out.font = font != UNSET ? font : base.font;
            out.size = size != UNSET ? size : base.size;
            out.color = color != -1 ? color : base.color;
            return out;
        }
    }

    private static final class Level {
        final PF pf = new PF();
        final CF cf = new CF();
    }

    private static final class TextBlock {
        int type;
        String text = "";
        int styleFrom = -1, styleTo = -1;
    }

    private static final class Entry {
        int ref, id;
        List<TextBlock> texts = new ArrayList<>();
    }

    private static final class Master {
        Map<Integer, List<Level>> styles = new HashMap<>();
        String[] scheme = DEFAULT_SCHEME;
        /** Shapes drawn behind every slide that follows this master */
        final StringBuilder shapes = new StringBuilder();
        String background;
    }

    /** What the shapes of one drawing need to know about where they are drawn */
    private static final class Context {
        Map<Integer, List<Level>> styles = new HashMap<>();
        String[] scheme = DEFAULT_SCHEME;
        List<TextBlock> texts = new ArrayList<>();
        /** Masters contribute decoration only, never placeholders */
        boolean decorationOnly;
    }

    private final byte[] doc;
    private final byte[] pictures;
    private final Map<Integer, Integer> persist = new HashMap<>();
    private final Map<Integer, String> fonts = new HashMap<>();
    private final Map<Integer, List<Level>> otherStyles = new HashMap<>();
    private final List<int[]> store = new ArrayList<>();
    private final Map<Integer, String> pictureCache = new HashMap<>();
    private final Map<Integer, Master> masters = new HashMap<>();
    private final List<List<Entry>> lists = new ArrayList<>();
    private int documentRef;

    private PptSlides(byte[] doc, byte[] pictures) {
        this.doc = doc;
        this.pictures = pictures;
        for (int i = 0; i < 3; i++) {
            lists.add(new ArrayList<>());
        }
    }

    /** @return the HTML of all the slides; throws if the file is encrypted or has no slides */
    static String html(byte[] file) throws IOException {
        final BinaryOffice.Ole2 ole = new BinaryOffice.Ole2(file);
        final byte[] doc = ole.stream("PowerPoint Document");
        if (doc == null) {
            throw new IOException("Not a PowerPoint file");
        }
        final byte[] current = ole.stream("Current User");
        if (ole.stream("EncryptedSummary") != null
                || (current != null && current.length >= 16 && u32(current, 12) == 0xF3D1C4DF)) {
            throw new IOException("Encrypted deck");
        }
        return new PptSlides(doc, ole.stream("Pictures")).render(current);
    }

    private String render(byte[] current) throws IOException {
        readPersistDirectory(current);
        final Rec documentRec = documentRecord();
        final List<Rec> top = children(doc, documentRec.body, documentRec.end);
        double width = 960, height = 720;
        for (Rec rec : top) {
            if (rec.type == DOCUMENT_ATOM) {
                width = mu(u32(doc, rec.body));
                height = mu(u32(doc, rec.body + 4));
            } else if (rec.type == ENVIRONMENT) {
                readEnvironment(rec);
            } else if (rec.type == DRAWING_GROUP) {
                store.addAll(Escher.blipStore(doc, rec.body, rec.end));
            } else if (rec.type == SLIDE_LIST && rec.inst < 3) {
                readSlideList(rec, lists.get(rec.inst));
            }
        }
        if (width < 100 || height < 100) {
            width = 960;
            height = 720;
        }

        final StringBuilder out = new StringBuilder();
        // One slide is about 900 CSS px wide on the page, whatever the deck's own size
        out.append("<div id=deck style=\"zoom:").append(fmt(900 / width)).append("\">");
        int count = 0;
        for (Entry entry : lists.get(0)) {
            final Rec rec = recordAt(persist.get(entry.ref));
            if (rec == null || rec.type != SLIDE) {
                continue;
            }
            if (++count > MAX_SLIDES) {
                break;
            }
            out.append("<div class=slide style=\"width:").append(fmt(width)).append("px;height:")
                    .append(fmt(height)).append("px;");
            slide(rec, entry, out);
            out.append("<span class=n>").append(count).append(" / ").append(lists.get(0).size())
                    .append("</span></div>");
            final String notes = notes(rec);
            if (!notes.isEmpty()) {
                out.append("<div class=notes style=\"width:").append(fmt(width)).append("px\">").append(notes)
                        .append("</div>");
            }
        }
        out.append("</div>");
        if (count == 0) {
            throw new IOException("No slides");
        }
        return out.toString();
    }

    private void slide(Rec rec, Entry entry, StringBuilder out) {
        final List<Rec> parts = children(doc, rec.body, rec.end);
        final Rec atom = find(parts, SLIDE_ATOM);
        final int masterId = atom == null ? 0 : u32(doc, atom.body + 12);
        final int follow = atom == null ? 0x7 : u16(doc, atom.body + 20);
        Master master = master(masterId, 0);
        if (master == null && !lists.get(1).isEmpty()) {
            master = master(lists.get(1).get(0).id, 0);
        }
        final String[] ownScheme = (follow & 0x2) != 0 ? null : readScheme(last(parts, COLOR_SCHEME));
        final String[] scheme = ownScheme != null ? ownScheme : master != null ? master.scheme : DEFAULT_SCHEME;
        final Rec drawing = find(parts, DRAWING);
        final Context context = new Context();
        context.styles = master != null ? master.styles : new HashMap<>();
        context.scheme = scheme;
        context.texts = entry.texts;
        String background = (follow & 0x4) != 0 && master != null ? master.background : background(drawing, scheme);
        if (background == null) {
            background = master != null && master.background != null ? master.background : scheme[0];
        }
        out.append("background:").append(background).append("\">");
        if ((follow & 0x1) != 0 && master != null) {
            out.append(master.shapes);
        }
        drawing(drawing, context, out);
    }

    /** @return the speaker notes of a slide as HTML paragraphs, or "" if it has none */
    private String notes(Rec slide) {
        final Rec atom = find(children(doc, slide.body, slide.end), SLIDE_ATOM);
        final int notesId = atom == null ? 0 : u32(doc, atom.body + 16);
        Integer offset = null;
        // The slide holds the notes page's slide id, which the notes list maps to its persist id
        for (Entry entry : lists.get(2)) {
            if (entry.id == notesId) {
                offset = persist.get(entry.ref);
                break;
            }
        }
        final Rec rec = recordAt(offset);
        if (rec == null || rec.type != NOTES) {
            return "";
        }
        final List<Rec> flat = new ArrayList<>();
        flatten(rec, flat, 0);
        final StringBuilder out = new StringBuilder();
        for (TextBlock block : readTexts(flat)) {
            if (block.type != 2 || block.text.trim().isEmpty()) {
                continue;
            }
            for (String line : block.text.split("[\r\n]")) {
                if (!line.trim().isEmpty()) {
                    out.append("<p>").append(esc(line.replace('\u000b', ' ').trim())).append("</p>");
                }
            }
        }
        return out.toString();
    }

    /** The records under a container in document order, containers themselves left out */
    private void flatten(Rec container, List<Rec> out, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (Rec rec : children(doc, container.body, container.end)) {
            if (rec.ver == 0xF) {
                flatten(rec, out, depth + 1);
            } else {
                out.add(rec);
            }
        }
    }

    // ---- the persist directory: object id -> offset of its live version ----

    private void readPersistDirectory(byte[] current) {
        int edit = current != null && current.length >= 20 ? u32(current, 16) : 0;
        documentRef = 0;
        for (int guard = 0; edit > 0 && edit + 28L <= doc.length && guard < 200; guard++) {
            if (u16(doc, edit + 2) != USER_EDIT) {
                break;
            }
            final int body = edit + 8;
            final int directory = u32(doc, body + 12);
            if (documentRef == 0) {
                documentRef = u32(doc, body + 16);
            }
            if (directory > 0 && directory + 8L <= doc.length && u16(doc, directory + 2) == PERSIST_DIRECTORY) {
                final long end = Math.min(doc.length, directory + 8L + (u32(doc, directory + 4) & 0xFFFFFFFFL));
                long pos = directory + 8;
                while (pos + 4 <= end) {
                    final int head = u32(doc, (int) pos);
                    final int first = head & 0xFFFFF;
                    final int count = head >>> 20;
                    pos += 4;
                    for (int i = 0; i < count && pos + 4 <= end; i++, pos += 4) {
                        if (!persist.containsKey(first + i)) {
                            persist.put(first + i, u32(doc, (int) pos));
                        }
                    }
                }
            }
            edit = u32(doc, body + 8);
        }
    }

    private Rec documentRecord() throws IOException {
        Rec rec = recordAt(persist.get(documentRef));
        if (rec == null || rec.type != DOCUMENT) {
            // No usable directory (damaged file): take the first Document
            rec = find(children(doc, 0, doc.length), DOCUMENT);
        }
        if (rec == null) {
            throw new IOException("No document record");
        }
        return rec;
    }

    private Rec recordAt(Integer offset) {
        if (offset == null || offset < 0 || offset + 8L > doc.length) {
            return null;
        }
        final int head = u16(doc, offset);
        final long end = offset + 8L + (u32(doc, offset + 4) & 0xFFFFFFFFL);
        return end > doc.length ? null : new Rec(head & 0xF, head >> 4, u16(doc, offset + 2), offset + 8, (int) end);
    }

    // ---- records ----

    private static Rec last(List<Rec> recs, int type) {
        Rec found = null;
        for (Rec rec : recs) {
            if (rec.type == type) {
                found = rec;
            }
        }
        return found;
    }

    // ---- document facts ----

    private void readEnvironment(Rec environment) {
        final List<Rec> entities = new ArrayList<>();
        descendants(doc, environment.body, environment.end, FONT_ENTITY, entities, 0);
        for (Rec rec : entities) {
            int end = rec.body;
            while (end + 1 < Math.min(rec.end, rec.body + 64) && u16(doc, end) != 0) {
                end += 2;
            }
            fonts.put(rec.inst, new String(doc, rec.body, end - rec.body, UTF16));
        }
        final List<Rec> styles = new ArrayList<>();
        descendants(doc, environment.body, environment.end, MASTER_STYLE, styles, 0);
        for (Rec rec : styles) {
            readMasterStyle(rec, otherStyles);
        }
    }

    /** @return the picture with that 1-based store index as a data URI, or null if it cannot be shown */
    private String picture(int index) {
        if (!pictureCache.containsKey(index)) {
            pictureCache.put(index, Escher.storedImage(store, index, doc, pictures));
        }
        return pictureCache.get(index);
    }

    private void readSlideList(Rec list, List<Entry> entries) {
        final List<Rec> recs = children(doc, list.body, list.end);
        int from = -1;
        for (int i = 0; i <= recs.size(); i++) {
            if (i < recs.size() && recs.get(i).type != SLIDE_PERSIST) {
                continue;
            }
            if (from >= 0) {
                final Rec head = recs.get(from);
                final Entry entry = new Entry();
                entry.ref = u32(doc, head.body);
                entry.id = u32(doc, head.body + 12);
                entry.texts = readTexts(recs.subList(from + 1, i));
                entries.add(entry);
            }
            from = i;
        }
    }

    private List<TextBlock> readTexts(List<Rec> recs) {
        final List<TextBlock> out = new ArrayList<>();
        TextBlock current = null;
        for (Rec rec : recs) {
            if (rec.type == TEXT_HEADER) {
                current = new TextBlock();
                current.type = u32(doc, rec.body);
                out.add(current);
            } else if (current == null) {
                continue;
            } else if (rec.type == TEXT_CHARS) {
                current.text = new String(doc, rec.body, rec.end - rec.body, UTF16);
            } else if (rec.type == TEXT_BYTES) {
                current.text = new String(doc, rec.body, rec.end - rec.body, ANSI);
            } else if (rec.type == STYLE_TEXT_PROP) {
                current.styleFrom = rec.body;
                current.styleTo = rec.end;
            }
        }
        return out;
    }

    // ---- colours ----

    private String[] readScheme(Rec rec) {
        if (rec == null || rec.end - rec.body < 32) {
            return null;
        }
        final String[] out = new String[8];
        for (int i = 0; i < 8; i++) {
            final int at = rec.body + i * 4;
            out[i] = rgb(doc[at] & 0xFF, doc[at + 1] & 0xFF, doc[at + 2] & 0xFF);
        }
        return out;
    }

    private static String rgb(int r, int g, int b) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
    }

    /** Text colour: a literal RGB (index 0xFE) or a scheme slot */
    private static String textColor(long value, String[] scheme) {
        if (value == -1) {
            return null;
        }
        final int index = (int) (value >>> 24);
        if (index == 0xFE) {
            return rgb((int) (value & 0xFF), (int) (value >> 8 & 0xFF), (int) (value >> 16 & 0xFF));
        }
        return index < scheme.length ? scheme[index] : null;
    }

    /** OfficeArt colour: RGB, or a scheme slot when flagged; null for system and palette colours */
    private static String artColor(int value, String[] scheme) {
        final int flags = value >>> 24;
        if ((flags & 0x08) != 0) {
            return (value & 0xFF) < scheme.length ? scheme[value & 0xFF] : null;
        }
        if ((flags & 0xF7) != 0) {
            return null;
        }
        return rgb(value & 0xFF, value >> 8 & 0xFF, value >> 16 & 0xFF);
    }

    // ---- text styles ----

    private int readPF(int pos, PF pf) {
        final int mask = u32(doc, pos);
        int p = pos + 4;
        if ((mask & 0x000F) != 0) {
            if ((mask & 0x0001) != 0) {
                pf.bullet = u16(doc, p) & 1;
            }
            p += 2;
        }
        if ((mask & 0x0080) != 0) {
            pf.bulletChar = u16(doc, p);
            p += 2;
        }
        if ((mask & 0x0010) != 0) {
            pf.bulletFont = u16(doc, p);
            p += 2;
        }
        if ((mask & 0x0040) != 0) {
            p += 2;
        }
        if ((mask & 0x0020) != 0) {
            p += 4;
        }
        if ((mask & 0x0800) != 0) {
            pf.align = Math.min(u16(doc, p), 3);
            p += 2;
        }
        if ((mask & 0x1000) != 0) {
            pf.lineSpacing = (short) u16(doc, p);
            p += 2;
        }
        if ((mask & 0x2000) != 0) {
            pf.spaceBefore = (short) u16(doc, p);
            p += 2;
        }
        if ((mask & 0x4000) != 0) {
            pf.spaceAfter = (short) u16(doc, p);
            p += 2;
        }
        if ((mask & 0x0100) != 0) {
            pf.leftMargin = (short) u16(doc, p);
            p += 2;
        }
        if ((mask & 0x0400) != 0) {
            p += 2;
        }
        if ((mask & 0x8000) != 0) {
            p += 2;
        }
        if ((mask & 0x100000) != 0) {
            p += 2 + u16(doc, p) * 4;
        }
        if ((mask & 0x10000) != 0) {
            p += 2;
        }
        if ((mask & 0xE0000) != 0) {
            p += 2;
        }
        if ((mask & 0x200000) != 0) {
            pf.rtl = u16(doc, p) == 1 ? 1 : 0;
            p += 2;
        }
        return p;
    }

    private int readCF(int pos, CF cf) {
        final int mask = u32(doc, pos);
        int p = pos + 4;
        if ((mask & 0xFFFF) != 0) {
            final int flags = u16(doc, p);
            if ((mask & 0x0001) != 0) {
                cf.bold = flags & 1;
            }
            if ((mask & 0x0002) != 0) {
                cf.italic = flags >> 1 & 1;
            }
            if ((mask & 0x0004) != 0) {
                cf.underline = flags >> 2 & 1;
            }
            p += 2;
        }
        if ((mask & 0x10000) != 0) {
            cf.font = u16(doc, p);
            p += 2;
        }
        if ((mask & 0x200000) != 0) {
            p += 2;
        }
        if ((mask & 0x400000) != 0) {
            p += 2;
        }
        if ((mask & 0x800000) != 0) {
            p += 2;
        }
        if ((mask & 0x20000) != 0) {
            cf.size = u16(doc, p);
            p += 2;
        }
        if ((mask & 0x40000) != 0) {
            cf.color = u32(doc, p) & 0xFFFFFFFFL;
            p += 4;
        }
        if ((mask & 0x80000) != 0) {
            p += 2;
        }
        return p;
    }

    private void readMasterStyle(Rec rec, Map<Integer, List<Level>> into) {
        final int levels = u16(doc, rec.body);
        int pos = rec.body + 2;
        final List<Level> out = new ArrayList<>();
        for (int l = 0; l < levels && pos + 8 <= rec.end; l++) {
            if (rec.inst >= 5) {
                pos += 2;
            }
            final Level level = new Level();
            pos = readPF(pos, level.pf);
            if (pos + 4 > rec.end) {
                break;
            }
            pos = readCF(pos, level.cf);
            out.add(level);
        }
        into.put(rec.inst, out);
    }

    /** Text types 5 to 8 store only their differences from the type they inherit */
    private static int inherits(int type) {
        switch (type) {
            case 5:
            case 7:
            case 8:
                return 1;
            case 6:
                return 0;
            default:
                return -1;
        }
    }

    private Level levelStyle(List<Level> base, List<Level> own, int level) {
        final Level a = pick(base, level);
        final Level b = pick(own, level);
        return merged(b.pf.over(a.pf), b.cf.over(a.cf));
    }

    private static Level merged(PF pf, CF cf) {
        final Level out = new Level();
        out.pf.bullet = pf.bullet;
        out.pf.bulletChar = pf.bulletChar;
        out.pf.bulletFont = pf.bulletFont;
        out.pf.align = pf.align;
        out.pf.lineSpacing = pf.lineSpacing;
        out.pf.spaceBefore = pf.spaceBefore;
        out.pf.spaceAfter = pf.spaceAfter;
        out.pf.leftMargin = pf.leftMargin;
        out.pf.rtl = pf.rtl;
        out.cf.bold = cf.bold;
        out.cf.italic = cf.italic;
        out.cf.underline = cf.underline;
        out.cf.font = cf.font;
        out.cf.size = cf.size;
        out.cf.color = cf.color;
        return out;
    }

    private static Level pick(List<Level> list, int level) {
        return list.isEmpty() ? new Level() : list.get(Math.min(level, list.size() - 1));
    }

    // ---- text ----

    /** @return the HTML of a text box, or null when it has no text */
    private String text(TextBlock block, Context context, Map<Integer, Integer> props, boolean titleLike) {
        final String raw = block.text.endsWith("\r") ? block.text.substring(0, block.text.length() - 1) : block.text;
        if (raw.trim().isEmpty()) {
            return null;
        }
        List<Level> levels = context.styles.get(block.type);
        if (levels == null) {
            levels = context.styles.get(inherits(block.type));
        }
        if (levels == null) {
            levels = otherStyles.get(block.type);
        }
        if (levels == null) {
            levels = otherStyles.get(4);
        }
        if (levels == null) {
            levels = new ArrayList<>();
        }
        final List<Level> baseLevels = inherits(block.type) >= 0 && context.styles.get(inherits(block.type)) != null
                ? context.styles.get(inherits(block.type)) : new ArrayList<>();

        // Paragraph and character runs, as (length, properties) lists
        final int total = block.text.length() + 1;
        final List<int[]> paraCounts = new ArrayList<>();
        final List<PF> paraProps = new ArrayList<>();
        final List<Integer> charCounts = new ArrayList<>();
        final List<CF> charProps = new ArrayList<>();
        if (block.styleFrom >= 0) {
            int pos = block.styleFrom;
            int seen = 0;
            while (seen < total && pos + 10 <= block.styleTo) {
                final int count = u32(doc, pos);
                final PF pf = new PF();
                final int level = u16(doc, pos + 4);
                pos = readPF(pos + 6, pf);
                paraCounts.add(new int[]{count, level});
                paraProps.add(pf);
                seen += Math.max(count, 1);
            }
            seen = 0;
            while (seen < total && pos + 8 <= block.styleTo) {
                final int count = u32(doc, pos);
                final CF cf = new CF();
                pos = readCF(pos + 4, cf);
                charCounts.add(count);
                charProps.add(cf);
                seen += Math.max(count, 1);
            }
        }

        final StringBuilder out = new StringBuilder();
        int paraRun = 0;
        int paraLeft = paraCounts.isEmpty() ? Integer.MAX_VALUE : paraCounts.get(0)[0];
        int charRun = 0;
        int charLeft = charCounts.isEmpty() ? Integer.MAX_VALUE : charCounts.get(0);

        for (String line : raw.split("\r", -1)) {
            final int level = paraRun < paraCounts.size() ? Math.min(4, paraCounts.get(paraRun)[1]) : 0;
            final Level base = levelStyle(baseLevels, levels, level);
            final PF pf = paraRun < paraProps.size() ? paraProps.get(paraRun).over(base.pf) : base.pf;
            final StringBuilder runs = new StringBuilder();
            double first = -1;
            int i = 0;
            final int length = line.length() + 1; // with the paragraph mark
            while (i < length) {
                final int take = Math.min(length - i, Math.max(charLeft, 1));
                final CF own = charRun < charProps.size() ? charProps.get(charRun) : new CF();
                final CF cf = own.over(base.cf);
                final String piece = line.substring(Math.min(i, line.length()), Math.min(line.length(), i + take));
                final double size = (cf.size != UNSET ? cf.size : titleLike ? 44 : 18) * 4 / 3.0;
                if (first < 0) {
                    first = size;
                }
                if (!piece.isEmpty()) {
                    runs.append("<span style=\"font-size:").append(fmt(size)).append("px;color:")
                            .append(orElse(textColor(cf.color, context.scheme), context.scheme[titleLike ? 3 : 1]));
                    if (cf.bold == 1) {
                        runs.append(";font-weight:bold");
                    }
                    if (cf.italic == 1) {
                        runs.append(";font-style:italic");
                    }
                    if (cf.underline == 1) {
                        runs.append(";text-decoration:underline");
                    }
                    final String font = cf.font != UNSET ? fonts.get(cf.font) : null;
                    if (font != null && !font.isEmpty()) {
                        runs.append(";font-family:'").append(font.replaceAll("[^\\p{L}\\p{N} \\-]", ""))
                                .append("',sans-serif");
                    }
                    // A vertical tab is a line break inside the paragraph
                    runs.append("\">").append(esc(piece).replace("\u000b", "<br>")).append("</span>");
                }
                i += take;
                charLeft -= take;
                if (charLeft <= 0) {
                    charRun++;
                    charLeft = charRun < charCounts.size() ? charCounts.get(charRun) : Integer.MAX_VALUE;
                }
            }
            paraLeft -= length;
            if (paraLeft <= 0) {
                paraRun++;
                paraLeft = paraRun < paraCounts.size() ? paraCounts.get(paraRun)[0] : Integer.MAX_VALUE;
            }
            if (first < 0) {
                first = (base.cf.size != UNSET ? base.cf.size : 18) * 4 / 3.0;
            }
            final boolean bullet = pf.bullet == 1;
            final double left = pf.leftMargin == UNSET ? 0 : mu(pf.leftMargin);
            final double indent = bullet ? Math.max(left, first * 1.1) : left;
            out.append("<p style=\"margin:").append(fmt(spacing(pf.spaceBefore, first))).append("px 0 ")
                    .append(fmt(spacing(pf.spaceAfter, first))).append("px;text-align:")
                    .append(ALIGN[pf.align == UNSET ? 0 : pf.align]);
            if (pf.lineSpacing != UNSET && pf.lineSpacing > 0) {
                out.append(";line-height:").append(fmt(pf.lineSpacing / 100.0));
            }
            if (pf.rtl == 1) {
                out.append(";direction:rtl");
            }
            out.append(";padding-left:").append(fmt(indent)).append("px");
            if (bullet) {
                out.append(";text-indent:-").append(fmt(first * 1.1)).append("px");
            }
            out.append("\">");
            if (bullet) {
                out.append("<span style=\"display:inline-block;font-size:").append(fmt(first)).append("px;width:")
                        .append(fmt(first * 1.1)).append("px;text-indent:0\">")
                        .append(bulletGlyph(pf.bulletChar == UNSET ? '•' : (char) pf.bulletChar,
                                pf.bulletFont == UNSET ? null : fonts.get(pf.bulletFont))).append("</span>");
            }
            out.append(runs.length() == 0 ? "<span style=\"font-size:" + fmt(first) + "px\">&nbsp;</span>" : runs)
                    .append("</p>");
        }

        final Integer anchor = props.get(0x0087);
        final String justify = anchor == null ? titleLike ? "center" : "flex-start"
                : anchor == 1 || anchor == 4 || anchor == 8 ? "center"
                : anchor == 2 || anchor == 5 || anchor == 9 ? "flex-end" : "flex-start";
        final boolean wrap = props.get(0x0085) == null || props.get(0x0085) != 2;
        return "<div class=t style=\"justify-content:" + justify + ";padding:" + fmt(inset(props, 0x0082, 4.8)) + "px "
                + fmt(inset(props, 0x0083, 9.6)) + "px " + fmt(inset(props, 0x0084, 4.8)) + "px "
                + fmt(inset(props, 0x0081, 9.6)) + "px" + (wrap ? "" : ";white-space:nowrap") + "\">" + out + "</div>";
    }

    private static double inset(Map<Integer, Integer> props, int id, double fallback) {
        final Integer value = props.get(id);
        return value == null ? fallback : value / 9525.0;
    }

    private static double spacing(int value, double first) {
        return value == UNSET ? 0 : value > 0 ? value / 100.0 * first * 1.2 : mu(-value);
    }

    /** Symbol-font bullet characters only mean something in that font, so common ones map to Unicode */
    private static String bulletGlyph(char c, String font) {
        final String name = font == null ? "" : font.toLowerCase(Locale.ROOT);
        final char plain = (c & 0xFF00) == 0xF000 ? (char) (c & 0xFF) : c;
        if (name.contains("wingdings") || name.contains("webdings")) {
            switch (plain) {
                case 'l': return "●";
                case 'n': return "■";
                case 'o': case 'p': return "□";
                case 'q': return "❑";
                case 'r': return "❒";
                case 'u': return "◆";
                case 'v': return "❖";
                case 'à': case 'è': return "➔";
                case 'ð': return "⇨";
                case 'Ø': case 'Ù': return "➢";
                case 'ü': return "✓";
                case 'û': return "✗";
                case 'þ': return "☑";
                case 'ý': return "☒";
                default: return "•";
            }
        }
        if (name.equals("symbol")) {
            switch (plain) {
                case '¨': return "♦";
                case '®': return "→";
                case 'Þ': return "⇒";
                case '-': return "−";
                default: return "•";
            }
        }
        return plain != c || c == '·' ? "•" : esc(String.valueOf(c));
    }

    // ---- masters ----

    private Master master(int id, int depth) {
        final Master known = masters.get(id);
        if (known != null) {
            return known;
        }
        Entry entry = null;
        for (Entry e : lists.get(1)) {
            if (e.id == id) {
                entry = e;
                break;
            }
        }
        final Rec rec = recordAt(entry == null ? null : persist.get(entry.ref));
        if (rec == null || depth > 3) {
            return null;
        }
        final List<Rec> parts = children(doc, rec.body, rec.end);
        // A title master is a slide that follows the main master
        final Rec atom = find(parts, SLIDE_ATOM);
        final Master parent = rec.type == SLIDE && atom != null ? master(u32(doc, atom.body + 12), depth + 1) : null;
        final Master master = new Master();
        if (parent != null) {
            master.styles.putAll(parent.styles);
        }
        for (Rec part : parts) {
            if (part.type == MASTER_STYLE) {
                readMasterStyle(part, master.styles);
            }
        }
        final String[] scheme = readScheme(last(parts, COLOR_SCHEME));
        master.scheme = scheme != null ? scheme : parent != null ? parent.scheme : DEFAULT_SCHEME;
        final Rec drawing = find(parts, DRAWING);
        master.background = background(drawing, master.scheme);
        if (master.background == null && parent != null) {
            master.background = parent.background;
        }
        final Context context = new Context();
        context.styles = master.styles;
        context.scheme = master.scheme;
        context.decorationOnly = true;
        drawing(drawing, context, master.shapes);
        masters.put(id, master);
        return master;
    }

    // ---- shapes ----

    private void drawing(Rec drawing, Context context, StringBuilder out) {
        if (drawing == null) {
            return;
        }
        final Rec dg = find(children(doc, drawing.body, drawing.end), DG);
        if (dg != null) {
            shapes(dg, context, out, f -> f, 0);
        }
    }

    /** @return a CSS background for the slide from its background shape, or null if it has none */
    private String background(Rec drawing, String[] scheme) {
        if (drawing == null) {
            return null;
        }
        final Rec dg = find(children(doc, drawing.body, drawing.end), DG);
        final Rec shape = dg == null ? null : find(children(doc, dg.body, dg.end), SP_CONTAINER);
        if (shape == null) {
            return null;
        }
        final Map<Integer, Integer> props = new HashMap<>();
        for (Rec part : children(doc, shape.body, shape.end)) {
            if (part.type == OPT) {
                readProps(doc, part, props);
            }
        }
        final int type = props.containsKey(0x0180) ? props.get(0x0180) : 0;
        final Integer pib = props.get(0x0186);
        if ((type == 2 || type == 3) && pib != null) {
            final String uri = picture(pib);
            if (uri != null) {
                return "url(" + uri + ") center/100% 100%";
            }
        }
        final Integer color = props.get(0x0181);
        final String css = color == null ? null : artColor(color, scheme);
        final Integer back = props.get(0x0182);
        final String gradient = Escher.gradient(props, orElse(css, scheme[0]),
                orElse(back == null ? null : artColor(back, scheme), "#ffffff"));
        return gradient != null ? gradient : css != null ? css : scheme[0];
    }

    private double[] anchor(List<Rec> parts) {
        final Rec child = find(parts, CHILD_ANCHOR);
        if (child != null && child.end - child.body >= 16) {
            final int l = u32(doc, child.body);
            final int t = u32(doc, child.body + 4);
            return new double[]{mu(l), mu(t), mu(u32(doc, child.body + 8) - l), mu(u32(doc, child.body + 12) - t)};
        }
        final Rec client = find(parts, CLIENT_ANCHOR);
        if (client == null) {
            return null;
        }
        if (client.end - client.body >= 16) {
            final int t = u32(doc, client.body);
            final int l = u32(doc, client.body + 4);
            return new double[]{mu(l), mu(t), mu(u32(doc, client.body + 8) - l), mu(u32(doc, client.body + 12) - t)};
        }
        if (client.end - client.body >= 8) {
            final int t = (short) u16(doc, client.body);
            final int l = (short) u16(doc, client.body + 2);
            return new double[]{mu(l), mu(t), mu((short) u16(doc, client.body + 4) - l),
                    mu((short) u16(doc, client.body + 6) - t)};
        }
        return null;
    }

    private void shapes(Rec container, Context context, StringBuilder out, UnaryOperator<double[]> map, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (Rec rec : children(doc, container.body, container.end)) {
            if (rec.type == SPGR_CONTAINER) {
                // The first shape of a group is the group itself: its frame in the parent, and the
                // coordinate box its children use
                final List<Rec> inside = children(doc, rec.body, rec.end);
                UnaryOperator<double[]> inner = map;
                if (!inside.isEmpty() && inside.get(0).type == SP_CONTAINER) {
                    final List<Rec> parts = children(doc, inside.get(0).body, inside.get(0).end);
                    final Rec box = find(parts, SPGR);
                    final double[] frame = anchor(parts);
                    if (box != null && frame != null && depth > 0) {
                        final double cx = mu(u32(doc, box.body));
                        final double cy = mu(u32(doc, box.body + 4));
                        final double cw = mu(u32(doc, box.body + 8)) - cx == 0 ? 1 : mu(u32(doc, box.body + 8)) - cx;
                        final double ch = mu(u32(doc, box.body + 12)) - cy == 0 ? 1 : mu(u32(doc, box.body + 12)) - cy;
                        final double[] outer = map.apply(frame);
                        inner = f -> new double[]{outer[0] + (f[0] - cx) * outer[2] / cw,
                                outer[1] + (f[1] - cy) * outer[3] / ch, f[2] * outer[2] / cw, f[3] * outer[3] / ch};
                    }
                }
                shapes(rec, context, out, inner, depth + 1);
            } else if (rec.type == SP_CONTAINER) {
                shape(rec, context, out, map);
            }
        }
    }

    private void shape(Rec rec, Context context, StringBuilder out, UnaryOperator<double[]> map) {
        final List<Rec> parts = children(doc, rec.body, rec.end);
        final Rec sp = find(parts, SP);
        if (sp == null) {
            return;
        }
        final int flags = u32(doc, sp.body + 4);
        if ((flags & 0x0005) != 0 || (flags & 0x0008) != 0 || (flags & 0x0400) != 0) {
            return; // the group shape, a deleted shape, or the background
        }
        final double[] frame = anchor(parts);
        if (frame == null) {
            return;
        }
        final Map<Integer, Integer> props = new HashMap<>();
        for (Rec part : parts) {
            if (part.type == OPT || part.type == TERTIARY_OPT) {
                readProps(doc, part, props);
            }
        }
        final Rec clientData = find(parts, CLIENT_DATA);
        final Rec placeholder = clientData == null ? null : find(children(doc, clientData.body, clientData.end), PLACEHOLDER);
        if (context.decorationOnly && placeholder != null) {
            return;
        }
        final double[] box = map.apply(frame);
        final StringBuilder style = new StringBuilder("position:absolute;left:").append(fmt(box[0])).append("px;top:")
                .append(fmt(box[1])).append("px;width:").append(fmt(Math.max(box[2], 0))).append("px;height:")
                .append(fmt(Math.max(box[3], 0))).append("px");
        final Integer rotation = props.get(0x0004);
        final StringBuilder transform = new StringBuilder();
        if (rotation != null && rotation != 0) {
            transform.append("rotate(").append(fmt(rotation / 65536.0)).append("deg)");
        }
        if ((flags & 0x40) != 0) {
            transform.append(" scaleX(-1)");
        }
        if ((flags & 0x80) != 0) {
            transform.append(" scaleY(-1)");
        }
        if (transform.length() > 0) {
            style.append(";transform:").append(transform.toString().trim());
        }

        // Text: inline in the shape, or a reference into the slide's outline text (placeholders)
        String text = null;
        final Rec textbox = find(parts, CLIENT_TEXTBOX);
        if (textbox != null) {
            final List<Rec> inner = children(doc, textbox.body, textbox.end);
            final Rec ref = find(inner, OUTLINE_TEXT_REF);
            TextBlock block = null;
            if (ref != null) {
                final int index = u32(doc, ref.body);
                block = index >= 0 && index < context.texts.size() ? context.texts.get(index) : null;
            } else {
                final List<TextBlock> own = readTexts(inner);
                block = own.isEmpty() ? null : own.get(0);
            }
            if (block != null) {
                text = text(block, context, props, block.type == 0 || block.type == 6);
            }
        }

        final Integer pib = props.get(0x0104);
        if ((pib != null && pib != 0) || sp.inst == 75) {
            final String uri = pib == null ? null : picture(pib);
            if (uri != null) {
                picture(out, style.toString(), uri, props);
            }
            return;
        }
        if ((flags & 0x10) != 0) {
            out.append("<div style=\"").append(style).append(";border:1px dashed #999\"></div>");
            return;
        }

        // Fill and outline. Absent booleans mean "on", except for text boxes, which PowerPoint draws bare
        final boolean bare = sp.inst == 202 || placeholder != null;
        final Integer fillBools = props.get(0x01BF);
        final boolean filled = fillBools != null && (fillBools & 0x100000) != 0 ? (fillBools & 0x10) != 0 : !bare;
        final Integer lineBools = props.get(0x01FF);
        final boolean lined = lineBools != null && (lineBools & 0x80000) != 0 ? (lineBools & 0x8) != 0 : !bare;
        final int fillType = props.containsKey(0x0180) ? props.get(0x0180) : 0;
        final String fillPicture = filled && (fillType == 2 || fillType == 3) && props.containsKey(0x0186)
                ? picture(props.get(0x0186)) : null;
        String fill = null;
        if (filled) {
            final Integer color = props.get(0x0181);
            fill = color == null ? context.scheme[4] : artColor(color, context.scheme);
            final Integer back = props.get(0x0182);
            final String gradient = Escher.gradient(props, fill,
                    orElse(back == null ? null : artColor(back, context.scheme), "#ffffff"));
            if (gradient != null) {
                fill = gradient;
            }
        }
        String stroke = null;
        if (lined) {
            final Integer color = props.get(0x01C0);
            stroke = color == null ? context.scheme[1] : orElse(artColor(color, context.scheme), context.scheme[1]);
        }
        if (fill == null && stroke == null && text == null && fillPicture == null) {
            return;
        }
        final int geom = sp.inst;
        if (geom == 20 || geom == 32) {
            line(out, style.toString(), stroke == null ? context.scheme[1] : stroke, props);
            return;
        }
        style.append(";box-sizing:border-box");
        if (fillPicture != null) {
            style.append(";background:url(").append(fillPicture).append(") center/100% 100%");
        } else if (fill != null) {
            style.append(";background:").append(fill);
        }
        if (stroke != null) {
            final Integer width = props.get(0x01CB);
            final Integer dash = props.get(0x01CE);
            style.append(";border:").append(fmt(Math.max(0.5, (width == null ? 9525 : width) / 9525.0))).append("px ")
                    .append(dash != null && dash != 0 ? "dashed " : "solid ").append(stroke);
        }
        style.append(Escher.geometry(geom, Math.min(box[2], box[3])));
        out.append("<div style=\"").append(style).append("\">").append(text == null ? "" : text).append("</div>");
    }

    private void picture(StringBuilder out, String style, String uri, Map<Integer, Integer> props) {
        final double left = fixed(props.get(0x0102)), top = fixed(props.get(0x0100));
        final double right = fixed(props.get(0x0103)), bottom = fixed(props.get(0x0101));
        final double keepW = 1 - left - right, keepH = 1 - top - bottom;
        out.append("<div style=\"").append(style).append(";overflow:hidden\"><img src=\"").append(uri).append("\" ");
        if (keepW > 0.01 && keepH > 0.01 && (left != 0 || top != 0 || right != 0 || bottom != 0)) {
            out.append("style=\"position:absolute;width:").append(fmt(100 / keepW)).append("%;height:")
                    .append(fmt(100 / keepH)).append("%;left:").append(fmt(-left / keepW * 100)).append("%;top:")
                    .append(fmt(-top / keepH * 100)).append("%\"></div>");
        } else {
            out.append("style=\"width:100%;height:100%\"></div>");
        }
    }

    /** A straight line along the diagonal of its frame; flips come from the shape's transform */
    private void line(StringBuilder out, String style, String color, Map<Integer, Integer> props) {
        final Integer width = props.get(0x01CB);
        final Integer dash = props.get(0x01CE);
        out.append("<svg style=\"").append(style).append(";overflow:visible\" viewBox=\"0 0 1 1\" ")
                .append("preserveAspectRatio=\"none\"><line x1=0 y1=0 x2=1 y2=1 stroke=\"").append(color)
                .append("\" stroke-width=\"").append(fmt(Math.max(0.75, (width == null ? 9525 : width) / 9525.0)))
                .append("\" vector-effect=\"non-scaling-stroke\"")
                .append(dash != null && dash != 0 ? " stroke-dasharray=\"6 4\"" : "").append("/></svg>");
    }

    // ---- small helpers ----

    /** Master units (576 per inch) to CSS px */
    private static double mu(int value) {
        return value / 6.0;
    }

    private static double fixed(Integer value) {
        return value == null ? 0 : value / 65536.0;
    }

    private static String orElse(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
