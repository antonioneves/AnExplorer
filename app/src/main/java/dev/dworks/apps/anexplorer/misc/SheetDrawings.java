package dev.dworks.apps.anexplorer.misc;

import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.attr;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.child;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.children;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.fmt;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.num;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.path;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The pictures and charts that sit on Excel worksheets, at the size the drawing gives them. Each is
 * placed over the cell it is anchored to; one with no usable anchor goes below the table.
 */
final class SheetDrawings {

    private static final double EMU_PER_PIXEL = 9525;
    private static final double MAX_WIDTH = 900;

    private SheetDrawings() {
    }

    /** One picture or chart: its HTML, the 1-based sheet row and 0-based column it starts in, and its offset in px (row 0 if unanchored) */
    static final class Item {
        final String html;
        final int row, column;
        final double dx, dy;

        Item(String html, int row, int column, double dx, double dy) {
            this.html = html;
            this.row = row;
            this.column = column;
            this.dx = dx;
            this.dy = dy;
        }
    }

    /** @return for each sheet its pictures and charts, empty if it has none */
    static List<List<Item>> html(Map<String, byte[]> parts, List<BinaryOffice.Sheet> sheets) {
        final OoxmlPackage pkg = OoxmlPackage.of(parts);
        final OoxmlTheme theme = new OoxmlTheme(pkg.xml("xl/theme/theme1.xml"));
        final List<List<Item>> out = new ArrayList<>();
        for (BinaryOffice.Sheet sheet : sheets) {
            List<Item> items = new ArrayList<>();
            try {
                items = sheet.part == null ? items : sheet(pkg, theme, sheet.part);
            } catch (RuntimeException e) {
                items = new ArrayList<>();
            }
            out.add(items);
        }
        return out;
    }

    private static List<Item> sheet(OoxmlPackage pkg, OoxmlTheme theme, String sheetPart) {
        final List<Item> items = new ArrayList<>();
        final String drawing = pkg.targetOfType(sheetPart, "drawing");
        final Element root = drawing == null ? null : pkg.xml(drawing);
        if (root == null) {
            return items;
        }
        for (Element anchor : children(root, null)) {
            final StringBuilder out = new StringBuilder();
            final Element pic = child(anchor, "xdr:pic");
            final Element frame = child(anchor, "xdr:graphicFrame");
            if (pic != null) {
                final String uri = pkg.picture(pkg.target(drawing,
                        attr(path(pic, "xdr:blipFill", "a:blip"), "r:embed")));
                if (uri != null) {
                    final double[] size = size(path(pic, "xdr:spPr", "a:xfrm"), 320, 240);
                    out.append("<img src=\"").append(uri).append("\" style=\"width:").append(fmt(size[0]))
                            .append("px;max-width:100%;height:auto\">");
                }
            } else if (frame != null) {
                final Element chart = path(frame, "a:graphic", "a:graphicData", "c:chart");
                final String chartPart = chart == null ? null : pkg.target(drawing, attr(chart, "r:id"));
                final Element space = chartPart == null ? null : pkg.xml(chartPart);
                if (space != null) {
                    final double[] size = size(child(frame, "xdr:xfrm"), 480, 288);
                    out.append("<div style=\"display:inline-block;max-width:100%;overflow:hidden;background:#fff\">");
                    PptxChart.draw(space, theme, size[0], size[1], out);
                    out.append("</div>");
                }
            }
            if (out.length() > 0) {
                final Element from = child(anchor, "xdr:from");
                items.add(from == null ? new Item(out.toString(), 0, 0, 0, 0)
                        : new Item(out.toString(), (int) number(from, "xdr:row") + 1, (int) number(from, "xdr:col"),
                        number(from, "xdr:colOff") / EMU_PER_PIXEL, number(from, "xdr:rowOff") / EMU_PER_PIXEL));
            }
        }
        return items;
    }

    /** @return the number in a child element's text, such as the row of an anchor, or 0 */
    private static long number(Element parent, String name) {
        try {
            return Long.parseLong(OoxmlPackage.text(child(parent, name)).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** @return the width and height in pixels of a drawing's xfrm, or the fallback, scaled to fit the page */
    private static double[] size(Element xfrm, double w, double h) {
        final Element ext = child(xfrm, "a:ext");
        if (ext != null && num(ext, "cx", 0) > 0 && num(ext, "cy", 0) > 0) {
            w = num(ext, "cx", 0) / EMU_PER_PIXEL;
            h = num(ext, "cy", 0) / EMU_PER_PIXEL;
        }
        if (w > MAX_WIDTH) {
            h = h * MAX_WIDTH / w;
            w = MAX_WIDTH;
        }
        return new double[]{w, h};
    }
}
