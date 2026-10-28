package dev.dworks.apps.anexplorer.misc;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns file contents into styled text for the preview screen. The extension-to-language table,
 * the highlight size cap, the delimiter sniffing and the EPUB reading order follow Paperwren
 * (MIT, Copyright (c) 2026 Paperwren contributors), ported to the platform APIs so no library is added.
 */
public final class PreviewFormatter {

    /** Larger files are shown plain, as highlighting them would stall a TV */
    public static final int HIGHLIGHT_MAX = 400_000;

    private static final int MAX_ENTRY_BYTES = 16 * 1024 * 1024;
    private static final int MAX_BINARY_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ROWS = 2000;
    private static final int MAX_COLUMNS = 50;
    private static final int MAX_CELL_WIDTH = 28;
    private static final int MAX_BOOK_CHARS = 2 * 1024 * 1024;

    private static final int COLOR_COMMENT = 0xFF6A9955;
    private static final int COLOR_STRING = 0xFFCE9178;
    private static final int COLOR_NUMBER = 0xFFB5CEA8;
    private static final int COLOR_KEYWORD = 0xFF569CD6;
    private static final int COLOR_ADDED = 0xFF6FBF73;
    private static final int COLOR_REMOVED = 0xFFE57373;
    private static final int COLOR_MUTED = 0xFF9E9E9E;

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract", "as", "async", "await", "break", "case", "catch", "class", "const", "continue",
            "def", "default", "defer", "del", "do", "elif", "else", "enum", "except", "export", "extends",
            "false", "fi", "final", "finally", "fn", "for", "from", "fun", "func", "function", "go", "if",
            "impl", "implements", "import", "in", "interface", "is", "lambda", "let", "match", "mod", "namespace",
            "new", "nil", "none", "null", "object", "override", "package", "pass", "private", "protected",
            "public", "pub", "raise", "return", "self", "static", "struct", "super", "switch", "then", "this",
            "throw", "throws", "trait", "true", "try", "typeof", "use", "using", "val", "var", "void",
            "while", "with", "yield", "select", "insert", "update", "delete", "where", "create", "table",
            "end", "begin", "local", "echo", "done", "esac", "unless", "elsif", "undef", "my", "sub"));

    private PreviewFormatter() {
    }

    // ---- decoding ----

    /** UTF-8 first, windows-1252 when the bytes aren't valid UTF-8, as old exports often are */
    public static String decode(byte[] bytes) {
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        try {
            return Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, offset, bytes.length - offset, Charset.forName("windows-1252"));
        }
    }

    // ---- syntax colouring ----

    private static final class Lang {
        final String line;
        final String blockStart;
        final String blockEnd;
        final boolean keywords;

        Lang(String line, String blockStart, String blockEnd, boolean keywords) {
            this.line = line;
            this.blockStart = blockStart;
            this.blockEnd = blockEnd;
            this.keywords = keywords;
        }
    }

    private static final Lang C_LIKE = new Lang("//", "/*", "*/", true);
    private static final Lang HASH = new Lang("#", null, null, true);
    private static final Lang SQL = new Lang("--", "/*", "*/", true);
    private static final Lang MARKUP = new Lang(null, "<!--", "-->", false);
    private static final Lang DATA = new Lang(null, null, null, false);
    private static final Lang INI = new Lang(";", null, null, false);
    private static final Lang DIFF = new Lang(null, null, null, false);

    private static final Map<String, Lang> BY_EXTENSION = new HashMap<>();

    static {
        for (String ext : "js mjs cjs jsx ts tsx java kt kts swift c h cpp cc hpp cs go rs php dart scss less css gradle"
                .split(" ")) {
            BY_EXTENSION.put(ext, C_LIKE);
        }
        for (String ext : "py rb sh bash zsh r pl yml yaml toml".split(" ")) {
            BY_EXTENSION.put(ext, HASH);
        }
        for (String ext : "sql lua".split(" ")) {
            BY_EXTENSION.put(ext, SQL);
        }
        for (String ext : "html htm vue xml plist csproj".split(" ")) {
            BY_EXTENSION.put(ext, MARKUP);
        }
        for (String ext : "ini properties conf cfg env".split(" ")) {
            BY_EXTENSION.put(ext, INI);
        }
        BY_EXTENSION.put("json", DATA);
        BY_EXTENSION.put("diff", DIFF);
        BY_EXTENSION.put("patch", DIFF);
    }

    /** @return whether the extension (or a well-known file name) has syntax colouring */
    public static boolean isCode(String name) {
        return languageOf(name) != null;
    }

    private static Lang languageOf(String name) {
        if (name == null) {
            return null;
        }
        final String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("dockerfile") || lower.equals("makefile") || lower.equals("gnumakefile")) {
            return HASH;
        }
        final int dot = lower.lastIndexOf('.');
        return dot < 0 ? null : BY_EXTENSION.get(lower.substring(dot + 1));
    }

    /** Colours comments, strings, numbers and keywords; plain text when the language is unknown or the file is huge */
    public static CharSequence highlight(String name, String text) {
        final Lang lang = languageOf(name);
        if (lang == null || text.length() > HIGHLIGHT_MAX) {
            return text;
        }
        final SpannableStringBuilder out = new SpannableStringBuilder(text);
        if (lang == DIFF) {
            colorDiff(out, text);
            return out;
        }
        final int n = text.length();
        int i = 0;
        while (i < n) {
            final char c = text.charAt(i);
            if (lang.blockStart != null && text.startsWith(lang.blockStart, i)) {
                int end = text.indexOf(lang.blockEnd, i + lang.blockStart.length());
                end = end < 0 ? n : end + lang.blockEnd.length();
                color(out, COLOR_COMMENT, i, end);
                i = end;
            } else if (lang.line != null && text.startsWith(lang.line, i)) {
                int end = text.indexOf('\n', i);
                end = end < 0 ? n : end;
                color(out, COLOR_COMMENT, i, end);
                i = end;
            } else if (lang == MARKUP && c == '<') {
                int end = text.indexOf('>', i);
                end = end < 0 ? n : end + 1;
                color(out, COLOR_KEYWORD, i, end);
                i = end;
            } else if (lang != MARKUP && (c == '"' || c == '\'' || c == '`')) {
                int j = i + 1;
                while (j < n) {
                    final char d = text.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == c || d == '\n' && c != '`') {
                        break;
                    }
                    j++;
                }
                final int end = Math.min(n, j + 1);
                color(out, COLOR_STRING, i, end);
                i = end;
            } else if (Character.isDigit(c) && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '.'
                        || text.charAt(j) == '_')) {
                    j++;
                }
                color(out, COLOR_NUMBER, i, j);
                i = j;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
                    j++;
                }
                if (lang.keywords && KEYWORDS.contains(text.substring(i, j))) {
                    color(out, COLOR_KEYWORD, i, j);
                } else if (lang == DATA && KEYWORDS.contains(text.substring(i, j))) {
                    color(out, COLOR_KEYWORD, i, j);
                }
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }

    private static void colorDiff(SpannableStringBuilder out, String text) {
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            end = end < 0 ? text.length() : end;
            if (end > start) {
                final char c = text.charAt(start);
                if (text.startsWith("@@", start)) {
                    color(out, COLOR_KEYWORD, start, end);
                } else if (c == '+') {
                    color(out, COLOR_ADDED, start, end);
                } else if (c == '-') {
                    color(out, COLOR_REMOVED, start, end);
                }
            }
            start = end + 1;
        }
    }

    private static void color(SpannableStringBuilder out, int color, int start, int end) {
        out.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ---- markdown ----

    private static final Pattern INLINE = Pattern.compile(
            "`([^`]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|\\[([^\\]]+)\\]\\([^)]*\\)|\\*([^*\\s][^*]*?)\\*|(?<![\\w])_([^_\\s][^_]*?)_(?![\\w])");

    /** Headings, emphasis, inline code, code blocks, lists and quotes; links keep their label */
    public static CharSequence markdown(String text) {
        final SpannableStringBuilder out = new SpannableStringBuilder();
        boolean fenced = false;
        for (String line : text.split("\n", -1)) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            final String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fenced = !fenced;
                continue;
            }
            final int start = out.length();
            if (fenced) {
                out.append(line).append('\n');
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                color(out, COLOR_STRING, start, out.length());
                continue;
            }
            int level = 0;
            while (level < trimmed.length() && level < 6 && trimmed.charAt(level) == '#') {
                level++;
            }
            if (level > 0 && trimmed.length() > level && trimmed.charAt(level) == ' ') {
                inline(out, trimmed.substring(level + 1).trim());
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(2.0f - level * 0.15f), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
            } else if (trimmed.matches("^[-*_]{3,}$")) {
                out.append("────────────────\n");
            } else if (trimmed.startsWith("> ")) {
                inline(out, trimmed.substring(2));
                color(out, COLOR_MUTED, start, out.length());
                out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
            } else if (trimmed.matches("^[-*+] .*")) {
                out.append(line.substring(0, line.indexOf(trimmed.charAt(0)))).append("• ");
                inline(out, trimmed.substring(2));
                out.append('\n');
            } else {
                inline(out, line);
                out.append('\n');
            }
        }
        return out;
    }

    private static void inline(SpannableStringBuilder out, String line) {
        final Matcher m = INLINE.matcher(line);
        int last = 0;
        while (m.find()) {
            out.append(line, last, m.start());
            final int start = out.length();
            if (m.group(1) != null) {
                out.append(m.group(1));
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(0xFF2A2A2A), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(2) != null || m.group(3) != null) {
                out.append(m.group(2) != null ? m.group(2) : m.group(3));
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(4) != null) {
                out.append(m.group(4));
                out.setSpan(new UnderlineSpan(), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                color(out, COLOR_KEYWORD, start, out.length());
            } else {
                out.append(m.group(5) != null ? m.group(5) : m.group(6));
                out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        out.append(line, last, line.length());
    }

    // ---- tables ----

    /** Parses CSV or TSV, guessing the delimiter from the first line, and lays it out in columns */
    public static String delimited(String text) {
        final int firstEnd = text.indexOf('\n');
        final String first = firstEnd < 0 ? text : text.substring(0, firstEnd);
        char delimiter = ',';
        int best = -1;
        for (char candidate : new char[]{',', ';', '\t', '|'}) {
            int count = 0;
            for (int i = 0; i < first.length(); i++) {
                if (first.charAt(i) == candidate) {
                    count++;
                }
            }
            if (count > best) {
                best = count;
                delimiter = candidate;
            }
        }
        final List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        final StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        final int n = text.length();
        for (int i = 0; i < n && rows.size() < MAX_ROWS; i++) {
            final char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return layout(rows);
    }

    /** Pads every column to its widest cell (capped) so the rows line up in a monospace view */
    private static String layout(List<List<String>> rows) {
        final int[] widths = new int[MAX_COLUMNS];
        for (List<String> row : rows) {
            if (row.size() < 2) {
                continue;
            }
            for (int c = 0; c < Math.min(row.size(), MAX_COLUMNS); c++) {
                widths[c] = Math.max(widths[c], Math.min(MAX_CELL_WIDTH, flat(row.get(c)).length()));
            }
        }
        final StringBuilder out = new StringBuilder();
        for (List<String> row : rows) {
            if (row.size() < 2) {
                out.append(row.isEmpty() ? "" : row.get(0)).append('\n');
                continue;
            }
            for (int c = 0; c < Math.min(row.size(), MAX_COLUMNS); c++) {
                String value = flat(row.get(c));
                if (value.length() > MAX_CELL_WIDTH) {
                    value = value.substring(0, MAX_CELL_WIDTH - 1) + "…";
                }
                out.append(value);
                if (c < row.size() - 1) {
                    for (int pad = value.length(); pad < widths[c]; pad++) {
                        out.append(' ');
                    }
                    out.append("  │  ");
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String flat(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    // ---- documents ----

    /** Formats that are a zip of XML parts, RTF, or the binary Office files that {@link BinaryOffice} reads */
    public static boolean isDocument(String ext) {
        return "|docx|odt|rtf|doc|xlsx|xlsm|xltx|ods|xls|xlsb|pptx|pptm|ppsx|odp|ppt|pps|epub|"
                .contains("|" + ext + "|");
    }

    /** @return the readable text of the document, or throws if it is encrypted or not readable */
    public static CharSequence document(String ext, InputStream in) throws IOException {
        try {
            switch (ext) {
                case "rtf":
                    return rtf(decodeLatin(readAll(in, MAX_ENTRY_BYTES)));
                case "doc":
                    return BinaryOffice.doc(readAll(in, MAX_BINARY_BYTES));
                case "xls":
                    return sheets(BinaryOffice.xls(readAll(in, MAX_BINARY_BYTES), MAX_ROWS, MAX_COLUMNS));
                case "xlsb":
                    return sheets(BinaryOffice.xlsb(zip(in, "xl/"), MAX_ROWS, MAX_COLUMNS));
                case "ppt":
                case "pps":
                    return slides(BinaryOffice.ppt(readAll(in, MAX_BINARY_BYTES)));
                case "docx":
                    return docx(zip(in, "word/document.xml").get("word/document.xml"));
                case "odt":
                case "odp":
                case "ods":
                    return odf(ext, zip(in, "content.xml").get("content.xml"));
                case "pptx":
                case "pptm":
                case "ppsx":
                    return pptx(zip(in, "ppt/slides/slide"));
                case "xlsx":
                case "xlsm":
                case "xltx":
                    return xlsx(zip(in, "xl/"));
                case "epub":
                    return epub(zip(in, ""));
                default:
                    throw new IOException("Unsupported document");
            }
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
    }

    private static String decodeLatin(byte[] bytes) {
        return new String(bytes, Charset.forName("ISO-8859-1"));
    }

    static byte[] readAll(InputStream in, int limit) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[16 * 1024];
        int read;
        while (out.size() < limit && (read = in.read(buffer)) > 0) {
            out.write(buffer, 0, Math.min(read, limit - out.size()));
        }
        return out.toByteArray();
    }

    /** Reads the zip entries whose names start with the prefix; a zip can only be walked in order */
    static Map<String, byte[]> zip(InputStream in, String prefix) throws IOException {
        final Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                final String name = entry.getName();
                final boolean wanted = name.startsWith(prefix) || name.startsWith("META-INF/")
                        || prefix.isEmpty() || name.equals("word/document.xml");
                if (!entry.isDirectory() && wanted && (name.endsWith(".xml") || name.endsWith(".rels") || name.endsWith(".bin")
                        || name.endsWith(".opf") || name.endsWith(".ncx") || name.endsWith("html"))) {
                    entries.put(name, readAll(zip, MAX_ENTRY_BYTES));
                }
            }
        }
        return entries;
    }

    private static XmlPullParser parser(byte[] xml) throws XmlPullParserException {
        final XmlPullParser parser = Xml.newPullParser();
        // Tag and attribute names are matched with their prefixes, as in w:p and r:id
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new ByteArrayInputStream(xml), null);
        return parser;
    }

    private static CharSequence docx(byte[] xml) throws IOException, XmlPullParserException {
        if (xml == null) {
            throw new IOException("Not a Word document");
        }
        final XmlPullParser p = parser(xml);
        final SpannableStringBuilder out = new SpannableStringBuilder();
        boolean inText = false;
        boolean numbered = false;
        int cells = 0;
        String style = null;
        int paragraph = 0;
        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            final String tag = p.getName();
            if (event == XmlPullParser.START_TAG) {
                switch (tag) {
                    case "w:p":
                        paragraph = out.length();
                        style = null;
                        numbered = false;
                        break;
                    case "w:pStyle":
                        style = p.getAttributeValue(null, "w:val");
                        break;
                    case "w:numPr":
                        numbered = true;
                        break;
                    case "w:t":
                        inText = true;
                        break;
                    case "w:tab":
                        out.append('\t');
                        break;
                    case "w:br":
                        out.append('\n');
                        break;
                    case "w:tc":
                        cells++;
                        break;
                    default:
                        break;
                }
            } else if (event == XmlPullParser.END_TAG) {
                switch (tag) {
                    case "w:t":
                        inText = false;
                        break;
                    case "w:p":
                        if (numbered) {
                            out.insert(paragraph, "• ");
                        }
                        if (style != null && (style.startsWith("Heading") || style.equals("Title"))) {
                            out.setSpan(new StyleSpan(Typeface.BOLD), paragraph, out.length(),
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            out.setSpan(new RelativeSizeSpan(style.equals("Title") ? 1.6f : 1.3f), paragraph,
                                    out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        out.append(cells > 0 ? " " : "\n");
                        break;
                    case "w:tc":
                        cells = Math.max(0, cells - 1);
                        out.append("  │  ");
                        break;
                    case "w:tr":
                        out.append('\n');
                        break;
                    default:
                        break;
                }
            } else if (event == XmlPullParser.TEXT && inText) {
                out.append(p.getText());
            }
        }
        return out;
    }

    private static CharSequence odf(String ext, byte[] xml) throws IOException, XmlPullParserException {
        if (xml == null) {
            throw new IOException("Not an OpenDocument file");
        }
        final XmlPullParser p = parser(xml);
        final SpannableStringBuilder out = new SpannableStringBuilder();
        int paragraphs = 0;
        int heading = -1;
        int cells = 0;
        int slide = 0;
        final boolean sheet = ext.equals("ods");
        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            final String tag = p.getName();
            if (event == XmlPullParser.START_TAG) {
                switch (tag) {
                    case "text:p":
                        paragraphs++;
                        break;
                    case "text:h":
                        paragraphs++;
                        heading = out.length();
                        break;
                    case "text:list-item":
                        out.append("• ");
                        break;
                    case "text:tab":
                        out.append('\t');
                        break;
                    case "text:line-break":
                        out.append('\n');
                        break;
                    case "text:s": {
                        final String count = p.getAttributeValue(null, "text:c");
                        for (int i = count == null ? 1 : Integer.parseInt(count); i > 0; i--) {
                            out.append(' ');
                        }
                        break;
                    }
                    case "table:table":
                        if (sheet) {
                            final String name = p.getAttributeValue(null, "table:name");
                            out.append('\n').append(name == null ? "" : name).append('\n');
                            out.setSpan(new StyleSpan(Typeface.BOLD), out.length() - (name == null ? 0 : name.length()) - 1,
                                    out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        break;
                    case "table:table-cell":
                        cells++;
                        break;
                    case "draw:page":
                        slide++;
                        out.append("\n— ").append(String.valueOf(slide)).append(" —\n");
                        break;
                    default:
                        break;
                }
            } else if (event == XmlPullParser.END_TAG) {
                switch (tag) {
                    case "text:p":
                        paragraphs--;
                        out.append(cells > 0 ? "" : "\n");
                        break;
                    case "text:h":
                        paragraphs--;
                        out.setSpan(new StyleSpan(Typeface.BOLD), heading, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new RelativeSizeSpan(1.3f), heading, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.append('\n');
                        break;
                    case "table:table-cell":
                        cells--;
                        out.append(sheet ? "\t" : "  │  ");
                        break;
                    case "table:table-row":
                        out.append('\n');
                        break;
                    default:
                        break;
                }
            } else if (event == XmlPullParser.TEXT && paragraphs > 0) {
                out.append(p.getText());
            }
        }
        return sheet ? layoutTabs(out.toString()) : out;
    }

    private static String layoutTabs(String text) {
        final List<List<String>> rows = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            final List<String> row = new ArrayList<>(Arrays.asList(line.split("\t", -1)));
            while (row.size() > 1 && row.get(row.size() - 1).isEmpty()) {
                row.remove(row.size() - 1);
            }
            rows.add(row);
        }
        return layout(rows);
    }

    private static CharSequence pptx(Map<String, byte[]> parts) throws IOException, XmlPullParserException {
        final List<String> names = new ArrayList<>();
        for (String name : parts.keySet()) {
            if (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            throw new IOException("No slides");
        }
        Collections.sort(names, (a, b) -> Integer.compare(number(a), number(b)));
        final SpannableStringBuilder out = new SpannableStringBuilder();
        int index = 0;
        for (String name : names) {
            final int start = out.length();
            out.append("Slide ").append(String.valueOf(++index)).append('\n');
            out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new RelativeSizeSpan(1.3f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            final XmlPullParser p = parser(parts.get(name));
            boolean inText = false;
            for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
                if (event == XmlPullParser.START_TAG && "a:t".equals(p.getName())) {
                    inText = true;
                } else if (event == XmlPullParser.END_TAG && "a:t".equals(p.getName())) {
                    inText = false;
                } else if (event == XmlPullParser.END_TAG && "a:p".equals(p.getName())) {
                    out.append('\n');
                } else if (event == XmlPullParser.TEXT && inText) {
                    out.append(p.getText());
                }
            }
            out.append('\n');
        }
        return out;
    }

    private static int number(String name) {
        final String digits = name.replaceAll("\\D", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits.length() > 9 ? digits.substring(0, 9) : digits);
    }

    private static CharSequence xlsx(Map<String, byte[]> parts) throws IOException, XmlPullParserException {
        final List<String> shared = new ArrayList<>();
        if (parts.containsKey("xl/sharedStrings.xml")) {
            final XmlPullParser p = parser(parts.get("xl/sharedStrings.xml"));
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
        final Map<String, String> targets = new HashMap<>();
        if (parts.containsKey("xl/_rels/workbook.xml.rels")) {
            final XmlPullParser p = parser(parts.get("xl/_rels/workbook.xml.rels"));
            for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
                if (event == XmlPullParser.START_TAG && "Relationship".equals(p.getName())) {
                    targets.put(p.getAttributeValue(null, "Id"), p.getAttributeValue(null, "Target"));
                }
            }
        }
        final List<String[]> sheets = new ArrayList<>();
        if (parts.containsKey("xl/workbook.xml")) {
            final XmlPullParser p = parser(parts.get("xl/workbook.xml"));
            for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
                if (event == XmlPullParser.START_TAG && "sheet".equals(p.getName())) {
                    final String target = targets.get(p.getAttributeValue(null, "r:id"));
                    if (target != null) {
                        sheets.add(new String[]{p.getAttributeValue(null, "name"),
                                target.startsWith("/") ? target.substring(1) : "xl/" + target});
                    }
                }
            }
        }
        if (sheets.isEmpty()) {
            throw new IOException("No sheets");
        }
        final SpannableStringBuilder out = new SpannableStringBuilder();
        for (String[] sheet : sheets) {
            final byte[] xml = parts.get(sheet[1]);
            if (xml == null) {
                continue;
            }
            appendSheet(out, sheet[0], sheetRows(xml, shared));
        }
        return out;
    }

    private static CharSequence sheets(List<BinaryOffice.Sheet> sheets) {
        final SpannableStringBuilder out = new SpannableStringBuilder();
        for (BinaryOffice.Sheet sheet : sheets) {
            appendSheet(out, sheet.name, sheet.rows);
        }
        return out;
    }

    private static void appendSheet(SpannableStringBuilder out, String name, List<List<String>> rows) {
        final int start = out.length();
        out.append(name).append('\n');
        out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(1.2f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append(layout(rows)).append('\n');
    }

    private static CharSequence slides(List<List<String>> slides) throws IOException {
        if (slides.isEmpty()) {
            throw new IOException("No slides");
        }
        final SpannableStringBuilder out = new SpannableStringBuilder();
        int index = 0;
        for (List<String> slide : slides) {
            final int start = out.length();
            out.append("Slide ").append(String.valueOf(++index)).append('\n');
            out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new RelativeSizeSpan(1.3f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            for (String text : slide) {
                out.append(text).append('\n');
            }
            out.append('\n');
        }
        return out;
    }

    private static List<List<String>> sheetRows(byte[] xml, List<String> shared)
            throws IOException, XmlPullParserException {
        final List<List<String>> rows = new ArrayList<>();
        final XmlPullParser p = parser(xml);
        List<String> row = null;
        String type = null;
        int column = 0;
        StringBuilder value = null;
        boolean inValue = false;
        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT && rows.size() < MAX_ROWS;
             event = p.next()) {
            final String tag = p.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("row".equals(tag)) {
                    row = new ArrayList<>();
                } else if ("c".equals(tag)) {
                    type = p.getAttributeValue(null, "t");
                    column = columnOf(p.getAttributeValue(null, "r"), row == null ? 0 : row.size());
                    value = new StringBuilder();
                } else if ("v".equals(tag) || "t".equals(tag)) {
                    inValue = true;
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("v".equals(tag) || "t".equals(tag)) {
                    inValue = false;
                } else if ("c".equals(tag) && row != null && value != null) {
                    String text = value.toString();
                    if ("s".equals(type)) {
                        final int at = text.isEmpty() ? -1 : Integer.parseInt(text.trim());
                        text = at >= 0 && at < shared.size() ? shared.get(at) : "";
                    } else if ("b".equals(type)) {
                        text = "1".equals(text) ? "TRUE" : "FALSE";
                    }
                    while (row.size() < Math.min(column, MAX_COLUMNS)) {
                        row.add("");
                    }
                    row.add(text);
                    value = null;
                } else if ("row".equals(tag) && row != null) {
                    rows.add(row);
                    row = null;
                }
            } else if (event == XmlPullParser.TEXT && inValue && value != null) {
                value.append(p.getText());
            }
        }
        return rows;
    }

    /** "C7" is column 2; a missing reference means the next cell */
    private static int columnOf(String ref, int fallback) {
        if (ref == null) {
            return fallback;
        }
        int column = 0;
        for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) {
            column = column * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
        }
        return column - 1;
    }

    /** Reads chapters in spine order, as the book's package file lists them */
    private static CharSequence epub(Map<String, byte[]> parts) throws IOException, XmlPullParserException {
        if (parts.containsKey("META-INF/rights.xml") || parts.containsKey("META-INF/encryption.xml")
                && new String(parts.get("META-INF/encryption.xml")).contains("EncryptedData")
                && !new String(parts.get("META-INF/encryption.xml")).contains("fontobfuscation")) {
            throw new IOException("Protected book");
        }
        final byte[] container = parts.get("META-INF/container.xml");
        if (container == null) {
            throw new IOException("Not an EPUB");
        }
        String opf = null;
        final XmlPullParser c = parser(container);
        for (int event = c.getEventType(); event != XmlPullParser.END_DOCUMENT && opf == null; event = c.next()) {
            if (event == XmlPullParser.START_TAG && "rootfile".equals(c.getName())) {
                opf = c.getAttributeValue(null, "full-path");
            }
        }
        if (opf == null || !parts.containsKey(opf)) {
            throw new IOException("Missing package");
        }
        final Map<String, String> manifest = new HashMap<>();
        final List<String> spine = new ArrayList<>();
        final XmlPullParser p = parser(parts.get(opf));
        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            if (event != XmlPullParser.START_TAG) {
                continue;
            }
            if ("item".equals(p.getName())) {
                manifest.put(p.getAttributeValue(null, "id"), p.getAttributeValue(null, "href"));
            } else if ("itemref".equals(p.getName())) {
                spine.add(p.getAttributeValue(null, "idref"));
            }
        }
        final String base = opf.contains("/") ? opf.substring(0, opf.lastIndexOf('/') + 1) : "";
        final SpannableStringBuilder out = new SpannableStringBuilder();
        for (String id : spine) {
            final String href = manifest.get(id);
            final byte[] chapter = href == null ? null : parts.get(resolve(base, href));
            if (chapter == null) {
                continue;
            }
            String html = decode(chapter)
                    .replaceAll("(?is)<(head|style|script|svg)\\b.*?</\\1>", "")
                    .replaceAll("(?is)<img\\b[^>]*>", "");
            final Spanned body = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY) : Html.fromHtml(html);
            out.append(body).append("\n\n");
            if (out.length() > MAX_BOOK_CHARS) {
                break;
            }
        }
        if (out.length() == 0) {
            throw new IOException("Empty book");
        }
        return trimBlankLines(out);
    }

    /** Joins a relative href to the package folder, resolving "." and ".." and %-escapes */
    private static String resolve(String base, String href) {
        final String path = base + android.net.Uri.decode(href.split("#")[0]);
        final List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
            } else if (!part.equals(".") && !part.isEmpty()) {
                parts.add(part);
            }
        }
        final StringBuilder out = new StringBuilder();
        for (String part : parts) {
            out.append(out.length() == 0 ? "" : "/").append(part);
        }
        return out.toString();
    }

    /** Html.fromHtml leaves runs of empty lines between blocks */
    private static CharSequence trimBlankLines(SpannableStringBuilder text) {
        for (int i = text.length() - 1; i >= 2; i--) {
            if (text.charAt(i) == '\n' && text.charAt(i - 1) == '\n' && text.charAt(i - 2) == '\n') {
                text.delete(i, i + 1);
            }
        }
        return text;
    }

    // ---- RTF ----

    private static final Set<String> RTF_SKIPPED = new HashSet<>(Arrays.asList(
            "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "object", "themedata",
            "datastore", "latentstyles", "listtable", "listoverridetable", "rsidtbl", "generator"));

    /** Strips the control words and keeps paragraphs, tabs and escaped characters */
    private static String rtf(String s) {
        final StringBuilder out = new StringBuilder();
        final List<Boolean> skips = new ArrayList<>();
        boolean skip = false;
        final int n = s.length();
        int i = 0;
        while (i < n) {
            final char c = s.charAt(i);
            if (c == '{') {
                skips.add(skip);
                int j = i + 1;
                if (j < n && s.charAt(j) == '\\') {
                    if (j + 1 < n && s.charAt(j + 1) == '*') {
                        skip = true;
                    } else {
                        int k = j + 1;
                        while (k < n && Character.isLetter(s.charAt(k))) {
                            k++;
                        }
                        if (RTF_SKIPPED.contains(s.substring(j + 1, k))) {
                            skip = true;
                        }
                    }
                }
                i++;
            } else if (c == '}') {
                if (!skips.isEmpty()) {
                    skip = skips.remove(skips.size() - 1);
                }
                i++;
            } else if (c == '\\') {
                i++;
                if (i >= n) {
                    break;
                }
                final char d = s.charAt(i);
                if (d == '\'' && i + 2 < n) {
                    if (!skip) {
                        try {
                            out.append(new String(new byte[]{(byte) Integer.parseInt(s.substring(i + 1, i + 3), 16)},
                                    Charset.forName("windows-1252")));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    i += 3;
                } else if (Character.isLetter(d)) {
                    int j = i;
                    while (j < n && Character.isLetter(s.charAt(j))) {
                        j++;
                    }
                    final String word = s.substring(i, j);
                    int k = j;
                    if (k < n && (s.charAt(k) == '-' || Character.isDigit(s.charAt(k)))) {
                        k++;
                        while (k < n && Character.isDigit(s.charAt(k))) {
                            k++;
                        }
                    }
                    if (!skip) {
                        if (word.equals("par") || word.equals("line")) {
                            out.append('\n');
                        } else if (word.equals("tab")) {
                            out.append('\t');
                        } else if (word.equals("u") && k > j) {
                            int code = Integer.parseInt(s.substring(j, k));
                            out.append((char) (code < 0 ? code + 65536 : code));
                            if (k < n && s.charAt(k) == ' ') {
                                k++;
                            }
                            if (k < n) {
                                k++;
                            }
                            i = k;
                            continue;
                        }
                    }
                    if (k < n && s.charAt(k) == ' ') {
                        k++;
                    }
                    i = k;
                } else {
                    if (!skip && (d == '\\' || d == '{' || d == '}')) {
                        out.append(d);
                    } else if (!skip && d == '~') {
                        out.append(' ');
                    }
                    i++;
                }
            } else {
                if (!skip && c != '\r' && c != '\n') {
                    out.append(c);
                }
                i++;
            }
        }
        return out.toString();
    }
}
