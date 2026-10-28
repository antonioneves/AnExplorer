package dev.dworks.apps.anexplorer.misc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Reads the text out of the binary Office formats: Word (.doc), Excel (.xls, .xlsb) and PowerPoint
 * (.ppt, .pps). Only the text and cell values are read, not the layout or the number formats, so
 * dates show as their serial numbers. The OLE2 container and record layouts follow the public
 * [MS-CFB], [MS-DOC], [MS-XLS], [MS-PPT] and [MS-XLSB] specifications.
 */
public final class BinaryOffice {

    private static final Charset ANSI = Charset.forName("windows-1252");
    private static final Charset UTF16 = Charset.forName("UTF-16LE");
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;

    /** One worksheet: its name and the rows that have cells, with the look of each cell */
    public static final class Sheet {
        public final String name;
        public final List<List<String>> rows = new ArrayList<>();
        /** The 1-based sheet row number of each entry in {@link #rows}, since empty rows are left out */
        public final List<Integer> rowNumbers = new ArrayList<>();
        /** The CSS of each cell, in the same shape as {@link #rows}; empty for a plain cell */
        public final List<List<String>> styles = new ArrayList<>();
        /** Column widths in pixels, by 0-based column */
        public final Map<Integer, Integer> columnWidths = new TreeMap<>();
        /** Merged ranges as first row, last row, first column, last column (0-based) */
        public final List<int[]> merges = new ArrayList<>();

        Sheet(String name) {
            this.name = name;
        }
    }

    private BinaryOffice() {
    }

    // ---- OLE2 container ----

    /** A compound file: the named streams that sit in it */
    static final class Ole2 {
        private final byte[] data;
        private final int sectorSize;
        private final int miniCutoff;
        private final int[] fat;
        private int[] miniFat = new int[0];
        private byte[] miniStream = new byte[0];
        private final byte[] directory;

        Ole2(byte[] data) throws IOException {
            this.data = data;
            if (data.length < 512 || u32(data, 0) != (int) 0xE011CFD0L || u32(data, 4) != (int) 0xE11AB1A1L) {
                throw new IOException("Not an OLE2 file");
            }
            sectorSize = 1 << u16(data, 0x1E);
            if (sectorSize < 512 || sectorSize > 4096) {
                throw new IOException("Bad sector size");
            }
            miniCutoff = u32(data, 0x38);
            final int fatCount = u32(data, 0x2C);
            final List<Integer> fatSectors = new ArrayList<>();
            for (int i = 0; i < 109 && fatSectors.size() < fatCount; i++) {
                fatSectors.add(u32(data, 0x4C + i * 4));
            }
            int difat = u32(data, 0x44);
            for (int d = u32(data, 0x48); d > 0 && difat >= 0 && fatSectors.size() < fatCount; d--) {
                final int at = offset(difat);
                for (int i = 0; i < sectorSize / 4 - 1 && fatSectors.size() < fatCount; i++) {
                    fatSectors.add(u32(data, at + i * 4));
                }
                difat = u32(data, at + sectorSize - 4);
            }
            fat = new int[fatSectors.size() * sectorSize / 4];
            for (int i = 0; i < fatSectors.size(); i++) {
                final int at = offset(fatSectors.get(i));
                for (int j = 0; j < sectorSize / 4; j++) {
                    fat[i * sectorSize / 4 + j] = u32(data, at + j * 4);
                }
            }
            directory = read(u32(data, 0x30), Long.MAX_VALUE, false);
            if (directory.length < 128) {
                throw new IOException("No directory");
            }
            miniStream = read(u32(directory, 0x74), size(directory, 0), false);
            final byte[] mini = read(u32(data, 0x3C), Long.MAX_VALUE, false);
            miniFat = new int[mini.length / 4];
            for (int i = 0; i < miniFat.length; i++) {
                miniFat[i] = u32(mini, i * 4);
            }
        }

        private int offset(int sector) throws IOException {
            final long at = ((long) sector + 1) * sectorSize;
            if (sector < 0 || at + sectorSize > data.length) {
                throw new IOException("Sector out of range");
            }
            return (int) at;
        }

        private static long size(byte[] dir, int entry) {
            return u32(dir, entry * 128 + 0x78) & 0xFFFFFFFFL;
        }

        private byte[] read(int start, long size, boolean mini) throws IOException {
            if (size > MAX_FILE_BYTES && size != Long.MAX_VALUE) {
                throw new IOException("Stream too large");
            }
            final int unit = mini ? 64 : sectorSize;
            final int[] table = mini ? miniFat : fat;
            final byte[] source = mini ? miniStream : data;
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            int sector = start;
            for (int guard = 0; sector >= 0 && sector < table.length && out.size() < size
                    && guard <= table.length; guard++) {
                final long at = mini ? (long) sector * unit : ((long) sector + 1) * unit;
                if (at >= source.length) {
                    break;
                }
                out.write(source, (int) at, (int) Math.min(unit, source.length - at));
                if (out.size() > MAX_FILE_BYTES) {
                    throw new IOException("Stream too large");
                }
                sector = table[sector];
            }
            final byte[] bytes = out.toByteArray();
            return size < bytes.length ? java.util.Arrays.copyOf(bytes, (int) size) : bytes;
        }

        /** @return the stream with that name, or null when the file has none */
        byte[] stream(String name) throws IOException {
            for (int entry = 0; (entry + 1) * 128 <= directory.length; entry++) {
                final int at = entry * 128;
                final int nameLength = u16(directory, at + 0x40);
                if (directory[at + 0x42] != 2 || nameLength < 2 || nameLength > 64) {
                    continue;
                }
                if (new String(directory, at, nameLength - 2, UTF16).equalsIgnoreCase(name)) {
                    final long size = size(directory, entry);
                    return read(u32(directory, at + 0x74), size, size < miniCutoff);
                }
            }
            return null;
        }
    }

    // ---- Word ----

