package dev.dworks.apps.anexplorer.misc;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shows the binary Office files (.doc, .xls, .xlsb, .ppt, .pps) as a styled HTML page for a
 * WebView: the Word text with its formatting, worksheets as grids and slides as cards.
 */
public final class OfficeHtml {

    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ROWS = 2000;
    private static final int MAX_COLUMNS = 50;
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    private static final String STYLE = "<style>"
            + "body{margin:0;background:#303030;font-family:sans-serif;color:#202020}"
            + "#page{zoom:1.35;box-sizing:border-box;max-width:820px;margin:24px auto;padding:48px 56px;"
            + "background:#fff;min-height:600px}"
            + "#wide{zoom:1.25;padding:20px}"
            + "p{margin:0 0 .6em;line-height:1.35}"
            + "h1,h2,h3,h4,h5,h6{margin:.8em 0 .4em;color:#1f3864}"
            + "hr{border:0;border-top:2px dashed #bbb;margin:1.5em 0}"
            + "img{max-width:100%}.m{display:inline-block;min-width:22px}"
            + "table{border-collapse:collapse;margin:.6em 0}"
            + "#page td{border:1px solid #999;padding:4px 8px;vertical-align:top}"
            + "#page td p{margin:0}"
            + "h2.s{margin:0 0 8px;color:#fff;font-size:20px}"
            + "table.g{background:#fff;font-size:15px;margin:0 0 32px}"
            + "table.g td,table.g th{border:1px solid #c8c8c8;padding:3px 10px;white-space:pre}"
            + "table.g th{background:#e6e6e6;color:#555;font-weight:normal;font-size:13px}"
            + "table.g th.r{text-align:center}"
            + "table.g td.n{text-align:right}"
            + "table.g tr:nth-child(even) td{background:#f6f8fb}"
            + ".card{box-sizing:border-box;width:640px;min-height:360px;margin:0 auto 24px;"
            + "padding:28px 40px;background:#fff;border-radius:6px;position:relative}"
            + ".card h1{margin:0 0 .6em;font-size:34px;color:#1f3864}"
            + ".card ul{margin:0;padding-left:1.2em}"
            + ".card li,.card p{font-size:22px;line-height:1.35;margin:0 0 .4em}"
            + ".card .n{position:absolute;right:16px;bottom:8px;color:#888;font-size:14px}"
            + "</style>";

    private OfficeHtml() {
    }

    /** @return whether the file is one of the binary Office formats this page shows */
    public static boolean supports(String ext) {
        return "|doc|xls|xlsb|ppt|pps|".contains("|" + ext + "|");
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
            case "xls":
                sheets(out, BinaryOffice.xls(PreviewFormatter.readAll(in, MAX_BYTES), MAX_ROWS, MAX_COLUMNS));
                break;
            case "xlsb":
                sheets(out, BinaryOffice.xlsb(PreviewFormatter.zip(in, "xl/"), MAX_ROWS, MAX_COLUMNS));
                break;
            default:
                slides(out, BinaryOffice.ppt(PreviewFormatter.readAll(in, MAX_BYTES)));
                break;
        }
        return out.toString();
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
            out.append("<h2 class=s>").append(esc(sheet.name)).append("</h2><table class=g><tr><th>");
            for (int c = 0; c < columns; c++) {
                out.append("<th class=r>").append(column(c));
            }
            int number = 0;
            for (List<String> row : sheet.rows) {
                out.append("<tr><th>").append(++number);
                for (int c = 0; c < columns; c++) {
                    final String value = c < row.size() ? row.get(c) : "";
                    out.append(NUMBER.matcher(value).matches() ? "<td class=n>" : "<td>").append(esc(value));
                }
            }
            out.append("</table>");
        }
        out.append("</div>");
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
