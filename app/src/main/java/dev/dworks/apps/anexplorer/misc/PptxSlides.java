package dev.dworks.apps.anexplorer.misc;

import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.attr;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.child;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.children;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.esc;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.flag;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.fmt;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.num;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.path;

import org.w3c.dom.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws the slides of a .pptx, .pptm or .ppsx as absolutely placed HTML: master, layout and slide
 * shapes with their fills, lines, pictures, tables, groups, charts and text, using the theme and the
 * placeholder inheritance of the master and layout. The reading follows the structure of
 * Paperwren's pptx parser (MIT, https://github.com/antonioneves/Paperwren), rewritten in Java.
 */
final class PptxSlides {

    private static final int MAX_SLIDES = 300;
    private static final double EMU = 9525;

    private final OoxmlPackage pkg;
    private final Map<String, OoxmlTheme> themes = new HashMap<>();
    private Element defaultText;
    private int markers;

    /** Where a drawing's own coordinates land on the slide: a scale and an offset, in EMU */
    private static final class Tx {
        final double sx, sy, dx, dy;

        Tx(double sx, double sy, double dx, double dy) {
            this.sx = sx;
            this.sy = sy;
            this.dx = dx;
            this.dy = dy;
        }

        double x(double v) {
            return v * sx + dx;
        }

        double y(double v) {
            return v * sy + dy;
        }
    }

    /** The slide being drawn and the layout and master it inherits from */
    private static final class Ctx {
        String slide, layoutPart, masterPart;
        Element layout, master;
        OoxmlTheme theme;
    }

    private static final class Line {
        double width = -1;
        String color;
        boolean none;
        String dash = "solid";
        boolean head, tail;
        String join;
    }

    private PptxSlides(OoxmlPackage pkg) {
        this.pkg = pkg;
    }

    static String html(OoxmlPackage pkg) throws IOException {
        return new PptxSlides(pkg).render();
    }

    private String render() throws IOException {
        final String presPart = "ppt/presentation.xml";
        final Element pres = pkg.xml(presPart);
        if (pres == null) {
            throw new IOException("Not a PowerPoint presentation");
        }
        defaultText = child(pres, "p:defaultTextStyle");
        final Element size = child(pres, "p:sldSz");
        final double width = num(size, "cx", 9144000) / EMU;
        final double height = num(size, "cy", 6858000) / EMU;
        final List<String> slides = new ArrayList<>();
        for (Element id : children(child(pres, "p:sldIdLst"), "p:sldId")) {
            final String part = pkg.target(presPart, attr(id, "r:id"));
            if (part != null && pkg.has(part)) {
                slides.add(part);
            }
        }
        if (slides.isEmpty()) {
            throw new IOException("No slides");
        }
        final StringBuilder out = new StringBuilder();
        out.append("<div id=deck style=\"zoom:").append(fmt(900 / width)).append("\">");
        int count = 0;
        for (String slide : slides) {
            if (++count > MAX_SLIDES) {
                break;
            }
            out.append("<div class=slide style=\"width:").append(fmt(width)).append("px;height:")
                    .append(fmt(height)).append("px;");
            try {
                slide(slide, out);
            } catch (RuntimeException e) {
                out.append("\">");
            }
            out.append("<span class=n>").append(count).append(" / ").append(slides.size()).append("</span></div>");
            final String notes = notes(slide);
            if (!notes.isEmpty()) {
                out.append("<div class=notes style=\"width:").append(fmt(width)).append("px\">").append(notes)
                        .append("</div>");
            }
        }
        return out.append("</div>").toString();
    }

    private OoxmlTheme theme(String masterPart) {
        OoxmlTheme theme = themes.get(masterPart);
        if (theme == null) {
            final String themePart = masterPart == null ? null : pkg.targetOfType(masterPart, "theme");
            theme = new OoxmlTheme(themePart == null ? null : pkg.xml(themePart));
            final Element map = masterPart == null ? null : child(pkg.xml(masterPart), "p:clrMap");
            if (map != null) {
                final Map<String, String> colors = new HashMap<>();
                final org.w3c.dom.NamedNodeMap attrs = map.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    colors.put(attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
                }
                theme.colorMap = colors;
            }
            themes.put(masterPart, theme);
        }
        return theme;
    }

    /** Writes the rest of the slide's opening tag (its background) and then its content */
    private void slide(String part, StringBuilder out) {
        final Ctx ctx = new Ctx();
        ctx.slide = part;
        final Element slide = pkg.xml(part);
        ctx.layoutPart = pkg.targetOfType(part, "slideLayout");
        ctx.layout = ctx.layoutPart == null ? null : pkg.xml(ctx.layoutPart);
        ctx.masterPart = ctx.layoutPart == null ? null : pkg.targetOfType(ctx.layoutPart, "slideMaster");
        ctx.master = ctx.masterPart == null ? null : pkg.xml(ctx.masterPart);
        ctx.theme = theme(ctx.masterPart);

        String background = null;
        final Element[] chain = {slide, ctx.layout, ctx.master};
        final String[] parts = {part, ctx.layoutPart, ctx.masterPart};
        for (int i = 0; i < chain.length && background == null; i++) {
            background = background(chain[i], parts[i], ctx.theme);
        }
        out.append(background == null ? "background:#fff;" : background).append("\">");

        final boolean showLayout = flag(slide, "showMasterSp", true);
        final boolean showMaster = showLayout && (ctx.layout == null || flag(ctx.layout, "showMasterSp", true));
        final Tx root = new Tx(1, 1, 0, 0);
        if (showMaster && ctx.master != null) {
            tree(path(ctx.master, "p:cSld", "p:spTree"), ctx.masterPart, ctx, root, true, out);
        }
        if (showLayout && ctx.layout != null) {
            tree(path(ctx.layout, "p:cSld", "p:spTree"), ctx.layoutPart, ctx, root, true, out);
        }
        tree(path(slide, "p:cSld", "p:spTree"), part, ctx, root, false, out);
    }

    private String background(Element root, String part, OoxmlTheme theme) {
        final Element bg = path(root, "p:cSld", "p:bg");
        if (bg == null) {
            return null;
        }
        final Element props = child(bg, "p:bgPr");
        if (props != null) {
            return theme.fill(props, pkg, part);
        }
        final Element ref = child(bg, "p:bgRef");
        if (ref != null) {
            final int idx = (int) num(ref, "idx", 0);
            theme.phColor = theme.color(ref);
            final Element style = idx >= 1001 ? theme.style("a:bgFillStyleLst", idx - 1000)
                    : theme.style("a:fillStyleLst", idx);
            final String css = style == null ? theme.phColor == null ? null : "background:" + theme.phColor + ";"
                    : theme.fillElement(style, pkg, part);
            theme.phColor = null;
            return css;
        }
        return null;
    }

    // ---- shape tree ----

    private void tree(Element spTree, String part, Ctx ctx, Tx tx, boolean template, StringBuilder out) {
        if (spTree == null) {
            return;
        }
        for (Element e : children(spTree, null)) {
            element(e, part, ctx, tx, template, out);
        }
    }

