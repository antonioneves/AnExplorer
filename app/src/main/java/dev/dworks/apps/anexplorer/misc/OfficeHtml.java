package dev.dworks.apps.anexplorer.misc;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shows the binary Office files (.doc, .xls, .xlsb, .ppt, .pps) as a styled HTML page for a
 * WebView: the Word text with its formatting, worksheets as grids and slides as cards.
 */
public final class OfficeHtml {

    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ROWS = 2000;
    private static final int MAX_COLUMNS = 50;

    private static final String STYLE = "<style>"
            + "body{margin:0;background:#303030;font-family:sans-serif;color:#202020}"
            + "#page{zoom:1.35;box-sizing:border-box;max-width:820px;margin:24px auto;padding:48px 56px;"
            + "background:#fff;min-height:600px;font-size:10pt}"
            + "#wide{zoom:1.25;padding:20px}"
            + "p{margin:0;line-height:1.2}"
            + "h1,h2,h3,h4,h5,h6{margin:0;font-size:inherit;font-weight:normal;line-height:1.2}"
            + "hr{border:0;border-top:2px dashed #bbb;margin:1.5em 0}"
            + "img{max-width:100%}.m{display:inline-block;min-width:22px}"
            + "table{border-collapse:collapse;margin:.6em 0}"
            + "#page td{box-sizing:border-box;border:1px solid #999;padding:2px 5px;vertical-align:top}"
            + "#page td p{margin:0}"
            + "h2.s{margin:0 0 8px;color:#fff;font-size:20px;font-weight:bold}"
            + "table.g{background:#fff;font-size:15px;margin:0 0 32px}"
            + "table.g td,table.g th{border:1px solid #c8c8c8;padding:3px 10px;white-space:pre}"
            + "table.g th{background:#e6e6e6;color:#555;font-weight:normal;font-size:13px}"
            + "table.g th.r{text-align:center}"
            + "table.g td{background:#fff}"
            + ".card{box-sizing:border-box;width:640px;min-height:360px;margin:0 auto 24px;"
            + "padding:28px 40px;background:#fff;border-radius:6px;position:relative}"
            + ".card h1{margin:0 0 .6em;font-size:34px;color:#1f3864;font-weight:bold}"
            + ".card ul{margin:0;padding-left:1.2em}"
            + ".card li,.card p{font-size:22px;line-height:1.35;margin:0 0 .4em}"
            + ".card .n,.slide .n{position:absolute;right:16px;bottom:8px;color:#888;font-size:14px}"
            + "#deck{padding:20px 0}"
            + ".slide{position:relative;overflow:hidden;margin:0 auto 24px;background:#fff}"
            + ".slide p{line-height:1.2}.slide .t{box-sizing:border-box;width:100%;height:100%;display:flex;"
            + "flex-direction:column;overflow-wrap:break-word}.slide img{max-width:none}"
            + ".slide .o{position:absolute;box-sizing:border-box}.slide td{padding:0}.slide td p{margin:0}"
            + ".sh{display:inline-block;vertical-align:top;overflow:hidden}span.p{display:block;margin:0 0 .3em;"
            + "line-height:1.3}"
            + ".hd{border-bottom:1px dashed #bbb;margin-bottom:1.2em;color:#555}"
            + ".ft{border-top:1px dashed #bbb;margin-top:1.5em;color:#555}"
            + ".fn{margin-top:2em;border-top:1px solid #999;padding-top:.6em;font-size:.85em}"
            + "</style>";

    private OfficeHtml() {
    }

    /** @return whether the file is one of the binary Office formats this page shows */
    public static boolean supports(String ext) {
        return "|doc|xls|xlsx|xlsm|xltx|xlsb|ppt|pps|pptx|pptm|ppsx|potx|docx|docm|dotx|".contains("|" + ext + "|");
    }