    /** @return the body text of a Word 97 or later document */
    public static String doc(byte[] file) throws IOException {
        final Ole2 ole = new Ole2(file);
        final byte[] word = ole.stream("WordDocument");
        if (word == null || word.length < 0x40 || u16(word, 0) != 0xA5EC) {
            throw new IOException("Not a Word document");
        }
        final int flags = u16(word, 0x0A);
        if ((flags & 0x0100) != 0) {
            throw new IOException("Encrypted");
        }
        final byte[] table = ole.stream((flags & 0x0200) != 0 ? "1Table" : "0Table");
        final int csw = u16(word, 32);
        final int lwAt = 34 + csw * 2;
        final int lw = u16(word, lwAt);
        final int bodyLength = u32(word, lwAt + 2 + 12);
        final int pairsAt = lwAt + 2 + lw * 4 + 2;
        final int clxAt = pairsAt + 33 * 8;
        if (table == null || clxAt + 8 > word.length) {
            throw new IOException("No text table");
        }
        int pos = u32(word, clxAt);
        final int end = Math.min(table.length, pos + u32(word, clxAt + 4));
        while (pos >= 0 && pos < end && table[pos] == 1) {
            pos += 3 + u16(table, pos + 1);
        }
        if (pos < 0 || pos + 5 > end || table[pos] != 2) {
            throw new IOException("No pieces");
        }
        final int pieces = (u32(table, pos + 1) - 4) / 12;
        final int cps = pos + 5;
        final int pcds = cps + (pieces + 1) * 4;
        if (pieces < 1 || pcds + pieces * 8 > table.length) {
            throw new IOException("Bad piece table");
        }
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < pieces; i++) {
            final int from = u32(table, cps + i * 4);
            final int to = Math.min(u32(table, cps + i * 4 + 4), bodyLength);
            if (from >= bodyLength) {
                break;
            }
            final int raw = u32(table, pcds + i * 8 + 2);
            final boolean compressed = (raw & 0x40000000) != 0;
            final int at = compressed ? (raw & 0x3FFFFFFF) / 2 : raw & 0x3FFFFFFF;
            final int length = Math.max(0, Math.min(to - from, (word.length - at) / (compressed ? 1 : 2)));
            if (at >= 0 && at < word.length) {
                text.append(new String(word, at, length * (compressed ? 1 : 2), compressed ? ANSI : UTF16));
            }
        }
        return wordText(text);
    }

    /** Drops field codes and control characters, and turns cell and paragraph marks into tabs and lines */
    private static String wordText(CharSequence raw) {
        final StringBuilder out = new StringBuilder();
        final boolean[] code = new boolean[32];
        int depth = 0;
        int hidden = 0;
        boolean cellEnd = false;
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == 0x13) {
                if (depth < code.length) {
                    code[depth++] = true;
                    hidden++;
                }
            } else if (c == 0x14) {
                if (depth > 0 && code[depth - 1]) {
                    code[depth - 1] = false;
                    hidden--;
                }
            } else if (c == 0x15) {
                if (depth > 0) {
                    if (code[--depth]) {
                        hidden--;
                    }
                }
            } else if (hidden == 0) {
                if (c == 0x07) {
                    if (cellEnd && out.length() > 0) {
                        out.setCharAt(out.length() - 1, '\n');
                        cellEnd = false;
                    } else {
                        out.append('\t');
                        cellEnd = true;
                    }
                    continue;
                }
                cellEnd = false;
                if (c == '\r' || c == 0x0B || c == 0x0C) {
                    out.append('\n');
                } else if (c == 0x1E) {
                    out.append('-');
                } else if (c >= 0x20 || c == '\t' || c == '\n') {
                    out.append(c);
                }
            }
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    // ---- cell looks shared by the Excel formats ----

    private static final class Cell {
        final String text;
        final boolean numeric;
        final int xf;

        Cell(String text, boolean numeric, int xf) {
            this.text = text;
            this.numeric = numeric;
            this.xf = xf;
        }
    }

    private static final class Font {
        boolean bold, italic, strike, underline;
        float size = 10;
        String color;
    }

    /** What a cell format record says: its CSS, whether it aligns itself, and its number format */
    private static final class Xf {
        String css = "";
        boolean aligned;
        int format;
    }

    /** The fonts, number formats and cell formats a workbook defines, and how its dates count */
    private static final class Workbook {
        final List<Font> fonts = new ArrayList<>();
        final Map<Integer, String> formats = new java.util.HashMap<>();
        final List<Xf> xfs = new ArrayList<>();
        String[] palette;
        boolean date1904;

        /** @return the number as text under the cell's number format */
        String number(int xf, double value) {
            final String code = xf >= 0 && xf < xfs.size() ? code(xfs.get(xf).format) : null;
            return SheetFormat.format(value, code, date1904);
        }

        String code(int id) {
            final String custom = formats.get(id);
            return custom != null ? custom : SheetFormat.builtIn(id);
        }

        String css(int xf, boolean numeric) {
            if (xf < 0 || xf >= xfs.size()) {
                return numeric ? "text-align:right;" : "";
            }
            final Xf x = xfs.get(xf);
            return numeric && !x.aligned ? x.css + "text-align:right;" : x.css;
        }

        /** Builds the CSS of a cell format from the pieces its record points at */
        Xf make(int font, int format, int align, boolean wrap, int[] borders, String fill) {
            final Xf x = new Xf();
            x.format = format;
            final StringBuilder css = new StringBuilder();
            final Font f = font >= 0 && font < fonts.size() ? fonts.get(font) : null;
            final Font base = fonts.isEmpty() ? null : fonts.get(0);
            if (f != null) {
                if (f.bold) {
                    css.append("font-weight:bold;");
                }
                if (f.italic) {
                    css.append("font-style:italic;");
                }
                if (f.underline || f.strike) {
                    css.append("text-decoration:").append(f.underline ? "underline " : "")
                            .append(f.strike ? "line-through" : "").append(';');
                }
                if (base != null && Math.abs(f.size - base.size) > 0.1f) {
                    css.append("font-size:").append(f.size).append("pt;");
                }
                if (f.color != null) {
                    css.append("color:").append(f.color).append(';');
                }
            }
            if (fill != null) {
                css.append("background:").append(fill).append(';');
            }
            if (align >= 1 && align <= 3) {
                css.append("text-align:").append(align == 1 ? "left" : align == 2 ? "center" : "right").append(';');
                x.aligned = true;
            }
            if (wrap) {
                css.append("white-space:pre-wrap;");
            }
            final String[] sides = {"left", "right", "top", "bottom"};
            for (int i = 0; i < 4; i++) {
                if (borders[i] > 0) {
                    css.append("border-").append(sides[i]).append(':').append(borders[i] >= 5 ? 3 : borders[i] == 2 ? 2 : 1)
                            .append("px solid #333;");
                }
            }
            x.css = css.toString();
            return x;
        }
    }

    // ---- Excel 97-2003 ----

    /** @return the worksheets of a BIFF8 workbook */
    public static List<Sheet> xls(byte[] file, int maxRows, int maxColumns) throws IOException {
        final Ole2 ole = new Ole2(file);
        byte[] book = ole.stream("Workbook");
        if (book == null) {
            book = ole.stream("Book");
        }
        if (book == null || book.length < 8 || u16(book, 0) != 0x0809) {
            throw new IOException("Not an Excel workbook");
        }
        if (u16(book, 4) != 0x0600) {
            throw new IOException("Excel version not supported");
        }
        final List<String> shared = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        final Workbook wb = new Workbook();
        for (int pos = 0; pos + 4 <= book.length; ) {
            final int type = u16(book, pos);
            final int length = u16(book, pos + 2);
            final int data = pos + 4;
            if (type == 0x00FC) {
                final Segments segments = new Segments(book);
                segments.add(data, Math.min(book.length, data + length));
                int next = data + length;
                while (next + 4 <= book.length && u16(book, next) == 0x003C) {
                    final int partLength = u16(book, next + 2);
                    segments.add(next + 4, Math.min(book.length, next + 4 + partLength));
                    next += 4 + partLength;
                }
                sharedStrings(segments, shared);
            } else if (type == 0x0085 && data + 8 <= book.length) {
                final int chars = book[data + 6] & 0xFF;
                final boolean wide = (book[data + 7] & 1) != 0;
                final int size = Math.min(chars * (wide ? 2 : 1), book.length - data - 8);
                if (book[data + 5] == 0) {
                    names.add(new String(book, data + 8, size, wide ? UTF16 : ANSI));
                    offsets.add(u32(book, data));
                }
            } else if (type == 0x0031 && length >= 14) {
                xlsFont(wb, book, data, length);
            } else if (type == 0x041E && length >= 5) {
                final int chars = u16(book, data + 2);
                final boolean wide = (book[data + 4] & 1) != 0;
                final int size = Math.min(chars * (wide ? 2 : 1), Math.max(0, length - 5));
                wb.formats.put(u16(book, data), new String(book, data + 5, size, wide ? UTF16 : ANSI));
            } else if (type == 0x00E0 && length >= 20) {
                xlsXf(wb, book, data);
            } else if (type == 0x0092 && length >= 2) {
                final int count = Math.min(u16(book, data), (length - 2) / 4);
                wb.palette = new String[count];
                for (int i = 0; i < count; i++) {
                    wb.palette[i] = String.format("#%02x%02x%02x", book[data + 2 + i * 4] & 0xFF,
                            book[data + 3 + i * 4] & 0xFF, book[data + 4 + i * 4] & 0xFF);
                }
            } else if (type == 0x0022 && length >= 2) {
                wb.date1904 = u16(book, data) != 0;
            } else if (type == 0x000A) {
                break;
            }
            pos = data + length;
        }
        final List<Sheet> sheets = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            sheets.add(xlsSheet(book, names.get(i), offsets.get(i), shared, wb, maxRows, maxColumns));
        }
        if (sheets.isEmpty()) {
            throw new IOException("No sheets");
        }
        return sheets;
    }

    private static void xlsFont(Workbook wb, byte[] book, int d, int length) {
        final Font font = new Font();
        font.size = u16(book, d) / 20f;
        final int flags = u16(book, d + 2);
        font.italic = (flags & 2) != 0;
        font.strike = (flags & 8) != 0;
        font.bold = u16(book, d + 6) >= 700;
        font.underline = (book[d + 10] & 0xFF) != 0;
        final int color = u16(book, d + 4);
        font.color = color == 0x7FFF || color == 64 ? null : SheetFormat.color(color, wb.palette);
        wb.fonts.add(font);
        if (wb.fonts.size() == 4) {
            // BIFF numbers fonts from 0 to 3 and then from 5: index 4 is never used
            wb.fonts.add(new Font());
        }
    }

    private static void xlsXf(Workbook wb, byte[] book, int d) {
        final int align = book[d + 6] & 7;
        final boolean wrap = (book[d + 6] & 8) != 0;
        final int lines = u16(book, d + 10);
        final int[] borders = {lines & 0xF, lines >> 4 & 0xF, lines >> 8 & 0xF, lines >> 12 & 0xF};
        final int pattern = u32(book, d + 14) >>> 26;
        final int colors = u16(book, d + 18);
        final String fill = pattern == 1 ? SheetFormat.color(colors & 0x7F, wb.palette) : null;
        wb.xfs.add(wb.make(u16(book, d), u16(book, d + 2), align, wrap, borders, fill));
    }

    /** The record data of a string table, which a CONTINUE record can cut in the middle of a string */
    private static final class Segments {
        private final byte[] bytes;
        private final List<int[]> parts = new ArrayList<>();
        private int part;
        private int pos = -1;

        Segments(byte[] bytes) {
            this.bytes = bytes;
        }

        void add(int start, int end) {
            parts.add(new int[]{start, end});
            if (pos < 0) {
                pos = start;
            }
        }

        private void settle() throws IOException {
            while (part < parts.size() && pos >= parts.get(part)[1]) {
                if (++part < parts.size()) {
                    pos = parts.get(part)[0];
                }
            }
            if (part >= parts.size()) {
                throw new IOException("End of strings");
            }
        }

        int u8() throws IOException {
            settle();
            return bytes[pos++] & 0xFF;
        }

        int u16() throws IOException {
            final int low = u8();
            return low | u8() << 8;
        }

        int u32() throws IOException {
            final int low = u16();
            return low | u16() << 16;
        }

        void skip(long count) throws IOException {
            while (count > 0) {
                settle();
                final int take = (int) Math.min(count, parts.get(part)[1] - pos);
                pos += take;
                count -= take;
            }
        }

        /** Reads characters; each part that continues the string starts with its own width flag */
        String chars(int count, boolean wide) throws IOException {
            final StringBuilder out = new StringBuilder();
            while (count > 0) {
                if (part < parts.size() && pos >= parts.get(part)[1]) {
                    if (++part >= parts.size()) {
                        throw new IOException("End of strings");
                    }
                    pos = parts.get(part)[0];
                    wide = (u8() & 1) != 0;
                    continue;
                }
                settle();
                final int size = wide ? 2 : 1;
                final int take = Math.min(count, (parts.get(part)[1] - pos) / size);
                if (take == 0) {
                    throw new IOException("Split character");
                }
                out.append(new String(bytes, pos, take * size, wide ? UTF16 : ANSI));
                pos += take * size;
                count -= take;
            }
            return out.toString();
        }
    }

    private static void sharedStrings(Segments in, List<String> out) {
        try {
            in.u32();
            final int unique = in.u32();
            for (int i = 0; i < unique; i++) {
                final int chars = in.u16();
                final int flags = in.u8();
                final int runs = (flags & 8) != 0 ? in.u16() : 0;
                final int extra = (flags & 4) != 0 ? in.u32() : 0;
                out.add(in.chars(chars, (flags & 1) != 0));
                in.skip(runs * 4L + (extra & 0xFFFFFFFFL));
            }
        } catch (IOException e) {
            // A damaged table keeps the strings read so far
        }
    }

    private static Sheet xlsSheet(byte[] book, String name, int start, List<String> shared, Workbook wb, int maxRows,
                                  int maxColumns) {
        final Sheet sheet = new Sheet(name);
        final TreeMap<Integer, TreeMap<Integer, Cell>> cells = new TreeMap<>();
        int formulaXf = -1;
        int depth = 0;
        int formulaRow = -1;
        int formulaColumn = -1;
        for (int pos = start; pos >= 0 && pos + 4 <= book.length; ) {
            final int type = u16(book, pos);
            final int length = u16(book, pos + 2);
            final int d = pos + 4;
            pos = d + length;
            if (pos > book.length) {
                break;
            }
            if (type == 0x0809) {
                depth++;
            } else if (type == 0x000A) {
                if (--depth <= 0) {
                    break;
                }
            } else if (type == 0x00FD && length >= 10) {
                final int at = u32(book, d + 6);
                put(cells, u16(book, d), u16(book, d + 2), at >= 0 && at < shared.size() ? shared.get(at) : "",
                        false, u16(book, d + 4));
            } else if (type == 0x0204 && length >= 9) {
                final int chars = u16(book, d + 6);
                final boolean wide = (book[d + 8] & 1) != 0;
                final int size = Math.min(chars * (wide ? 2 : 1), Math.max(0, length - 9));
                put(cells, u16(book, d), u16(book, d + 2), new String(book, d + 9, size, wide ? UTF16 : ANSI),
                        false, u16(book, d + 4));
            } else if (type == 0x0203 && length >= 14) {
                final int xf = u16(book, d + 4);
                put(cells, u16(book, d), u16(book, d + 2),
                        wb.number(xf, Double.longBitsToDouble(i64(book, d + 6))), true, xf);
            } else if (type == 0x027E && length >= 10) {
                final int xf = u16(book, d + 4);
                put(cells, u16(book, d), u16(book, d + 2), wb.number(xf, rk(u32(book, d + 6))), true, xf);
            } else if (type == 0x00BD && length >= 6) {
                final int row = u16(book, d);
                final int first = u16(book, d + 2);
                for (int i = 0; i < (length - 6) / 6; i++) {
                    final int xf = u16(book, d + 4 + i * 6);
                    put(cells, row, first + i, wb.number(xf, rk(u32(book, d + 4 + i * 6 + 2))), true, xf);
                }
            } else if (type == 0x0201 && length >= 6) {
                put(cells, u16(book, d), u16(book, d + 2), "", false, u16(book, d + 4));
            } else if (type == 0x00BE && length >= 6) {
                final int row = u16(book, d);
                final int first = u16(book, d + 2);
                for (int i = 0; i < (length - 6) / 2; i++) {
                    put(cells, row, first + i, "", false, u16(book, d + 4 + i * 2));
                }
            } else if (type == 0x0205 && length >= 8) {
                put(cells, u16(book, d), u16(book, d + 2),
                        book[d + 7] != 0 ? "#ERR" : book[d + 6] != 0 ? "TRUE" : "FALSE", false, u16(book, d + 4));
            } else if (type == 0x0006 && length >= 14) {
                formulaRow = u16(book, d);
                formulaColumn = u16(book, d + 2);
                formulaXf = u16(book, d + 4);
                if (u16(book, d + 12) == 0xFFFF) {
                    final int kind = book[d + 6];
                    if (kind == 1) {
                        put(cells, formulaRow, formulaColumn, book[d + 8] != 0 ? "TRUE" : "FALSE", false, formulaXf);
                    } else if (kind == 2) {
                        put(cells, formulaRow, formulaColumn, "#ERR", false, formulaXf);
                    }
                } else {
                    put(cells, formulaRow, formulaColumn,
                            wb.number(formulaXf, Double.longBitsToDouble(i64(book, d + 6))), true, formulaXf);
                }
            } else if (type == 0x0207 && length >= 3 && formulaRow >= 0) {
                final int chars = u16(book, d);
                final boolean wide = (book[d + 2] & 1) != 0;
                final int size = Math.min(chars * (wide ? 2 : 1), Math.max(0, length - 3));
                put(cells, formulaRow, formulaColumn, new String(book, d + 3, size, wide ? UTF16 : ANSI), false,
                        formulaXf);
            } else if (type == 0x007D && length >= 12) {
                final int width = Math.round(u16(book, d + 4) / 256f * 7 + 5);
                for (int c = u16(book, d); c <= Math.min(u16(book, d + 2), 255) && c < 256; c++) {
                    sheet.columnWidths.put(c, width);
                }
            } else if (type == 0x00E5 && length >= 2) {
                for (int i = 0; i < Math.min(u16(book, d), (length - 2) / 8); i++) {
                    final int at = d + 2 + i * 8;
                    sheet.merges.add(new int[]{u16(book, at), u16(book, at + 2), u16(book, at + 4), u16(book, at + 6)});
                }
            }
        }
        rows(cells, sheet, wb, maxRows, maxColumns);
        return sheet;
    }

    private static void put(TreeMap<Integer, TreeMap<Integer, Cell>> cells, int row, int column, String text,
                             boolean numeric, int xf) {
        TreeMap<Integer, Cell> line = cells.get(row);
        if (line == null) {
            line = new TreeMap<>();
            cells.put(row, line);
        }
        line.put(column, new Cell(text, numeric, xf));
    }

    /** Fills the gaps between cells with empty strings so the columns line up */
    private static void rows(TreeMap<Integer, TreeMap<Integer, Cell>> cells, Sheet sheet, Workbook wb, int maxRows,
                             int maxColumns) {
        for (Map.Entry<Integer, TreeMap<Integer, Cell>> line : cells.entrySet()) {
            if (sheet.rows.size() >= maxRows) {
                break;
            }
            final List<String> row = new ArrayList<>();
            final List<String> styles = new ArrayList<>();
            for (Map.Entry<Integer, Cell> cell : line.getValue().entrySet()) {
                if (cell.getKey() >= maxColumns) {
                    break;
                }
                while (row.size() < cell.getKey()) {
                    row.add("");
                    styles.add("");
                }
                row.add(cell.getValue().text);
                styles.add(wb.css(cell.getValue().xf, cell.getValue().numeric));
            }
            sheet.rows.add(row);
            sheet.styles.add(styles);
            sheet.rowNumbers.add(line.getKey() + 1);
        }
    }

    private static double rk(int value) {
        final double number = (value & 2) != 0 ? value >> 2
                : Double.longBitsToDouble((value & 0xFFFFFFFCL) << 32);
        return (value & 1) != 0 ? number / 100 : number;
    }

    // ---- Excel binary workbook (.xlsb) ----

    /** @return the worksheets of an .xlsb, given its zip parts */
    public static List<Sheet> xlsb(Map<String, byte[]> parts, int maxRows, int maxColumns) throws IOException {
        final byte[] workbook = parts.get("xl/workbook.bin");
        if (workbook == null) {
            throw new IOException("Not an XLSB workbook");
        }
        final List<String> shared = new ArrayList<>();
        final byte[] strings = parts.get("xl/sharedStrings.bin");
        if (strings != null) {
            for (Record r = new Record(strings); r.next(); ) {
                if (r.id == 0x13 && r.length >= 5) {
                    shared.add(wide(strings, r.data + 1));
                }
            }
        }
        final Workbook wb = new Workbook();
        final byte[] styles = parts.get("xl/styles.bin");
        if (styles != null) {
            xlsbStyles(wb, styles);
        }
        final List<Sheet> sheets = new ArrayList<>();
        for (Record r = new Record(workbook); r.next(); ) {
            if (r.id == 0x99 && r.length >= 4) {
                wb.date1904 = (u32(workbook, r.data) & 1) != 0;
            }
            if (r.id != 0x9C || r.length < 12) {
                continue;
            }
            final int relLength = u32(workbook, r.data + 8);
            final int nameAt = r.data + 12 + Math.max(0, relLength) * 2;
            if (relLength < 0 || nameAt + 4 > r.data + r.length) {
                continue;
            }
            final String rel = wide(workbook, r.data + 8);
            final String name = wide(workbook, nameAt);
            final byte[] sheet = parts.get(sheetPath(parts, rel));
            if (sheet != null) {
                sheets.add(xlsbSheet(sheet, name, shared, wb, maxRows, maxColumns));
            }
        }
        if (sheets.isEmpty()) {
            throw new IOException("No sheets");
        }
        return sheets;
    }

    /** The relationships file maps the sheet's relation id to its part, like "worksheets/sheet1.bin" */
    private static String sheetPath(Map<String, byte[]> parts, String rel) {
        final byte[] rels = parts.get("xl/_rels/workbook.bin.rels");
        if (rels == null) {
            return "";
        }
        final String xml = new String(rels, java.nio.charset.StandardCharsets.UTF_8);
        final java.util.regex.Matcher tag = java.util.regex.Pattern.compile("<Relationship\\b[^>]*>").matcher(xml);
        while (tag.find()) {
            final String element = tag.group();
            if (rel.equals(attribute(element, "Id"))) {
                final String target = attribute(element, "Target");
                if (target == null) {
                    return "";
                }
                return target.startsWith("/") ? target.substring(1) : "xl/" + target;
            }
        }
        return "";
    }

    private static String attribute(String element, String name) {
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(element);
        return m.find() ? m.group(1) : null;
    }

    /** XLWideString: a 32-bit length, then UTF-16 characters */
    private static String wide(byte[] b, int at) {
        if (at < 0 || at + 4 > b.length) {
            return "";
        }
        final int chars = u32(b, at);
        if (chars < 0 || at + 4 + chars * 2L > b.length) {
            return "";
        }
        return new String(b, at + 4, chars * 2, UTF16);
    }

    /** A BIFF12 colour: an RGB value or a palette index; theme colours are not resolved */
    private static String xlsbColor(byte[] b, int at) {
        final int type = (b[at] & 0xFF) >> 1;
        if (type == 1) {
            return String.format("#%02x%02x%02x", b[at + 4] & 0xFF, b[at + 5] & 0xFF, b[at + 6] & 0xFF);
        }
        return type == 2 ? SheetFormat.color(b[at + 1] & 0xFF, null) : null;
    }

    /** Reads the number formats, fonts, fills, borders and cell formats of xl/styles.bin */
    private static void xlsbStyles(Workbook wb, byte[] b) {
        final List<String> fills = new ArrayList<>();
        final List<int[]> borders = new ArrayList<>();
        boolean cellXfs = false;
        for (Record r = new Record(b); r.next(); ) {
            final int d = r.data;
            switch (r.id) {
                case 0x2C:
                    if (r.length >= 6) {
                        wb.formats.put(u16(b, d), wide(b, d + 2));
                    }
                    break;
                case 0x2B:
                    if (r.length >= 20) {
                        final Font font = new Font();
                        font.size = u16(b, d) / 20f;
                        font.italic = (u16(b, d + 2) & 2) != 0;
                        font.strike = (u16(b, d + 2) & 8) != 0;
                        font.bold = u16(b, d + 4) >= 700;
                        font.underline = (b[d + 8] & 0xFF) != 0;
                        font.color = xlsbColor(b, d + 12);
                        wb.fonts.add(font);
                    }
                    break;
                case 0x2D:
                    fills.add(r.length >= 12 && u32(b, d) == 1 ? xlsbColor(b, d + 4) : null);
                    break;
                case 0x2E:
                    if (r.length >= 43) {
                        // top, bottom, left, right
                        borders.add(new int[]{u16(b, d + 29), u16(b, d + 39), u16(b, d + 9), u16(b, d + 19)});
                    } else {
                        borders.add(new int[4]);
                    }
                    break;
                case 0x272:
                    cellXfs = false;
                    break;
                case 0x269:
                    cellXfs = true;
                    break;
                case 0x2F:
                    if (cellXfs && r.length >= 14) {
                        final int fill = u16(b, d + 6);
                        final int border = u16(b, d + 8);
                        final int[] edge = border < borders.size() ? borders.get(border) : new int[4];
                        wb.xfs.add(wb.make(u16(b, d + 4), u16(b, d + 2), u16(b, d + 12) & 7,
                                (u16(b, d + 12) & 0x40) != 0, edge, fill < fills.size() ? fills.get(fill) : null));
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static Sheet xlsbSheet(byte[] bytes, String name, List<String> shared, Workbook wb, int maxRows,
                                   int maxColumns) {
        final Sheet sheet = new Sheet(name);
        final TreeMap<Integer, TreeMap<Integer, Cell>> cells = new TreeMap<>();
        int row = 0;
        for (Record r = new Record(bytes); r.next(); ) {
            final int d = r.data;
            if (r.id == 0x00 && r.length >= 4) {
                row = u32(bytes, d);
            } else if (r.id == 0x3C && r.length >= 16) {
                final int width = Math.round(u32(bytes, d + 8) / 256f * 7 + 5);
                for (int c = u32(bytes, d); c <= Math.min(u32(bytes, d + 4), 255) && c >= 0; c++) {
                    sheet.columnWidths.put(c, width);
                }
            } else if (r.id == 0xB0 && r.length >= 16) {
                sheet.merges.add(new int[]{u32(bytes, d), u32(bytes, d + 4), u32(bytes, d + 8), u32(bytes, d + 12)});
            } else if (r.length >= 8 && r.id >= 0x01 && r.id <= 0x0B) {
                final int column = u32(bytes, d);
                final int xf = u32(bytes, d + 4) & 0xFFFFFF;
                String text = null;
                boolean numeric = false;
                switch (r.id) {
                    case 0x01:
                        text = "";
                        break;
                    case 0x02:
                        numeric = r.length >= 12;
                        text = numeric ? wb.number(xf, rk(u32(bytes, d + 8))) : null;
                        break;
                    case 0x03:
                    case 0x0B:
                        text = "#ERR";
                        break;
                    case 0x04:
                    case 0x0A:
                        text = bytes[d + 8] != 0 ? "TRUE" : "FALSE";
                        break;
                    case 0x05:
                    case 0x09:
                        numeric = r.length >= 16;
                        text = numeric ? wb.number(xf, Double.longBitsToDouble(i64(bytes, d + 8))) : null;
                        break;
                    case 0x06:
                    case 0x08:
                        text = wide(bytes, d + 8);
                        break;
                    case 0x07: {
                        final int at = r.length >= 12 ? u32(bytes, d + 8) : -1;
                        text = at >= 0 && at < shared.size() ? shared.get(at) : "";
                        break;
                    }
                    default:
                        break;
                }
                if (text != null && column >= 0) {
                    put(cells, row, column, text, numeric, xf);
                }
            }
        }
        rows(cells, sheet, wb, maxRows, maxColumns);
        return sheet;
    }

    /** A BIFF12 record: a 1 or 2 byte id, a 7-bits-per-byte length, then the data */
    private static final class Record {
        private final byte[] b;
        private int pos;
        int id;
        int length;
        int data;

        Record(byte[] b) {
            this.b = b;
        }

        boolean next() {
            if (pos >= b.length) {
                return false;
            }
            id = b[pos++] & 0xFF;
            if ((id & 0x80) != 0 && pos < b.length) {
                id |= (b[pos++] & 0xFF) << 8;
            }
            length = 0;
            for (int shift = 0, i = 0; i < 4 && pos < b.length; i++, shift += 7) {
                final int part = b[pos++] & 0xFF;
                length |= (part & 0x7F) << shift;
                if ((part & 0x80) == 0) {
                    break;
                }
            }
            data = pos;
            if (length < 0 || data + (long) length > b.length) {
                pos = b.length;
                return false;
            }
            pos = data + length;
            return true;
        }
    }


    // ---- Excel workbook (.xlsx and relatives) ----

    private static XmlPullParser xml(Map<String, byte[]> parts, String name) throws IOException {
        final byte[] bytes = parts.get(name);
        if (bytes == null) {
            return null;
        }
        try {
            final XmlPullParser p = android.util.Xml.newPullParser();
            // Names are matched with their prefixes, as in r:id
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new java.io.ByteArrayInputStream(bytes), null);
            return p;
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
    }

    /** @return the worksheets of an .xlsx, given its zip parts, with the same looks as the older formats */
    public static List<Sheet> xlsx(Map<String, byte[]> parts, int maxRows, int maxColumns) throws IOException {
        try {
            return readXlsx(parts, maxRows, maxColumns);
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
    }

    private static List<Sheet> readXlsx(Map<String, byte[]> parts, int maxRows, int maxColumns)
            throws IOException, XmlPullParserException {
        final Workbook wb = new Workbook();
        final List<String> shared = new ArrayList<>();
        XmlPullParser p = xml(parts, "xl/sharedStrings.xml");
        if (p != null) {
            StringBuilder current = null;
            boolean inText = false;
            int phonetic = 0;
            for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
                final String tag = p.getName();
                if (event == XmlPullParser.START_TAG) {
                    if ("si".equals(tag)) {
                        current = new StringBuilder();
                    } else if ("rPh".equals(tag)) {
                        phonetic++;
                    } else if ("t".equals(tag) && phonetic == 0) {
                        inText = true;
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if ("si".equals(tag) && current != null) {
                        shared.add(current.toString());
                    } else if ("rPh".equals(tag)) {
                        phonetic--;
                    } else if ("t".equals(tag)) {
                        inText = false;
                    }
                } else if (event == XmlPullParser.TEXT && inText && current != null) {
                    current.append(p.getText());
                }
            }
        }
        final String[] theme = new String[12];
        p = xml(parts, "xl/theme/theme1.xml");
        if (p != null) {
            // clrScheme order is dk1, lt1, dk2, lt2, accents, links; cells count lt1, dk1, lt2, dk2
            final String[] names = {"dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4",
                    "accent5", "accent6", "hlink", "folHlink"};
            String slot = null;
            for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                final String tag = p.getName();
                if (tag.startsWith("a:")) {
                    final String local = tag.substring(2);
                    for (String n : names) {
                        if (n.equals(local)) {
                            slot = local;
                        }
                    }
                    if (slot != null && ("srgbClr".equals(local) || "sysClr".equals(local))) {
                        String v = "srgbClr".equals(local) ? p.getAttributeValue(null, "val")
                                : p.getAttributeValue(null, "lastClr");
                        for (int i = 0; i < names.length; i++) {
                            if (names[i].equals(slot) && v != null) {
                                theme[i] = "#" + v.toLowerCase();
                            }
                        }
                        slot = null;
                    }
                }
            }
            String swap = theme[0];
            theme[0] = theme[1];
            theme[1] = swap;
            swap = theme[2];
            theme[2] = theme[3];
            theme[3] = swap;
        }
        p = xml(parts, "xl/styles.xml");
        if (p != null) {
            xlsxStyles(wb, p, theme);
        }
        final Map<String, String> targets = new java.util.HashMap<>();
        p = xml(parts, "xl/_rels/workbook.xml.rels");
        if (p != null) {
            for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
                if (event == XmlPullParser.START_TAG && "Relationship".equals(p.getName())) {
                    targets.put(p.getAttributeValue(null, "Id"), p.getAttributeValue(null, "Target"));
                }
            }
        }
        final List<String[]> listed = new ArrayList<>();
        p = xml(parts, "xl/workbook.xml");
        if (p == null) {
            throw new IOException("Not an Excel workbook");
        }
        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            if (event != XmlPullParser.START_TAG) {
                continue;
            }
            if ("workbookPr".equals(p.getName())) {
                wb.date1904 = "1".equals(p.getAttributeValue(null, "date1904"))
                        || "true".equals(p.getAttributeValue(null, "date1904"));
            } else if ("sheet".equals(p.getName())
                    && (p.getAttributeValue(null, "state") == null
                    || "visible".equals(p.getAttributeValue(null, "state")))) {
                final String target = targets.get(p.getAttributeValue(null, "r:id"));
                if (target != null) {
                    listed.add(new String[]{p.getAttributeValue(null, "name"),
                            target.startsWith("/") ? target.substring(1) : "xl/" + target});
                }
            }
        }
        final List<Sheet> sheets = new ArrayList<>();
        for (String[] entry : listed) {
            p = xml(parts, entry[1]);
            if (p != null) {
                sheets.add(xlsxSheet(p, entry[0], shared, wb, maxRows, maxColumns));
            }
        }
        if (sheets.isEmpty()) {
            throw new IOException("No sheets");
        }
        return sheets;
    }

    /** A colour element: rgb, a theme slot with a tint, or an indexed palette entry */
    private static String xlsxColor(XmlPullParser p, String[] theme) {
        final String rgb = p.getAttributeValue(null, "rgb");
        if (rgb != null && rgb.length() >= 6) {
            return "#" + rgb.substring(rgb.length() - 6).toLowerCase();
        }
        final String slot = p.getAttributeValue(null, "theme");
        if (slot != null) {
            int i = Integer.parseInt(slot);
            String base = i >= 0 && i < theme.length ? theme[i] : null;
            final String tint = p.getAttributeValue(null, "tint");
            if (base != null && tint != null) {
                final double t = Double.parseDouble(tint);
                final int c = Integer.parseInt(base.substring(1), 16);
                final StringBuilder out = new StringBuilder("#");
                for (int shift = 16; shift >= 0; shift -= 8) {
                    final double v = (c >> shift) & 0xFF;
                    final int n = (int) Math.round(t < 0 ? v * (1 + t) : v + (255 - v) * t);
                    out.append(String.format("%02x", Math.max(0, Math.min(255, n))));
                }
                return out.toString();
            }
            return base;
        }
        final String indexed = p.getAttributeValue(null, "indexed");
        return indexed == null ? null : SheetFormat.color(Integer.parseInt(indexed), null);
    }

    private static int borderStyle(String style) {
        if (style == null || "none".equals(style)) {
            return 0;
        }
        return "medium".equals(style) ? 2 : "thick".equals(style) ? 5 : 1;
    }

    private static void xlsxStyles(Workbook wb, XmlPullParser p, String[] theme)
            throws IOException, XmlPullParserException {
        final List<String> fills = new ArrayList<>();
        final List<int[]> borders = new ArrayList<>();
        String section = "";
        Font font = null;
        int[] border = null;
        String side = null;
        String fill = null;
        boolean solid = false;
        boolean cellXfs = false;
        int numFmt = 0, fontId = 0, fillId = 0, borderId = 0, align = 0;
        boolean wrap = false;
        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            final String tag = p.getName();
            if (event == XmlPullParser.START_TAG) {
                switch (tag) {
                    case "numFmt":
                        wb.formats.put(Integer.parseInt(p.getAttributeValue(null, "numFmtId")),
                                p.getAttributeValue(null, "formatCode"));
                        break;
                    case "fonts":
                    case "fills":
                    case "borders":
                        section = tag;
                        break;
                    case "cellXfs":
                        cellXfs = true;
                        section = tag;
                        break;
                    case "cellStyleXfs":
                    case "cellStyles":
                    case "dxfs":
                        section = tag;
                        break;
                    case "font":
                        if ("fonts".equals(section)) {
                            font = new Font();
                        }
                        break;
                    case "b":
                        if (font != null) {
                            font.bold = !"0".equals(p.getAttributeValue(null, "val"));
                        }
                        break;
                    case "i":
                        if (font != null) {
                            font.italic = !"0".equals(p.getAttributeValue(null, "val"));
                        }
                        break;
                    case "strike":
                        if (font != null) {
                            font.strike = !"0".equals(p.getAttributeValue(null, "val"));
                        }
                        break;
                    case "u":
                        if (font != null) {
                            font.underline = !"none".equals(p.getAttributeValue(null, "val"));
                        }
                        break;
                    case "sz":
                        if (font != null) {
                            font.size = Float.parseFloat(p.getAttributeValue(null, "val"));
                        }
                        break;
                    case "color":
                        if (font != null) {
                            font.color = xlsxColor(p, theme);
                        }
                        break;
                    case "fill":
                        fill = null;
                        solid = false;
                        break;
                    case "patternFill":
                        solid = "solid".equals(p.getAttributeValue(null, "patternType"));
                        break;
                    case "fgColor":
                        if ("fills".equals(section) && solid) {
                            fill = xlsxColor(p, theme);
                        }
                        break;
                    case "border":
                        if ("borders".equals(section)) {
                            border = new int[4];
                        }
                        break;
                    case "left":
                    case "right":
                    case "top":
                    case "bottom":
                        if (border != null) {
                            final int at = "left".equals(tag) ? 0 : "right".equals(tag) ? 1 : "top".equals(tag) ? 2 : 3;
                            border[at] = borderStyle(p.getAttributeValue(null, "style"));
                        }
                        break;
                    case "xf":
                        if (cellXfs && "cellXfs".equals(section)) {
                            numFmt = intAttr(p, "numFmtId");
                            fontId = intAttr(p, "fontId");
                            fillId = intAttr(p, "fillId");
                            borderId = intAttr(p, "borderId");
                            align = 0;
                            wrap = false;
                        }
                        break;
                    case "alignment":
                        if ("cellXfs".equals(section)) {
                            final String h = p.getAttributeValue(null, "horizontal");
                            align = "left".equals(h) ? 1 : "center".equals(h) ? 2 : "right".equals(h) ? 3 : 0;
                            wrap = "1".equals(p.getAttributeValue(null, "wrapText"))
                                    || "true".equals(p.getAttributeValue(null, "wrapText"));
                        }
                        break;
                    default:
                        break;
                }
            } else if (event == XmlPullParser.END_TAG) {
                switch (tag) {
                    case "font":
                        if (font != null) {
                            wb.fonts.add(font);
                            font = null;
                        }
                        break;
                    case "fill":
                        fills.add(fill);
                        break;
                    case "border":
                        if (border != null) {
                            borders.add(border);
                            border = null;
                        }
                        break;
                    case "xf":
                        if ("cellXfs".equals(section)) {
                            wb.xfs.add(wb.make(fontId, numFmt, align, wrap,
                                    borderId < borders.size() ? borders.get(borderId) : new int[4],
                                    fillId < fills.size() ? fills.get(fillId) : null));
                        }
                        break;
                    case "cellXfs":
                        section = "";
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private static int intAttr(XmlPullParser p, String name) {
        final String v = p.getAttributeValue(null, name);
        try {
            return v == null ? 0 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** @return the 0-based {row, column} of a reference like "AB12" */
    private static int[] cellRef(String ref) {
        int column = 0, i = 0;
        for (; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) {
            column = column * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
        }
        int row = 0;
        for (; i < ref.length() && Character.isDigit(ref.charAt(i)); i++) {
            row = row * 10 + ref.charAt(i) - '0';
        }
        return new int[]{row - 1, column - 1};
    }

    private static Sheet xlsxSheet(XmlPullParser p, String name, List<String> shared, Workbook wb, int maxRows,
                                   int maxColumns) throws IOException, XmlPullParserException {
        final Sheet sheet = new Sheet(name);
        final TreeMap<Integer, TreeMap<Integer, Cell>> cells = new TreeMap<>();
        int row = -1, column = -1;
        String type = null;
        int xf = 0;
        boolean inValue = false, inText = false, hiddenRow = false;
        StringBuilder value = new StringBuilder();
        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            final String tag = p.getName();
            if (event == XmlPullParser.START_TAG) {
                switch (tag) {
                    case "col": {
                        final int min = intAttr(p, "min"), max = Math.min(intAttr(p, "max"), 256);
                        final String width = p.getAttributeValue(null, "width");
                        if (width != null && !"1".equals(p.getAttributeValue(null, "hidden"))) {
                            final int px = Math.round(Float.parseFloat(width) * 7 + 5);
                            for (int c = min; c <= max; c++) {
                                sheet.columnWidths.put(c - 1, px);
                            }
                        }
                        break;
                    }
                    case "row": {
                        final String r = p.getAttributeValue(null, "r");
                        row = r != null ? Integer.parseInt(r) - 1 : row + 1;
                        column = -1;
                        hiddenRow = "1".equals(p.getAttributeValue(null, "hidden"));
                        break;
                    }
                    case "c": {
                        final String ref = p.getAttributeValue(null, "r");
                        column = ref != null ? cellRef(ref)[1] : column + 1;
                        type = p.getAttributeValue(null, "t");
                        xf = intAttr(p, "s");
                        value.setLength(0);
                        break;
                    }
                    case "v":
                        inValue = true;
                        break;
                    case "t":
                        inText = true;
                        break;
                    case "mergeCell": {
                        final String ref = p.getAttributeValue(null, "ref");
                        if (ref != null && ref.indexOf(':') > 0) {
                            final int[] a = cellRef(ref.substring(0, ref.indexOf(':')));
                            final int[] b = cellRef(ref.substring(ref.indexOf(':') + 1));
                            sheet.merges.add(new int[]{a[0], b[0], a[1], b[1]});
                        }
                        break;
                    }
                    default:
                        break;
                }
            } else if (event == XmlPullParser.TEXT) {
                if (inValue || inText) {
                    value.append(p.getText());
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("v".equals(tag)) {
                    inValue = false;
                } else if ("t".equals(tag)) {
                    inText = false;
                } else if ("c".equals(tag) && column >= 0 && row >= 0 && !hiddenRow) {
                    final String raw = value.toString();
                    String text = null;
                    boolean numeric = false;
                    if ("s".equals(type)) {
                        try {
                            final int at = Integer.parseInt(raw.trim());
                            text = at >= 0 && at < shared.size() ? shared.get(at) : "";
                        } catch (NumberFormatException e) {
                            text = "";
                        }
                    } else if ("b".equals(type)) {
                        text = "1".equals(raw.trim()) ? "TRUE" : "FALSE";
                    } else if ("str".equals(type) || "inlineStr".equals(type) || "e".equals(type) || "d".equals(type)) {
                        text = raw;
                    } else if (!raw.trim().isEmpty()) {
                        try {
                            text = wb.number(xf, Double.parseDouble(raw.trim()));
                            numeric = true;
                        } catch (NumberFormatException e) {
                            text = raw;
                        }
                    } else {
                        text = "";
                    }
                    put(cells, row, column, text, numeric, xf);
                }
            }
        }
        rows(cells, sheet, wb, maxRows, maxColumns);
        return sheet;
    }

    // ---- PowerPoint 97-2003 ----

    /** Where the slide walk is: the slides found so far and the one that text currently belongs to */
    private static final class Deck {
        final List<List<String>> slides = new ArrayList<>();
        List<String> current;
        int drawn;
    }

    /** @return the text of each slide, in slide order */
    public static List<List<String>> ppt(byte[] file) throws IOException {
        final byte[] doc = new Ole2(file).stream("PowerPoint Document");
        if (doc == null) {
            throw new IOException("Not a PowerPoint file");
        }
        final Deck deck = new Deck();
        walk(doc, 0, doc.length, false, deck);
        return deck.slides;
    }

    /**
     * Placeholder text sits in the slide list, other text boxes in the slides themselves, so the
     * slide text is taken from both, skipping what the list already gave. Masters, notes and
     * handouts are left out.
     */
    private static void walk(byte[] b, int start, int end, boolean inList, Deck deck) {
        for (int pos = start; pos + 8 <= end; ) {
            final int versionInstance = u16(b, pos);
            final int type = u16(b, pos + 2);
            final long length = u32(b, pos + 4) & 0xFFFFFFFFL;
            final int body = pos + 8;
            if (body + length > end) {
                break;
            }
            final int to = (int) (body + length);
            if ((versionInstance & 0xF) == 0xF) {
                if (type == 0x0FF0) {
                    if (versionInstance >> 4 == 0) {
                        walk(b, body, to, true, deck);
                        deck.current = null;
                    }
                } else if (type == 0x03EE) {
                    while (deck.slides.size() <= deck.drawn) {
                        deck.slides.add(new ArrayList<>());
                    }
                    deck.current = deck.slides.get(deck.drawn++);
                    walk(b, body, to, false, deck);
                    deck.current = null;
                } else if (type != 0x03F8 && type != 0x03F0 && type != 0x0FC9) {
                    walk(b, body, to, inList, deck);
                }
            } else if (type == 0x03F3 && inList) {
                deck.current = new ArrayList<>();
                deck.slides.add(deck.current);
            } else if ((type == 0x0FA0 || type == 0x0FA8) && deck.current != null) {
                final String text = new String(b, body, (int) length, type == 0x0FA0 ? UTF16 : ANSI)
                        .replace('\r', '\n').replace('\u000b', '\n').trim();
                if (!text.isEmpty() && !deck.current.contains(text)) {
                    deck.current.add(text);
                }
            }
            pos = to;
        }
    }

    // ---- little-endian reads ----

    static int u16(byte[] b, int at) {
        return at < 0 || at + 2 > b.length ? 0 : (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    static int u32(byte[] b, int at) {
        return at < 0 || at + 4 > b.length ? 0 : u16(b, at) | u16(b, at + 2) << 16;
    }

    private static long i64(byte[] b, int at) {
        return (u32(b, at) & 0xFFFFFFFFL) | (long) u32(b, at + 4) << 32;
    }
}
