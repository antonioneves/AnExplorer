package dev.dworks.apps.anexplorer.misc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    /** One worksheet: its name and the rows that have cells */
    public static final class Sheet {
        public final String name;
        public final List<List<String>> rows = new ArrayList<>();

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
            } else if (type == 0x000A) {
                break;
            }
            pos = data + length;
        }
        final List<Sheet> sheets = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            sheets.add(xlsSheet(book, names.get(i), offsets.get(i), shared, maxRows, maxColumns));
        }
        if (sheets.isEmpty()) {
            throw new IOException("No sheets");
        }
        return sheets;
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

    private static Sheet xlsSheet(byte[] book, String name, int start, List<String> shared, int maxRows,
                                  int maxColumns) {
        final Sheet sheet = new Sheet(name);
        final TreeMap<Integer, TreeMap<Integer, String>> cells = new TreeMap<>();
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
                put(cells, u16(book, d), u16(book, d + 2), at >= 0 && at < shared.size() ? shared.get(at) : "");
            } else if (type == 0x0204 && length >= 9) {
                final int chars = u16(book, d + 6);
                final boolean wide = (book[d + 8] & 1) != 0;
                final int size = Math.min(chars * (wide ? 2 : 1), Math.max(0, length - 9));
                put(cells, u16(book, d), u16(book, d + 2), new String(book, d + 9, size, wide ? UTF16 : ANSI));
            } else if (type == 0x0203 && length >= 14) {
                put(cells, u16(book, d), u16(book, d + 2), number(Double.longBitsToDouble(i64(book, d + 6))));
            } else if (type == 0x027E && length >= 10) {
                put(cells, u16(book, d), u16(book, d + 2), number(rk(u32(book, d + 6))));
            } else if (type == 0x00BD && length >= 6) {
                final int row = u16(book, d);
                final int first = u16(book, d + 2);
                for (int i = 0; i < (length - 6) / 6; i++) {
                    put(cells, row, first + i, number(rk(u32(book, d + 4 + i * 6 + 2))));
                }
            } else if (type == 0x0205 && length >= 8) {
                put(cells, u16(book, d), u16(book, d + 2),
                        book[d + 7] != 0 ? "#ERR" : book[d + 6] != 0 ? "TRUE" : "FALSE");
            } else if (type == 0x0006 && length >= 14) {
                formulaRow = u16(book, d);
                formulaColumn = u16(book, d + 2);
                if (u16(book, d + 12) == 0xFFFF) {
                    final int kind = book[d + 6];
                    if (kind == 1) {
                        put(cells, formulaRow, formulaColumn, book[d + 8] != 0 ? "TRUE" : "FALSE");
                    } else if (kind == 2) {
                        put(cells, formulaRow, formulaColumn, "#ERR");
                    }
                } else {
                    put(cells, formulaRow, formulaColumn, number(Double.longBitsToDouble(i64(book, d + 6))));
                }
            } else if (type == 0x0207 && length >= 3 && formulaRow >= 0) {
                final int chars = u16(book, d);
                final boolean wide = (book[d + 2] & 1) != 0;
                final int size = Math.min(chars * (wide ? 2 : 1), Math.max(0, length - 3));
                put(cells, formulaRow, formulaColumn, new String(book, d + 3, size, wide ? UTF16 : ANSI));
            }
        }
        rows(cells, sheet, maxRows, maxColumns);
        return sheet;
    }

    private static void put(TreeMap<Integer, TreeMap<Integer, String>> cells, int row, int column, String text) {
        TreeMap<Integer, String> line = cells.get(row);
        if (line == null) {
            line = new TreeMap<>();
            cells.put(row, line);
        }
        line.put(column, text);
    }

    /** Fills the gaps between cells with empty strings so the columns line up */
    private static void rows(TreeMap<Integer, TreeMap<Integer, String>> cells, Sheet sheet, int maxRows,
                             int maxColumns) {
        for (TreeMap<Integer, String> line : cells.values()) {
            if (sheet.rows.size() >= maxRows) {
                break;
            }
            final List<String> row = new ArrayList<>();
            for (Map.Entry<Integer, String> cell : line.entrySet()) {
                if (cell.getKey() >= maxColumns) {
                    break;
                }
                while (row.size() < cell.getKey()) {
                    row.add("");
                }
                row.add(cell.getValue());
            }
            sheet.rows.add(row);
        }
    }

    private static double rk(int value) {
        final double number = (value & 2) != 0 ? value >> 2
                : Double.longBitsToDouble((value & 0xFFFFFFFCL) << 32);
        return (value & 1) != 0 ? number / 100 : number;
    }

    private static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "#NUM";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return new BigDecimal(value).round(new MathContext(12)).stripTrailingZeros().toPlainString();
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
        final List<Sheet> sheets = new ArrayList<>();
        for (Record r = new Record(workbook); r.next(); ) {
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
                sheets.add(xlsbSheet(sheet, name, shared, maxRows, maxColumns));
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

    private static Sheet xlsbSheet(byte[] bytes, String name, List<String> shared, int maxRows, int maxColumns) {
        final Sheet sheet = new Sheet(name);
        final TreeMap<Integer, TreeMap<Integer, String>> cells = new TreeMap<>();
        int row = 0;
        for (Record r = new Record(bytes); r.next(); ) {
            final int d = r.data;
            if (r.id == 0x00 && r.length >= 4) {
                row = u32(bytes, d);
            } else if (r.length >= 8 && r.id >= 0x01 && r.id <= 0x0B) {
                final int column = u32(bytes, d);
                String text = null;
                switch (r.id) {
                    case 0x02:
                        text = r.length >= 12 ? number(rk(u32(bytes, d + 8))) : null;
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
                        text = r.length >= 16 ? number(Double.longBitsToDouble(i64(bytes, d + 8))) : null;
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
                    put(cells, row, column, text);
                }
            }
        }
        rows(cells, sheet, maxRows, maxColumns);
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