    /** @return the whole HTML page; throws if the file is encrypted or unreadable */
    public static String page(String ext, InputStream in) throws IOException {
        final StringBuilder out = new StringBuilder("<!doctype html><meta charset=utf-8>"
                + "<meta name=viewport content=\"width=device-width\">").append(STYLE);
        switch (ext) {
            case "doc":
                out.append("<div id=page>").append(WordReader.html(PreviewFormatter.readAll(in, MAX_BYTES)))
                        .append("</div>");
                break;
            case "docx":
            case "docm":
            case "dotx": {
                final byte[] file = PreviewFormatter.readAll(in, MAX_BYTES);
                String drawn;
                try {
                    drawn = DocxPage.html(OoxmlPackage.read(new java.io.ByteArrayInputStream(file)));
                } catch (RuntimeException | StackOverflowError e) {
                    // An odd document the reader can't follow: show its text instead
                    drawn = "<div style=\"white-space:pre-wrap\">" + OoxmlPackage.esc(PreviewFormatter.document("docx",
                            new java.io.ByteArrayInputStream(file)).toString()) + "</div>";
                }
                out.append("<div id=page>").append(drawn).append("</div>");
                break;
            }
            case "xls":
                sheets(out, BinaryOffice.xls(PreviewFormatter.readAll(in, MAX_BYTES), MAX_ROWS, MAX_COLUMNS));
                break;
            case "xlsx":
            case "xlsm":
            case "xltx":
                sheets(out, BinaryOffice.xlsx(PreviewFormatter.zip(in, "xl/"), MAX_ROWS, MAX_COLUMNS));
                break;
            case "xlsb":
                sheets(out, BinaryOffice.xlsb(PreviewFormatter.zip(in, "xl/"), MAX_ROWS, MAX_COLUMNS));
                break;
            case "pptx":
            case "pptm":
            case "ppsx":
            case "potx":
                out.append(PptxSlides.html(OoxmlPackage.read(in)));
                break;
            default: {
                final byte[] file = PreviewFormatter.readAll(in, MAX_BYTES);
                String drawn;
                try {
                    drawn = PptSlides.html(file);
                } catch (IOException | RuntimeException e) {
                    // Encrypted or damaged drawing data: fall back to the text of the slides
                    drawn = null;
                }
                if (drawn != null) {
                    out.append(drawn);
                } else {
                    slides(out, BinaryOffice.ppt(file));
                }
                break;
            }
        }
        return out.toString();
    }

    /** @return how many slides a page made by {@link #page} holds, or 0 if it is not a slide deck */
    public static int slideCount(String page) {
        int count = 0;
        for (int at = page.indexOf("<div class=slide id=s"); at >= 0; at = page.indexOf("<div class=slide id=s", at + 1)) {
            count++;
        }
        return count;
    }

    /** @return how many clicks reveal or remove shapes on slide number {@code index} (from 1) */
    public static int slideSteps(String page, int index) {
        final String key = "<div class=slide id=s" + index + " data-steps=";
        final int at = page.indexOf(key);
        if (at < 0) {
            return 0;
        }
        final int from = at + key.length();
        int to = from;
        while (to < page.length() && Character.isDigit(page.charAt(to))) {
            to++;
        }
        return to > from ? Integer.parseInt(page.substring(from, to)) : 0;
    }

