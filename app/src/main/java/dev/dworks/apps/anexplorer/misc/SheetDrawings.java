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
 * The pictures and charts that sit on Excel worksheets. They are not placed over the cells: each
 * sheet shows them in a row below its table, at the size the drawing gives them.
 */
final class SheetDrawings {

    private static final double EMU_PER_PIXEL = 9525;
    private static final double MAX_WIDTH = 900;

    private SheetDrawings() {
    }

    /** @return for each sheet the HTML of its pictures and charts, empty if it has none */
    static List<String> html(Map<String, byte[]> parts, List<BinaryOffice.Sheet> sheets) {
        final OoxmlPackage pkg = OoxmlPackage.of(parts);
        final OoxmlTheme theme = new OoxmlTheme(pkg.xml("xl/theme/theme1.xml"));
        final List<String> out = new ArrayList<>();
        for (BinaryOffice.Sheet sheet : sheets) {
            String html = "";
            try {
                html = sheet.part == null ? "" : sheet(pkg, theme, sheet.part);
            } catch (RuntimeException e) {
                html = "";
            }
            out.add(html);
        }
        return out;
    }

    private static String sheet(OoxmlPackage pkg, OoxmlTheme theme, String sheetPart) {
        final String drawing = pkg.targetOfType(sheetPart, "drawing");
        final Element root = drawing == null ? null : pkg.xml(drawing);
        if (root == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        for (Element anchor : children(root, null)) {
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
        }
        return out.length() == 0 ? "" : "<div class=dr>" + out + "</div>";
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