    private void element(Element e, String part, Ctx ctx, Tx tx, boolean template, StringBuilder out) {
        switch (e.getNodeName()) {
            case "p:sp":
            case "dsp:sp":
                if (!template || path(e, "p:nvSpPr", "p:nvPr", "p:ph") == null) {
                    shape(e, part, ctx, tx, false, out);
                }
                break;
            case "p:cxnSp":
                shape(e, part, ctx, tx, true, out);
                break;
            case "p:pic":
                picture(e, part, ctx, tx, out);
                break;
            case "p:grpSp":
                group(e, part, ctx, tx, template, out);
                break;
            case "p:graphicFrame":
                frame(e, part, ctx, tx, out);
                break;
            case "mc:AlternateContent": {
                Element pick = child(e, "mc:Fallback");
                if (pick == null) {
                    pick = child(e, "mc:Choice");
                }
                if (pick != null) {
                    for (Element inner : children(pick, null)) {
                        element(inner, part, ctx, tx, template, out);
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private void group(Element g, String part, Ctx ctx, Tx tx, boolean template, StringBuilder out) {
        final Element xfrm = path(g, "p:grpSpPr", "a:xfrm");
        Tx inner = tx;
        if (xfrm != null) {
            final Element off = child(xfrm, "a:off"), ext = child(xfrm, "a:ext");
            final Element chOff = child(xfrm, "a:chOff"), chExt = child(xfrm, "a:chExt");
            final double ex = num(ext, "cx", 0), ey = num(ext, "cy", 0);
            final double cx = num(chExt, "cx", (long) ex), cy = num(chExt, "cy", (long) ey);
            final double kx = cx == 0 ? 1 : ex / cx, ky = cy == 0 ? 1 : ey / cy;
            final double ox = num(off, "x", 0), oy = num(off, "y", 0);
            final double px = num(chOff, "x", (long) ox), py = num(chOff, "y", (long) oy);
            inner = new Tx(tx.sx * kx, tx.sy * ky, tx.x(ox - px * kx) - tx.dx + tx.dx, tx.y(oy - py * ky) - tx.dy + tx.dy);
        }
        for (Element e : children(g, null)) {
            element(e, part, ctx, inner, template, out);
        }
    }

    // ---- geometry and boxes ----

    /** A shape's box in slide pixels, before rotation */
    private static final class Box {
        double x, y, w, h, rot;
        boolean flipH, flipV;
    }

    private static Box box(Element xfrm, Tx tx) {
        if (xfrm == null) {
            return null;
        }
        final Element off = child(xfrm, "a:off"), ext = child(xfrm, "a:ext");
        if (off == null || ext == null) {
            return null;
        }
        final Box b = new Box();
        b.x = tx.x(num(off, "x", 0)) / EMU;
        b.y = tx.y(num(off, "y", 0)) / EMU;
        b.w = num(ext, "cx", 0) * tx.sx / EMU;
        b.h = num(ext, "cy", 0) * tx.sy / EMU;
        b.rot = num(xfrm, "rot", 0) / 60000.0;
        b.flipH = flag(xfrm, "flipH", false);
        b.flipV = flag(xfrm, "flipV", false);
        return b;
    }

    private static String place(Box b) {
        final StringBuilder s = new StringBuilder("left:").append(fmt(b.x)).append("px;top:").append(fmt(b.y))
                .append("px;width:").append(fmt(b.w)).append("px;height:").append(fmt(b.h)).append("px;");
        if (Math.abs(b.rot) > 0.01) {
            s.append("transform:rotate(").append(fmt(b.rot)).append("deg);");
        }
        return s.toString();
    }

    /** Finds the placeholder in a layout or master tree that a slide placeholder inherits from */
    private static Element match(Element root, Element ph, boolean byType) {
        final Element tree = path(root, "p:cSld", "p:spTree");
        if (tree == null || ph == null) {
            return null;
        }
        final String idx = attr(ph, "idx");
        final String type = attr(ph, "type");
        Element byTypeHit = null;
        for (Element sp : children(tree, "p:sp")) {
            final Element other = path(sp, "p:nvSpPr", "p:nvPr", "p:ph");
            if (other == null) {
                continue;
            }
            if (!byType && idx != null && idx.equals(attr(other, "idx"))) {
                return sp;
            }
            final String t = attr(other, "type");
            if (byTypeHit == null && sameType(type, t)) {
                byTypeHit = sp;
            }
        }
        return byTypeHit;
    }

    private static boolean sameType(String a, String b) {
        a = a == null ? "body" : "ctrTitle".equals(a) ? "title" : a;
        b = b == null ? "body" : "ctrTitle".equals(b) ? "title" : b;
        return a.equals(b);
    }

    private static Element spPr(Element sp) {
        if (sp == null) {
            return null;
        }
        final Element p = child(sp, "p:spPr");
        return p != null ? p : child(sp, "dsp:spPr");
    }

    private static Element firstWith(Element[] sps, String... names) {
        for (Element sp : sps) {
            final Element hit = path(spPr(sp), names);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    // ---- lines ----

    private void applyLine(Line line, Element ln, OoxmlTheme theme) {
        if (ln == null) {
            return;
        }
        if (ln.hasAttribute("w")) {
            line.width = num(ln, "w", 12700) / EMU;
        }
        for (Element c : children(ln, null)) {
            switch (c.getNodeName()) {
                case "a:noFill":
                    line.none = true;
                    break;
                case "a:solidFill": {
                    final String color = theme.color(c);
                    if (color != null) {
                        line.color = color;
                        line.none = false;
                    }
                    break;
                }
                case "a:gradFill": {
                    final String color = theme.color(child(child(c, "a:gsLst"), "a:gs"));
                    if (color != null) {
                        line.color = color;
                        line.none = false;
                    }
                    break;
                }
                case "a:prstDash":
                    line.dash = attr(c, "val");
                    break;
                case "a:headEnd":
                    line.head = !"none".equals(attr(c, "type")) && attr(c, "type") != null;
                    break;
                case "a:tailEnd":
                    line.tail = !"none".equals(attr(c, "type")) && attr(c, "type") != null;
                    break;
                default:
                    break;
            }
        }
    }

    private Line line(Element[] sps, Element style, OoxmlTheme theme) {
        final Line line = new Line();
        if (style != null) {
            final Element ref = child(style, "a:lnRef");
            final int idx = (int) num(ref, "idx", 0);
            if (idx > 0) {
                theme.phColor = theme.color(ref);
                applyLine(line, theme.style("a:lnStyleLst", idx), theme);
                theme.phColor = null;
                if (line.color == null) {
                    line.color = theme.color(ref);
                }
            } else {
                line.none = true;
            }
        } else {
            line.none = true;
        }
        for (int i = sps.length - 1; i >= 0; i--) {
            applyLine(line, path(spPr(sps[i]), "a:ln"), theme);
        }
        return line;
    }

    private static String dashStyle(String dash) {
        if (dash == null || dash.equals("solid")) {
            return "solid";
        }
        return dash.startsWith("sysDot") || dash.equals("dot") || dash.equals("lgDot") ? "dotted" : "dashed";
    }

    private static String dashArray(String dash, double w) {
        final double u = Math.max(w, 1);
        switch (dash == null ? "solid" : dash) {
            case "solid":
                return null;
            case "dot":
            case "sysDot":
                return fmt(u) + "," + fmt(u * 2);
            case "sysDash":
                return fmt(u * 3) + "," + fmt(u);
            case "lgDash":
                return fmt(u * 8) + "," + fmt(u * 3);
            case "dashDot":
            case "sysDashDot":
                return fmt(u * 4) + "," + fmt(u * 3) + "," + fmt(u) + "," + fmt(u * 3);
            default:
                return fmt(u * 4) + "," + fmt(u * 3);
        }
    }

    private String stroke(Line line) {
        final double w = Math.max(line.width < 0 ? 1 : line.width, 0.75);
        final String dash = dashArray(line.dash, w);
        return "stroke=\"" + line.color + "\" stroke-width=\"" + fmt(w) + "\" fill=\"none\"" +
                (dash == null ? "" : " stroke-dasharray=\"" + dash + "\"") + " stroke-linejoin=\"round\"";
    }

    private static boolean visible(Line line) {
        return !line.none && line.color != null;
    }

    // ---- shapes ----

    private void shape(Element sp, String part, Ctx ctx, Tx tx, boolean connector, StringBuilder out) {
        final Element ph = path(sp, "p:nvSpPr", "p:nvPr", "p:ph");
        final Element layoutSp = ph == null || ctx.layout == null || part.equals(ctx.layoutPart) ? null
                : match(ctx.layout, ph, false);
        final Element masterSp = ph == null || ctx.master == null || part.equals(ctx.masterPart) ? null
                : match(ctx.master, layoutSp != null ? path(layoutSp, "p:nvSpPr", "p:nvPr", "p:ph") : ph, true);
        final Element[] sps = {sp, layoutSp, masterSp};
        final OoxmlTheme theme = ctx.theme;

        Box box = box(firstWith(sps, "a:xfrm"), tx);
        if (box == null) {
            return;
        }
        final Element style = child(sp, "p:style") != null ? child(sp, "p:style") : child(sp, "dsp:style");
        final Element txBody = child(sp, "p:txBody") != null ? child(sp, "p:txBody") : child(sp, "dsp:txBody");
        final boolean hasText = txBody != null && !OoxmlPackage.text(txBody).trim().isEmpty();
        if (ph != null && !hasText && firstWith(new Element[]{sp}, "a:solidFill") == null
                && firstWith(new Element[]{sp}, "a:blipFill") == null
                && firstWith(new Element[]{sp}, "a:gradFill") == null) {
            // An empty placeholder only shows its prompt text while editing
            return;
        }

        String fill = null;
        for (Element s : sps) {
            fill = theme.fill(spPr(s), pkg, part);
            if (fill != null) {
                break;
            }
        }
        if (fill == null && style != null) {
            final Element ref = child(style, "a:fillRef");
            final int idx = (int) num(ref, "idx", 0);
            if (idx > 0) {
                theme.phColor = theme.color(ref);
                final Element item = idx >= 1001 ? theme.style("a:bgFillStyleLst", idx - 1000)
                        : theme.style("a:fillStyleLst", idx);
                fill = item == null ? theme.phColor == null ? null : "background:" + theme.phColor + ";"
                        : theme.fillElement(item, pkg, part);
                theme.phColor = null;
            }
        }
        final Line line = line(sps, style, theme);

        Element geom = firstWith(sps, "a:prstGeom");
        final Element cust = geom == null ? firstWith(sps, "a:custGeom") : null;
        final String prst = geom == null ? (cust == null ? "rect" : null) : attr(geom, "prst");

        final boolean isLine = connector || (prst != null && (prst.equals("line") || prst.equals("straightConnector1")
                || prst.startsWith("bentConnector") || prst.startsWith("curvedConnector")));
        final StringBuilder css = new StringBuilder(place(box));
        final StringBuilder inner = new StringBuilder();

        if (isLine) {
            if (!visible(line)) {
                return;
            }
            out.append("<div class=o style=\"").append(css).append("\">");
            connectorSvg(out, prst, box, line, geom);
            out.append("</div>");
            return;
        }

        final double ss = Math.min(box.w, box.h);
        String shapePath = null;
        double[][] polygon = null;
        if (cust != null) {
            shapePath = custom(cust, box);
        } else if (prst != null) {
            switch (prst) {
                case "rect":
                case "flowChartProcess":
                case "snip1Rect":
                case "snip2SameRect":
                case "round1Rect":
                case "round2SameRect":
                case "wedgeRectCallout":
                case "frame":
                    break;
                case "roundRect":
                case "wedgeRoundRectCallout":
                case "flowChartAlternateProcess": {
                    final double adj = adjust(geom, "adj", 16667);
                    css.append("border-radius:").append(fmt(ss * Math.min(adj, 50000) / 100000)).append("px;");
                    break;
                }
                case "flowChartTerminator":
                    css.append("border-radius:").append(fmt(ss / 2)).append("px;");
                    break;
                case "ellipse":
                case "flowChartConnector":
                case "flowChartOr":
                case "flowChartSummingJunction":
                    css.append("border-radius:50%;");
                    break;
                default:
                    polygon = preset(prst, geom, box);
                    break;
            }
        }
        if (fill != null) {
            css.append(fill);
        }
        final boolean outline = visible(line);
        final double lw = Math.max(line.width < 0 ? 1 : line.width, 0.75);
        if (outline && polygon == null && shapePath == null) {
            css.append("border:").append(fmt(lw)).append("px ").append(dashStyle(line.dash)).append(' ')
                    .append(line.color).append(';');
        }
        if (polygon != null) {
            css.append("clip-path:polygon(").append(points(polygon, box, true)).append(");");
        } else if (shapePath != null) {
            css.append("clip-path:path('").append(shapePath).append("');");
        } else {
            final String shadow = shadow(sps, style, theme);
            if (shadow != null && fill != null && !fill.equals("background:none;")) {
                css.append("box-shadow:").append(shadow).append(';');
            }
        }

        // Text sits above the outline, which is drawn separately for outlines that follow a clip path
        out.append("<div class=o style=\"").append(css).append("\">");
        if ((polygon != null || shapePath != null) && outline) {
            out.append("<svg style=\"position:absolute;left:0;top:0;overflow:visible\" width=\"").append(fmt(box.w))
                    .append("\" height=\"").append(fmt(box.h)).append("\">");
            if (polygon != null) {
                out.append("<polygon points=\"").append(points(polygon, box, false)).append("\" ")
                        .append(stroke(line)).append("/>");
            } else {
                out.append("<path d=\"").append(shapePath).append("\" ").append(stroke(line)).append("/>");
            }
            out.append("</svg>");
        }
        if (hasText) {
            text(sp, txBody, ph, sps, style, ctx, part, box, out);
        }
        out.append("</div>");
        inner.setLength(0);
    }

    private String shadow(Element[] sps, Element style, OoxmlTheme theme) {
        Element shadow = firstWith(sps, "a:effectLst", "a:outerShdw");
        if (shadow == null && style != null) {
            final Element ref = child(style, "a:effectRef");
            final int idx = (int) num(ref, "idx", 0);
            final Element item = idx > 0 ? theme.style("a:effectStyleLst", idx) : null;
            if (item != null) {
                theme.phColor = theme.color(ref);
                final String css = shadowCss(path(item, "a:effectLst", "a:outerShdw"), theme);
                theme.phColor = null;
                return css;
            }
        }
        return shadowCss(shadow, theme);
    }

    private static String shadowCss(Element shadow, OoxmlTheme theme) {
        if (shadow == null) {
            return null;
        }
        final String color = theme.color(shadow);
        if (color == null) {
            return null;
        }
        final double dist = num(shadow, "dist", 0) / EMU;
        final double dir = Math.toRadians(num(shadow, "dir", 0) / 60000.0);
        final double blur = num(shadow, "blurRad", 0) / EMU;
        return fmt(dist * Math.cos(dir)) + "px " + fmt(dist * Math.sin(dir)) + "px " + fmt(blur) + "px " + color;
    }

    private static double adjust(Element geom, String name, double fallback) {
        for (Element gd : children(child(geom, "a:avLst"), "a:gd")) {
            if (name.equals(attr(gd, "name"))) {
                final String f = attr(gd, "fmla");
                if (f != null && f.startsWith("val ")) {
                    try {
                        return Double.parseDouble(f.substring(4).trim());
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                }
            }
        }
        return fallback;
    }

    private static String points(double[][] pts, Box box, boolean css) {
        final StringBuilder s = new StringBuilder();
        for (double[] p : pts) {
            double x = box.flipH ? box.w - p[0] : p[0];
            double y = box.flipV ? box.h - p[1] : p[1];
            if (s.length() > 0) {
                s.append(css ? "," : " ");
            }
            if (css) {
                s.append(fmt(x)).append("px ").append(fmt(y)).append("px");
            } else {
                s.append(fmt(x)).append(',').append(fmt(y));
            }
        }
        return s.toString();
    }

    /** The outline of the preset shapes that are straight-edged, in pixels of the box; null if unknown */
    private static double[][] preset(String name, Element geom, Box b) {
        final double w = b.w, h = b.h, ss = Math.min(w, h);
        switch (name) {
            case "triangle":
            case "flowChartExtract": {
                final double x = w * adjust(geom, "adj", 50000) / 100000;
                return new double[][]{{x, 0}, {w, h}, {0, h}};
            }
            case "rtTriangle":
                return new double[][]{{0, 0}, {w, h}, {0, h}};
            case "diamond":
            case "flowChartDecision":
                return new double[][]{{w / 2, 0}, {w, h / 2}, {w / 2, h}, {0, h / 2}};
            case "parallelogram":
            case "flowChartInputOutput": {
                final double x = ss * adjust(geom, "adj", 25000) / 100000;
                return new double[][]{{x, 0}, {w, 0}, {w - x, h}, {0, h}};
            }
            case "trapezoid": {
                final double x = ss * adjust(geom, "adj", 25000) / 100000;
                return new double[][]{{x, 0}, {w - x, 0}, {w, h}, {0, h}};
            }
            case "pentagon":
                return new double[][]{{w / 2, 0}, {w, h * 0.38}, {w * 0.81, h}, {w * 0.19, h}, {0, h * 0.38}};
            case "hexagon": {
                final double x = ss * adjust(geom, "adj", 25000) / 100000;
                return new double[][]{{x, 0}, {w - x, 0}, {w, h / 2}, {w - x, h}, {x, h}, {0, h / 2}};
            }
            case "octagon": {
                final double x = ss * adjust(geom, "adj", 29289) / 100000;
                return new double[][]{{x, 0}, {w - x, 0}, {w, x}, {w, h - x}, {w - x, h}, {x, h}, {0, h - x}, {0, x}};
            }
            case "plus":
            case "mathPlus": {
                final double x = ss * adjust(geom, "adj", 25000) / 100000;
                return new double[][]{{x, 0}, {w - x, 0}, {w - x, x}, {w, x}, {w, h - x}, {w - x, h - x},
                        {w - x, h}, {x, h}, {x, h - x}, {0, h - x}, {0, x}, {x, x}};
            }
            case "rightArrow":
            case "leftArrow": {
                final double y1 = h * (1 - adjust(geom, "adj1", 50000) / 100000) / 2;
                final double x = ss * adjust(geom, "adj2", 50000) / 100000;
                final double[][] r = {{0, y1}, {w - x, y1}, {w - x, 0}, {w, h / 2}, {w - x, h}, {w - x, h - y1},
                        {0, h - y1}};
                if (name.equals("leftArrow")) {
                    for (double[] p : r) {
                        p[0] = w - p[0];
                    }
                }
                return r;
            }
            case "upArrow":
            case "downArrow": {
                final double x1 = w * (1 - adjust(geom, "adj1", 50000) / 100000) / 2;
                final double y = ss * adjust(geom, "adj2", 50000) / 100000;
                final double[][] r = {{x1, h}, {x1, y}, {0, y}, {w / 2, 0}, {w, y}, {w - x1, y}, {w - x1, h}};
                if (name.equals("downArrow")) {
                    for (double[] p : r) {
                        p[1] = h - p[1];
                    }
                }
                return r;
            }
            case "leftRightArrow": {
                final double y1 = h * (1 - adjust(geom, "adj1", 50000) / 100000) / 2;
                final double x = ss * adjust(geom, "adj2", 50000) / 100000;
                return new double[][]{{0, h / 2}, {x, 0}, {x, y1}, {w - x, y1}, {w - x, 0}, {w, h / 2}, {w - x, h},
                        {w - x, h - y1}, {x, h - y1}, {x, h}};
            }
            case "chevron": {
                final double x = ss * adjust(geom, "adj", 50000) / 100000;
                return new double[][]{{0, 0}, {w - x, 0}, {w, h / 2}, {w - x, h}, {0, h}, {x, h / 2}};
            }
            case "homePlate": {
                final double x = w - ss * adjust(geom, "adj", 50000) / 100000;
                return new double[][]{{0, 0}, {x, 0}, {w, h / 2}, {x, h}, {0, h}};
            }
            case "star4":
                return star(4, 0.4, w, h);
            case "star5":
                return star(5, 0.382, w, h);
            case "star6":
                return star(6, 0.58, w, h);
            case "star8":
                return star(8, 0.7, w, h);
            case "star10":
                return star(10, 0.8, w, h);
            case "star12":
                return star(12, 0.85, w, h);
            default:
                return null;
        }
    }

    private static double[][] star(int points, double inner, double w, double h) {
        final double[][] pts = new double[points * 2][];
        for (int i = 0; i < points * 2; i++) {
            final double a = -Math.PI / 2 + Math.PI * i / points;
            final double r = i % 2 == 0 ? 0.5 : 0.5 * inner;
            pts[i] = new double[]{w * (0.5 + r * Math.cos(a)), h * (0.5 + r * Math.sin(a))};
        }
        return pts;
    }

    /** Turns a custom geometry into one SVG path in pixels of the box */
    private static String custom(Element cust, Box box) {
        final StringBuilder d = new StringBuilder();
        for (Element p : children(child(cust, "a:pathLst"), "a:path")) {
            final double pw = num(p, "w", 0), ph = num(p, "h", 0);
            final double sx = pw > 0 ? box.w / pw : box.w * EMU / Math.max(1, 1) / EMU;
            final double sy = ph > 0 ? box.h / ph : sx;
            final double kx = pw > 0 ? sx : 1.0 / EMU, ky = ph > 0 ? sy : 1.0 / EMU;
            double cx = 0, cy = 0;
            for (Element c : children(p, null)) {
                final List<Element> pts = children(c, "a:pt");
                switch (c.getNodeName()) {
                    case "a:moveTo":
                        if (!pts.isEmpty()) {
                            cx = num(pts.get(0), "x", 0) * kx;
                            cy = num(pts.get(0), "y", 0) * ky;
                            d.append('M').append(fmt(cx)).append(' ').append(fmt(cy));
                        }
                        break;
                    case "a:lnTo":
                        if (!pts.isEmpty()) {
                            cx = num(pts.get(0), "x", 0) * kx;
                            cy = num(pts.get(0), "y", 0) * ky;
                            d.append('L').append(fmt(cx)).append(' ').append(fmt(cy));
                        }
                        break;
                    case "a:cubicBezTo":
                        if (pts.size() >= 3) {
                            d.append('C');
                            for (int i = 0; i < 3; i++) {
                                d.append(fmt(num(pts.get(i), "x", 0) * kx)).append(' ')
                                        .append(fmt(num(pts.get(i), "y", 0) * ky)).append(' ');
                            }
                            cx = num(pts.get(2), "x", 0) * kx;
                            cy = num(pts.get(2), "y", 0) * ky;
                        }
                        break;
                    case "a:quadBezTo":
                        if (pts.size() >= 2) {
                            d.append('Q');
                            for (int i = 0; i < 2; i++) {
                                d.append(fmt(num(pts.get(i), "x", 0) * kx)).append(' ')
                                        .append(fmt(num(pts.get(i), "y", 0) * ky)).append(' ');
                            }
                            cx = num(pts.get(1), "x", 0) * kx;
                            cy = num(pts.get(1), "y", 0) * ky;
                        }
                        break;
                    case "a:arcTo": {
                        final double wr = num(c, "wR", 0) * kx, hr = num(c, "hR", 0) * ky;
                        final double st = Math.toRadians(num(c, "stAng", 0) / 60000.0);
                        final double sw = num(c, "swAng", 0) / 60000.0;
                        if (wr <= 0 || hr <= 0) {
                            break;
                        }
                        final double t0 = Math.atan2(wr * Math.sin(st), hr * Math.cos(st));
                        final double t1 = Math.atan2(wr * Math.sin(st + Math.toRadians(sw)),
                                hr * Math.cos(st + Math.toRadians(sw)));
                        final double ox = cx - wr * Math.cos(t0), oy = cy - hr * Math.sin(t0);
                        cx = ox + wr * Math.cos(t1);
                        cy = oy + hr * Math.sin(t1);
                        d.append('A').append(fmt(wr)).append(' ').append(fmt(hr)).append(" 0 ")
                                .append(Math.abs(sw) > 180 ? 1 : 0).append(' ').append(sw > 0 ? 1 : 0).append(' ')
                                .append(fmt(cx)).append(' ').append(fmt(cy));
                        break;
                    }
                    case "a:close":
                        d.append('Z');
                        break;
                    default:
                        break;
                }
            }
        }
        return d.length() == 0 ? null : d.toString();
    }

    private void connectorSvg(StringBuilder out, String prst, Box box, Line line, Element geom) {
        final double w = Math.max(box.w, 0.01), h = Math.max(box.h, 0.01);
        final double x0 = box.flipH ? w : 0, x1 = box.flipH ? 0 : w;
        final double y0 = box.flipV ? h : 0, y1 = box.flipV ? 0 : h;
        final String d;
        if (prst != null && prst.startsWith("bentConnector")) {
            final double mid = adjust(geom, "adj1", 50000) / 100000;
            final double mx = x0 + (x1 - x0) * mid;
            d = "M" + fmt(x0) + " " + fmt(y0) + "H" + fmt(mx) + "V" + fmt(y1) + "H" + fmt(x1);
        } else if (prst != null && prst.startsWith("curvedConnector")) {
            d = "M" + fmt(x0) + " " + fmt(y0) + "C" + fmt((x0 + x1) / 2) + " " + fmt(y0) + " " + fmt((x0 + x1) / 2)
                    + " " + fmt(y1) + " " + fmt(x1) + " " + fmt(y1);
        } else {
            d = "M" + fmt(x0) + " " + fmt(y0) + "L" + fmt(x1) + " " + fmt(y1);
        }
        final double lw = Math.max(line.width < 0 ? 1 : line.width, 0.75);
        final String id = "m" + (++markers);
        out.append("<svg style=\"position:absolute;left:0;top:0;overflow:visible\" width=\"").append(fmt(w))
                .append("\" height=\"").append(fmt(h)).append("\">");
        if (line.head || line.tail) {
            final double size = Math.max(lw * 3.5, 8);
            out.append("<defs><marker id=\"").append(id).append("\" markerUnits=\"userSpaceOnUse\" markerWidth=\"")
                    .append(fmt(size)).append("\" markerHeight=\"").append(fmt(size)).append("\" refX=\"")
                    .append(fmt(size)).append("\" refY=\"").append(fmt(size / 2)).append("\" orient=\"auto-start-reverse\">")
                    .append("<path d=\"M0,0L").append(fmt(size)).append(',').append(fmt(size / 2)).append("L0,")
                    .append(fmt(size)).append("z\" fill=\"").append(line.color).append("\"/></marker></defs>");
        }
        out.append("<path d=\"").append(d).append("\" ").append(stroke(line));
        if (line.head) {
            out.append(" marker-start=\"url(#").append(id).append(")\"");
        }
        if (line.tail) {
            out.append(" marker-end=\"url(#").append(id).append(")\"");
        }
        out.append("/></svg>");
    }

    // ---- pictures ----

    private void picture(Element pic, String part, Ctx ctx, Tx tx, StringBuilder out) {
        final Box box = box(path(pic, "p:spPr", "a:xfrm"), tx);
        final Element blipFill = child(pic, "p:blipFill");
        final String uri = pkg.picture(pkg.target(part, attr(child(blipFill, "a:blip"), "r:embed")));
        if (box == null || uri == null) {
            return;
        }
        final Element src = child(blipFill, "a:srcRect");
        final double l = num(src, "l", 0) / 100000.0, t = num(src, "t", 0) / 100000.0;
        final double r = num(src, "r", 0) / 100000.0, b = num(src, "b", 0) / 100000.0;
        final double kw = 1 - l - r, kh = 1 - t - b;
        final StringBuilder css = new StringBuilder(place(box)).append("overflow:hidden;");
        final String prst = attr(path(pic, "p:spPr", "a:prstGeom"), "prst");
        if ("ellipse".equals(prst)) {
            css.append("border-radius:50%;");
        } else if ("roundRect".equals(prst)) {
            css.append("border-radius:").append(fmt(Math.min(box.w, box.h)
                    * adjust(path(pic, "p:spPr", "a:prstGeom"), "adj", 16667) / 100000)).append("px;");
        }
        final Line line = line(new Element[]{pic}, null, ctx.theme);
        if (visible(line)) {
            css.append("border:").append(fmt(Math.max(line.width, 0.75))).append("px ").append(dashStyle(line.dash))
                    .append(' ').append(line.color).append(';');
        }
        final String shadow = shadowCss(path(pic, "p:spPr", "a:effectLst", "a:outerShdw"), ctx.theme);
        if (shadow != null) {
            css.append("box-shadow:").append(shadow).append(';');
        }
        out.append("<div class=o style=\"").append(css).append("\"><img src=\"").append(uri)
                .append("\" style=\"position:absolute;width:").append(fmt(box.w / Math.max(kw, 0.01)))
                .append("px;height:").append(fmt(box.h / Math.max(kh, 0.01))).append("px;left:")
                .append(fmt(-l / Math.max(kw, 0.01) * box.w)).append("px;top:")
                .append(fmt(-t / Math.max(kh, 0.01) * box.h)).append("px\"></div>");
    }

    // ---- graphic frames: tables, charts, diagrams ----

    private void frame(Element gf, String part, Ctx ctx, Tx tx, StringBuilder out) {
        final Element xfrm = child(gf, "p:xfrm");
        final Box box = box(xfrm, tx);
        final Element data = path(gf, "a:graphic", "a:graphicData");
        if (box == null || data == null) {
            return;
        }
        final Element tbl = child(data, "a:tbl");
        if (tbl != null) {
            table(tbl, part, ctx, box, tx, out);
            return;
        }
        final Element chart = child(data, "c:chart");
        if (chart != null) {
            final String chartPart = pkg.target(part, attr(chart, "r:id"));
            out.append("<div class=o style=\"").append(place(box)).append("\">");
            PptxChart.draw(pkg.xml(chartPart), ctx.theme, box.w, box.h, out);
            out.append("</div>");
            return;
        }
        final Element rel = child(data, "dgm:relIds");
        if (rel != null) {
            final String dataPart = pkg.target(part, attr(rel, "r:dm"));
            final Element model = dataPart == null ? null : pkg.xml(dataPart);
            final Element ext = model == null ? null : find(model, "dsp:dataModelExt");
            final String drawing = ext == null ? null : pkg.target(part, attr(ext, "relId"));
            final Element root = drawing == null ? null : pkg.xml(drawing);
            if (root != null) {
                // Shapes of the drawing sit relative to the frame
                final Tx inner = new Tx(tx.sx, tx.sy, tx.x(num(child(xfrm, "a:off"), "x", 0)),
                        tx.y(num(child(xfrm, "a:off"), "y", 0)));
                tree(child(root, "dsp:spTree"), drawing, ctx, inner, false, out);
            }
        }
    }

    private static Element find(Element root, String name) {
        if (name.equals(root.getNodeName())) {
            return root;
        }
        for (Element c : children(root, null)) {
            final Element hit = find(c, name);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private void table(Element tbl, String part, Ctx ctx, Box box, Tx tx, StringBuilder out) {
        final OoxmlTheme theme = ctx.theme;
        final List<Double> cols = new ArrayList<>();
        double total = 0;
        for (Element g : children(child(tbl, "a:tblGrid"), "a:gridCol")) {
            final double w = num(g, "w", 0) * tx.sx / EMU;
            cols.add(w);
            total += w;
        }
        final Element props = child(tbl, "a:tblPr");
        final boolean styled = child(props, "a:tableStyleId") != null;
        final boolean firstRow = flag(props, "firstRow", false), banded = flag(props, "bandRow", false);
        final String accent = theme.color(syntheticScheme("accent1"));
        out.append("<table class=o style=\"").append(place(box)).append("width:").append(fmt(total))
                .append("px;border-collapse:collapse;table-layout:fixed\"><colgroup>");
        for (double w : cols) {
            out.append("<col style=\"width:").append(fmt(w)).append("px\">");
        }
        out.append("</colgroup>");
        int rowIndex = 0;
        final Map<Integer, Integer> spans = new HashMap<>();
        for (Element tr : children(tbl, "a:tr")) {
            out.append("<tr style=\"height:").append(fmt(num(tr, "h", 0) * tx.sy / EMU)).append("px\">");
            int col = 0;
            for (Element tc : children(tr, "a:tc")) {
                final int span = (int) num(tc, "gridSpan", 1);
                final boolean skip = flag(tc, "hMerge", false) || flag(tc, "vMerge", false);
                if (!skip) {
                    final Element pr = child(tc, "a:tcPr");
                    final StringBuilder css = new StringBuilder();
                    String fill = theme.fill(pr, pkg, part);
                    String color = null;
                    boolean bold = false;
                    if (fill == null && styled && accent != null) {
                        if (firstRow && rowIndex == 0) {
                            fill = "background:" + accent + ";";
                            color = "#ffffff";
                            bold = true;
                        } else if (banded) {
                            final int rgb = OoxmlTheme.parseCss(accent);
                            fill = "background:" + shade(rgb, rowIndex % 2 == 1 ? 0.8 : 0.9) + ";";
                        }
                    }
                    if (fill != null) {
                        css.append(fill);
                    }
                    final String[] sides = {"lnL", "lnR", "lnT", "lnB"};
                    final String[] names = {"left", "right", "top", "bottom"};
                    for (int i = 0; i < 4; i++) {
                        final Line ln = new Line();
                        applyLine(ln, child(pr, "a:" + sides[i]), theme);
                        if (visible(ln)) {
                            css.append("border-").append(names[i]).append(':').append(fmt(Math.max(ln.width, 0.75)))
                                    .append("px ").append(dashStyle(ln.dash)).append(' ').append(ln.color).append(';');
                        } else if (styled) {
                            css.append("border-").append(names[i]).append(":1px solid #fff;");
                        }
                    }
                    css.append("padding:").append(fmt(num(pr, "marT", 45720) / EMU)).append("px ")
                            .append(fmt(num(pr, "marR", 91440) / EMU)).append("px ")
                            .append(fmt(num(pr, "marB", 45720) / EMU)).append("px ")
                            .append(fmt(num(pr, "marL", 91440) / EMU)).append("px;");
                    final String anchor = attr(pr, "anchor");
                    css.append("vertical-align:").append("ctr".equals(anchor) ? "middle" : "b".equals(anchor) ? "bottom"
                            : "top").append(';');
                    out.append("<td");
                    if (span > 1) {
                        out.append(" colspan=").append(span);
                    }
                    final int rs = (int) num(tc, "rowSpan", 1);
                    if (rs > 1) {
                        out.append(" rowspan=").append(rs);
                    }
                    out.append(" style=\"").append(css).append("\">");
                    final List<Element> holders = new ArrayList<>();
                    holders.add(defaultText);
                    paragraphs(child(tc, "a:txBody"), holders, color, bold, 1, ctx, part, out);
                    out.append("</td>");
                }
                col += span;
            }
            out.append("</tr>");
            rowIndex++;
        }
        out.append("</table>");
    }

    private static String shade(int rgb, double toward) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            final double c = rgb >> shift & 255;
            out |= (int) Math.round(c * (1 - toward) + 255 * toward) << shift;
        }
        return String.format(Locale.US, "#%06x", out);
    }

    /** A schemeClr element with no modifiers, to look a theme colour up by name */
    private Element syntheticScheme(String name) {
        final Element holder = pkg.xml("ppt/presentation.xml").getOwnerDocument().createElement("a:solidFill");
        final Element c = pkg.xml("ppt/presentation.xml").getOwnerDocument().createElement("a:schemeClr");
        c.setAttribute("val", name);
        holder.appendChild(c);
        return holder;
    }

    // ---- text ----

    private void text(Element sp, Element txBody, Element ph, Element[] sps, Element style, Ctx ctx, String part,
                      Box box, StringBuilder out) {
        final OoxmlTheme theme = ctx.theme;
        // Lowest priority first: the deck default, the master's style for this kind of text, then the
        // master, layout and slide placeholders' own list styles
        final List<Element> holders = new ArrayList<>();
        holders.add(defaultText);
        if (ph != null && ctx.master != null) {
            final String type = attr(ph, "type");
            final Element styles = child(ctx.master, "p:txStyles");
            holders.add(child(styles, "title".equals(type) || "ctrTitle".equals(type) ? "p:titleStyle"
                    : type == null || "body".equals(type) || "subTitle".equals(type) || "obj".equals(type)
                    ? "p:bodyStyle" : "p:otherStyle"));
        }
        for (int i = sps.length - 1; i >= 0; i--) {
            if (sps[i] != null) {
                final Element body = child(sps[i], "p:txBody") != null ? child(sps[i], "p:txBody")
                        : child(sps[i], "dsp:txBody");
                holders.add(child(body, "a:lstStyle"));
            }
        }
        // bodyPr properties come from the slide, else the layout, else the master
        final Element[] pr = new Element[sps.length];
        for (int i = 0; i < sps.length; i++) {
            if (sps[i] != null) {
                final Element body = child(sps[i], "p:txBody") != null ? child(sps[i], "p:txBody")
                        : child(sps[i], "dsp:txBody");
                pr[i] = child(body, "a:bodyPr");
            }
        }
        final double lIns = bodyNum(pr, "lIns", 91440) / EMU, tIns = bodyNum(pr, "tIns", 45720) / EMU;
        final double rIns = bodyNum(pr, "rIns", 91440) / EMU, bIns = bodyNum(pr, "bIns", 45720) / EMU;
        final String anchor = bodyAttr(pr, "anchor");
        final String wrap = bodyAttr(pr, "wrap");
        final String vert = bodyAttr(pr, "vert");
        double scale = 1;
        final Element fit = child(pr[0], "a:normAutofit");
        if (fit != null && fit.hasAttribute("fontScale")) {
            scale = num(fit, "fontScale", 100000) / 100000.0;
        }
        String color = null;
        if (ph == null && style != null) {
            color = theme.color(child(style, "a:fontRef"));
        }
        out.append("<div class=t style=\"position:absolute;left:0;top:0;width:100%;height:100%;padding:")
                .append(fmt(tIns)).append("px ").append(fmt(rIns)).append("px ").append(fmt(bIns)).append("px ")
                .append(fmt(lIns)).append("px;justify-content:")
                .append("ctr".equals(anchor) ? "center" : "b".equals(anchor) ? "flex-end" : "flex-start");
        if ("none".equals(wrap)) {
            out.append(";white-space:nowrap");
        }
        if ("vert".equals(vert) || "eaVert".equals(vert)) {
            out.append(";writing-mode:vertical-rl");
        }
        out.append("\">");
        paragraphs(child(txBody(sps[0]), "a:p") == null ? null : txBody(sps[0]), holders, color, false, scale, ctx,
                part, out);
        out.append("</div>");
    }

    private static Element txBody(Element sp) {
        return child(sp, "p:txBody") != null ? child(sp, "p:txBody") : child(sp, "dsp:txBody");
    }

    private static double bodyNum(Element[] pr, String name, double fallback) {
        for (Element e : pr) {
            if (e != null && e.hasAttribute(name)) {
                return num(e, name, (long) fallback);
            }
        }
        return fallback;
    }

    private static String bodyAttr(Element[] pr, String name) {
        for (Element e : pr) {
            if (e != null && e.hasAttribute(name)) {
                return e.getAttribute(name);
            }
        }
        return null;
    }

    private static String last(List<Element> chain, String name) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            final String v = attr(chain.get(i), name);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Element lastChild(List<Element> chain, String... names) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            for (String name : names) {
                final Element e = child(chain.get(i), name);
                if (e != null) {
                    return e;
                }
            }
        }
        return null;
    }

    private static String bulletChar(String typeface, String ch) {
        if (typeface != null && typeface.toLowerCase(Locale.ROOT).startsWith("wingdings")) {
            switch (ch) {
                case "§":
                    return "▪";
                case "Ø":
                    return "➢";
                case "ü":
                    return "✓";
                case "v":
                    return "❖";
                case "l":
                    return "●";
                case "n":
                    return "■";
                case "q":
                    return "❑";
                default:
                    return "•";
            }
        }
        if (typeface != null && typeface.equalsIgnoreCase("Symbol")) {
            return "•";
        }
        return ch;
    }

    private static String numbered(String type, int n) {
        final String t = type == null ? "arabicPeriod" : type;
        final String body;
        if (t.startsWith("alphaLc")) {
            body = String.valueOf((char) ('a' + (n - 1) % 26));
        } else if (t.startsWith("alphaUc")) {
            body = String.valueOf((char) ('A' + (n - 1) % 26));
        } else if (t.startsWith("roman")) {
            final String[] r = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
            final int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
            final StringBuilder s = new StringBuilder();
            int left = n;
            for (int i = 0; i < v.length; i++) {
                while (left >= v[i]) {
                    s.append(r[i]);
                    left -= v[i];
                }
            }
            body = t.startsWith("romanLc") ? s.toString().toLowerCase(Locale.ROOT) : s.toString();
        } else {
            body = String.valueOf(n);
        }
        if (t.endsWith("ParenBoth")) {
            return "(" + body + ")";
        }
        return t.endsWith("ParenR") ? body + ")" : t.endsWith("Plain") ? body : body + ".";
    }

    /** Writes the paragraphs of a txBody, resolving each one's properties through the style holders */
    private void paragraphs(Element body, List<Element> holders, String boxColor, boolean boldAll, double scale,
                            Ctx ctx, String part, StringBuilder out) {
        if (body == null) {
            return;
        }
        final OoxmlTheme theme = ctx.theme;
        final int[] counters = new int[10];
        for (Element p : children(body, "a:p")) {
            final Element pPr = child(p, "a:pPr");
            final int level = (int) Math.max(0, Math.min(8, num(pPr, "lvl", 0)));
            final List<Element> chain = new ArrayList<>();
            for (Element h : holders) {
                final Element lvl = child(h, "a:lvl" + (level + 1) + "pPr");
                if (lvl != null) {
                    chain.add(lvl);
                }
            }
            final int own = chain.size();
            if (pPr != null) {
                chain.add(pPr);
            }
            final List<Element> defaults = new ArrayList<>();
            for (Element e : chain) {
                final Element d = child(e, "a:defRPr");
                if (d != null) {
                    defaults.add(d);
                }
            }
            final double marL = num(chainElement(chain, "marL"), "marL", 0) / EMU;
            final double indent = num(chainElement(chain, "indent"), "indent", 0) / EMU;
            final String algn = last(chain, "algn");
            final Element bullet = lastChild(chain, "a:buNone", "a:buChar", "a:buAutoNum", "a:buBlip");
            final boolean hasText = !OoxmlPackage.text(p).trim().isEmpty();
            String mark = null;
            if (bullet != null && hasText) {
                if ("a:buChar".equals(bullet.getNodeName())) {
                    mark = bulletChar(attr(lastChild(chain, "a:buFont"), "typeface"), attr(bullet, "char") == null
                            ? "•" : attr(bullet, "char"));
                    counters[level] = 0;
                } else if ("a:buAutoNum".equals(bullet.getNodeName())) {
                    counters[level] = counters[level] == 0 ? (int) num(bullet, "startAt", 1) : counters[level] + 1;
                    mark = numbered(attr(bullet, "type"), counters[level]);
                } else if ("a:buBlip".equals(bullet.getNodeName())) {
                    mark = "•";
                }
            } else if (hasText) {
                counters[level] = 0;
            }
            for (int deeper = level + 1; deeper < counters.length; deeper++) {
                counters[deeper] = 0;
            }

            // Line height and spacing
            final double base = baseSize(chain, defaults, p, scale);
            final StringBuilder css = new StringBuilder("margin:0;");
            if (marL != 0 || indent != 0) {
                css.append("padding-left:").append(fmt(Math.max(0, marL))).append("px;text-indent:")
                        .append(fmt(mark != null ? indent : indent)).append("px;");
            }
            if (algn != null) {
                css.append("text-align:").append("ctr".equals(algn) ? "center" : "r".equals(algn) ? "right"
                        : "just".equals(algn) ? "justify" : "left").append(';');
            }
            if ("1".equals(last(chain, "rtl"))) {
                css.append("direction:rtl;");
            }
            final Element lnSpc = lastChild(chain, "a:lnSpc");
            if (lnSpc != null) {
                final Element pct = child(lnSpc, "a:spcPct"), pts = child(lnSpc, "a:spcPts");
                if (pct != null) {
                    css.append("line-height:").append(fmt(num(pct, "val", 100000) / 100000.0 * 1.2)).append(';');
                } else if (pts != null) {
                    css.append("line-height:").append(fmt(num(pts, "val", 1200) / 100.0 * 4 / 3)).append("px;");
                }
            } else {
                css.append("line-height:1.2;");
            }
            css.append("margin-top:").append(fmt(spacing(lastChild(chain, "a:spcBef"), base))).append("px;")
                    .append("margin-bottom:").append(fmt(spacing(lastChild(chain, "a:spcAft"), base))).append("px;");
            out.append("<p style=\"").append(css).append("\">");
            if (mark != null) {
                final Element bc = lastChild(chain, "a:buClr");
                String markColor = bc == null ? null : theme.color(bc);
                final double pctSize = num(lastChild(chain, "a:buSzPct"), "val", 100000) / 100000.0;
                if (markColor == null) {
                    markColor = runColor(defaults, null, boxColor, theme);
                }
                out.append("<span style=\"display:inline-block;min-width:").append(fmt(Math.max(0, -indent)))
                        .append("px;text-indent:0;");
                if (markColor != null) {
                    out.append("color:").append(markColor).append(';');
                }
                if (pctSize != 1) {
                    out.append("font-size:").append(fmt(pctSize * 100)).append("%;");
                }
                out.append("\">").append(esc(mark)).append("</span>");
            }
            boolean any = false;
            for (Element r : children(p, null)) {
                switch (r.getNodeName()) {
                    case "a:r":
                    case "a:fld": {
                        final Element rPr = child(r, "a:rPr");
                        final String t = OoxmlPackage.text(child(r, "a:t") == null ? r : child(r, "a:t"));
                        run(out, defaults, rPr, t, boxColor, boldAll, scale, theme);
                        any = true;
                        break;
                    }
                    case "a:br":
                        out.append("<br>");
                        any = true;
                        break;
                    default:
                        break;
                }
            }
            if (!any) {
                final Element end = child(p, "a:endParaRPr");
                final double size = end != null && end.hasAttribute("sz") ? num(end, "sz", 1800) / 100.0 * 4 / 3 * scale
                        : base;
                out.append("<span style=\"font-size:").append(fmt(size)).append("px\">&nbsp;</span>");
            }
            out.append("</p>");
        }
    }

    private static Element chainElement(List<Element> chain, String attrName) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (chain.get(i).hasAttribute(attrName)) {
                return chain.get(i);
            }
        }
        return null;
    }

    private static double baseSize(List<Element> chain, List<Element> defaults, Element p, double scale) {
        String sz = last(defaults, "sz");
        for (Element r : children(p, "a:r")) {
            final String own = attr(child(r, "a:rPr"), "sz");
            if (own != null) {
                sz = own;
                break;
            }
        }
        double size = 1800;
        try {
            size = sz == null ? 1800 : Double.parseDouble(sz);
        } catch (NumberFormatException e) {
            size = 1800;
        }
        return size / 100.0 * 4 / 3 * scale;
    }

    private static double spacing(Element spc, double fontPx) {
        if (spc == null) {
            return 0;
        }
        final Element pts = child(spc, "a:spcPts"), pct = child(spc, "a:spcPct");
        if (pts != null) {
            return num(pts, "val", 0) / 100.0 * 4 / 3;
        }
        return pct == null ? 0 : num(pct, "val", 0) / 100000.0 * fontPx * 1.2;
    }

    private static String runColor(List<Element> defaults, Element rPr, String boxColor, OoxmlTheme theme) {
        Element fillEl = rPr == null ? null : child(rPr, "a:solidFill");
        if (fillEl != null) {
            final String c = theme.color(fillEl);
            if (c != null) {
                return c;
            }
        }
        if (boxColor != null) {
            return boxColor;
        }
        final Element d = lastChild(defaults, "a:solidFill");
        return d == null ? null : theme.color(d);
    }

    private void run(StringBuilder out, List<Element> defaults, Element rPr, String text, String boxColor,
                     boolean boldAll, double scale, OoxmlTheme theme) {
        final List<Element> chain = new ArrayList<>(defaults);
        if (rPr != null) {
            chain.add(rPr);
        }
        final StringBuilder css = new StringBuilder();
        final String sz = last(chain, "sz");
        final double size = (sz == null ? 1800 : Double.parseDouble(sz)) / 100.0 * 4 / 3 * scale;
        css.append("font-size:").append(fmt(size)).append("px;");
        if (boldAll || "1".equals(last(chain, "b"))) {
            css.append("font-weight:bold;");
        }
        if ("1".equals(last(chain, "i"))) {
            css.append("font-style:italic;");
        }
        final String u = last(chain, "u");
        final String strike = last(chain, "strike");
        final boolean link = rPr != null && child(rPr, "a:hlinkClick") != null;
        if ((u != null && !"none".equals(u)) || link || (strike != null && !"noStrike".equals(strike))) {
            css.append("text-decoration:").append(u != null && !"none".equals(u) || link ? "underline " : "")
                    .append(strike != null && !"noStrike".equals(strike) ? "line-through" : "").append(';');
        }
        final String base = last(chain, "baseline");
        if (base != null && !base.equals("0")) {
            css.append("position:relative;font-size:").append(fmt(size * 0.7)).append("px;top:")
                    .append(Integer.parseInt(base) > 0 ? "-" : "").append(fmt(size * 0.35)).append("px;");
        }
        final String cap = last(chain, "cap");
        if ("all".equals(cap)) {
            css.append("text-transform:uppercase;");
        } else if ("small".equals(cap)) {
            css.append("font-variant:small-caps;");
        }
        final String spc = last(chain, "spc");
        if (spc != null && !spc.equals("0")) {
            css.append("letter-spacing:").append(fmt(Double.parseDouble(spc) / 100.0 * 4 / 3)).append("px;");
        }
        String color = runColor(defaults, rPr, boxColor, theme);
        if (link) {
            final String hl = theme.colors.get("hlink");
            if (hl != null) {
                color = "#" + hl;
            }
        }
        if (color != null) {
            css.append("color:").append(color).append(';');
        }
        final Element latin = lastChild(chain, "a:latin");
        final String face = theme.font(attr(latin, "typeface"));
        if (face != null) {
            css.append("font-family:'").append(face.replace("'", "")).append("',")
                    .append(face.matches("(?i).*(times|georgia|cambria|garamond|serif|palatino|book).*") && !face.toLowerCase(Locale.ROOT).contains("sans")
                            ? "serif" : "sans-serif").append(';');
        }
        final Element hi = lastChild(chain, "a:highlight");
        if (hi != null && theme.color(hi) != null) {
            css.append("background:").append(theme.color(hi)).append(';');
        }
        out.append("<span style=\"").append(css).append("\">").append(esc(text).replace("\u000b", "<br>"))
                .append("</span>");
    }

    // ---- notes ----

    private String notes(String slidePart) {
        final String notesPart = pkg.targetOfType(slidePart, "notesSlide");
        final Element root = notesPart == null ? null : pkg.xml(notesPart);
        final Element tree = path(root, "p:cSld", "p:spTree");
        if (tree == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        for (Element sp : children(tree, "p:sp")) {
            final Element ph = path(sp, "p:nvSpPr", "p:nvPr", "p:ph");
            if (ph == null || !"body".equals(attr(ph, "type"))) {
                continue;
            }
            for (Element p : children(child(sp, "p:txBody"), "a:p")) {
                final String t = OoxmlPackage.text(p).trim();
                if (!t.isEmpty()) {
                    out.append("<p>").append(esc(t)).append("</p>");
                }
            }
        }
        return out.toString();
    }
}