    /**
     * @return the slide deck page showing only slide number {@code index} (from 1), scaled to
     * fit a screen of the given size in CSS pixels, with the shapes as they are after {@code step} clicks
     */
    public static String showSlide(String page, int index, int step, double screenW, double screenH) {
        final int deck = page.indexOf("<div id=deck");
        if (deck < 0) {
            return page;
        }
        double zoom = 0;
        final int slide = page.indexOf("<div class=slide id=s" + index + " ");
        if (slide >= 0) {
            try {
                final int w0 = page.indexOf("width:", slide) + 6, w1 = page.indexOf("px", w0);
                final int h0 = page.indexOf("height:", w1) + 7, h1 = page.indexOf("px", h0);
                zoom = Math.min(screenW / Double.parseDouble(page.substring(w0, w1)),
                        screenH / Double.parseDouble(page.substring(h0, h1))) * 0.97;
            } catch (RuntimeException e) {
                zoom = 0;
            }
        }
        final StringBuilder clicks = new StringBuilder();
        for (int n = 1; n <= slideSteps(page, index); n++) {
            // Shapes that enter on a later click, and shapes that already left, are hidden
            clicks.append(n > step ? ".k" : ".x").append(n).append("{visibility:hidden}");
        }
        return page.substring(0, deck) + "<style>" + clicks + ".slide{display:none}#s" + index + "{display:block}"
                + (zoom > 0 ? "#deck{zoom:" + zoom + "!important;padding:0!important}body{margin:0;display:flex;"
                + "align-items:center;justify-content:center;min-height:100vh}.slide{margin:0 auto!important}" : "")
                + "</style>" + page.substring(deck);
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    private static String column(int index) {
        final StringBuilder name = new StringBuilder();
        for (int left = index + 1; left > 0; left = (left - 1) / 26) {
            name.insert(0, (char) ('A' + (left - 1) % 26));
        }
        return name.toString();
    }

    private static void sheets(StringBuilder out, List<BinaryOffice.Sheet> sheets) {
        out.append("<div id=wide>");
        for (BinaryOffice.Sheet sheet : sheets) {
            int columns = 0;
            for (List<String> row : sheet.rows) {
                columns = Math.max(columns, row.size());
            }
            // A merged range shows at its first cell and covers the others
            final Map<Long, int[]> spans = new HashMap<>();
            final Set<Long> covered = new HashSet<>();
            for (int[] m : sheet.merges) {
                int rows = 0;
                for (int number : sheet.rowNumbers) {
                    if (number - 1 >= m[0] && number - 1 <= m[1]) {
                        rows++;
                    }
                }
                spans.put(key(m[0] + 1, m[2]), new int[]{Math.max(1, rows), m[3] - m[2] + 1});
                for (int r = m[0]; r <= m[1]; r++) {
                    for (int c = m[2]; c <= m[3]; c++) {
                        if (r != m[0] || c != m[2]) {
                            covered.add(key(r + 1, c));
                        }
                    }
                }
            }
            out.append("<h2 class=s>").append(esc(sheet.name)).append("</h2><table class=g><colgroup><col class=h>");
            for (int c = 0; c < columns; c++) {
                final Integer width = sheet.columnWidths.get(c);
                out.append(width == null ? "<col>" : "<col style=\"min-width:" + width + "px\">");
            }
            out.append("</colgroup><tr><th>");
            for (int c = 0; c < columns; c++) {
                out.append("<th class=r>").append(column(c));
            }
            for (int r = 0; r < sheet.rows.size(); r++) {
                final List<String> row = sheet.rows.get(r);
                final List<String> styles = sheet.styles.get(r);
                final int number = sheet.rowNumbers.get(r);
                out.append("<tr><th>").append(number);
                for (int c = 0; c < columns; c++) {
                    if (covered.contains(key(number, c))) {
                        continue;
                    }
                    final String value = c < row.size() ? row.get(c) : "";
                    final String style = c < styles.size() ? styles.get(c) : "";
                    final int[] span = spans.get(key(number, c));
                    out.append("<td");
                    if (span != null && span[0] > 1) {
                        out.append(" rowspan=").append(span[0]);
                    }
                    if (span != null && span[1] > 1) {
                        out.append(" colspan=").append(span[1]);
                    }
                    if (!style.isEmpty()) {
                        out.append(" style=\"").append(style).append('"');
                    }
                    out.append('>').append(esc(value));
                }
            }
            out.append("</table>");
        }
        out.append("</div>");
    }

    private static long key(int row, int column) {
        return (long) row << 32 | column;
    }

    private static void slides(StringBuilder out, List<List<String>> slides) throws IOException {
        if (slides.isEmpty()) {
            throw new IOException("No slides");
        }
        out.append("<div id=wide>");
        int index = 0;
        for (List<String> slide : slides) {
            out.append("<div class=card>");
            for (int i = 0; i < slide.size(); i++) {
                final String text = slide.get(i);
                if (i == 0) {
                    out.append("<h1>").append(esc(text)).append("</h1>");
                } else if (text.indexOf('\n') >= 0) {
                    out.append("<ul>");
                    for (String line : text.split("\n")) {
                        if (!line.trim().isEmpty()) {
                            out.append("<li>").append(esc(line.trim())).append("</li>");
                        }
                    }
                    out.append("</ul>");
                } else {
                    out.append("<p>").append(esc(text)).append("</p>");
                }
            }
            out.append("<span class=n>").append(++index).append(" / ").append(slides.size()).append("</span></div>");
        }
        out.append("</div>");
    }
}
