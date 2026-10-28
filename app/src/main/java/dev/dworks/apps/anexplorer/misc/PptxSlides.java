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
import org.w3c.dom.NodeList;

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
    /** Classes of the animated shapes of the slide being written, by shape id, and its number of clicks */
    private final Map<String, String> animated = new HashMap<>();
    /** Id of the shape being written, to find the builds of its paragraphs */
    private String shapeId;
    private int clicks;
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
            animations(slide);
            out.append("<div class=slide id=s").append(count).append(" data-steps=").append(clicks).append(transition(slide))
                    .append(" style=\"width:").append(fmt(width)).append("px;height:")
                    .append(fmt(height)).append("px;");
            try {
                slide(slide, out);
            } catch (RuntimeException e) {
                out.append("\">");
            }
            out.append("<span class=n>").append(count).append(" / ").append(slides.size()).append("</span></div>");
        }
        return out.append("</div>").toString();
    }

    /** @return the slide's entry transition as a class for the slideshow, such as " data-tr=push2", or nothing */
    private String transition(String part) {
        final Element root = pkg.xml(part);
        final org.w3c.dom.NodeList found = root == null ? null : root.getElementsByTagName("p:transition");
        if (found == null || found.getLength() == 0) {
            return "";
        }
        final Element tr = (Element) found.item(0);
        String kind = "fade", dir = "";
        for (Element e : children(tr, null)) {
            final String name = e.getNodeName();
            if (name.equals("mc:AlternateContent") || name.equals("p:sndAc")) {
                continue;
            }
            kind = name.substring(name.indexOf(':') + 1);
            dir = attr(e, "dir") == null ? "" : attr(e, "dir");
            break;
        }
        if (kind.equals("cut")) {
            return "";
        }
        // Direction is where the new slide travels: it enters from the opposite edge
        final String from = dir.equals("l") ? "2" : dir.equals("r") ? "8" : dir.equals("u") ? "4" : dir.equals("d") ? "1" : "2";
        switch (kind) {
            case "push":
            case "cover":
            case "pull":
                return " data-tr=push" + from;
            case "wipe":
                return " data-tr=wipe" + from;
            case "zoom":
                return " data-tr=zoom";
            default:
                return " data-tr=fade";
        }
    }

    /**
     * Reads the slide's click sequence: a shape that enters on click n gets class k(n), shown from that
     * click on; one that exits on click n gets class x(n), hidden from that click on
     */
    private void animations(String part) {
        animated.clear();
        clicks = 0;
        final Element timing = child(pkg.xml(part), "p:timing");
        if (timing == null) {
            return;
        }
        final NodeList nodes = timing.getElementsByTagName("p:cTn");
        for (int i = 0; i < nodes.getLength(); i++) {
            final Element main = (Element) nodes.item(i);
            if (!"mainSeq".equals(attr(main, "nodeType"))) {
                continue;
            }
            for (Element step : children(child(main, "p:childTnLst"), "p:par")) {
                final Element start = path(step, "p:cTn", "p:stCondLst", "p:cond");
                if (start == null || "indefinite".equals(attr(start, "delay"))) {
                    clicks++;
                }
                final NodeList effects = step.getElementsByTagName("p:cTn");
                for (int j = 0; j < effects.getLength(); j++) {
                    final String kind = attr((Element) effects.item(j), "presetClass");
                    if (!"entr".equals(kind) && !"exit".equals(kind)) {
                        continue;
                    }
                    // The effect's kind and direction, as a class: a10_0 fades, a2_4 flies in from the bottom
                    final String effect = "entr".equals(kind) ? " a" + attr((Element) effects.item(j), "presetID") + "_"
                            + attr((Element) effects.item(j), "presetSubtype") : "";
                    final NodeList targets = ((Element) effects.item(j)).getElementsByTagName("p:spTgt");
                    for (int k = 0; k < targets.getLength(); k++) {
                        // A paragraph build targets one paragraph of the shape, kept as "id:index"
                        final Element range = path((Element) targets.item(k), "p:txEl", "p:pRg");
                        final String shape = attr((Element) targets.item(k), "spid");
                        final String id = range == null ? shape : shape + ":" + num(range, "st", 0);
                        final String mark = ("entr".equals(kind) ? "k" : "x") + clicks;
                        final String had = animated.get(id);
                        if (id != null && clicks > 0 && (had == null || !had.contains(mark.substring(0, 1)))) {
                            animated.put(id, had == null ? mark + effect : had + " " + mark + effect);
                        }
                        for (long q = range == null ? 0 : num(range, "st", 0) + 1; range != null && q <= num(range, "end", 0)
                                && q < 200 && clicks > 0; q++) {
                            animated.putIfAbsent(shape + ":" + q, mark + effect);
                        }
                    }
                }
            }
        }
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
        final Element props = template ? null : path(e, "p:nvSpPr", "p:cNvPr") != null ? path(e, "p:nvSpPr", "p:cNvPr")
                : path(e, "p:nvPicPr", "p:cNvPr") != null ? path(e, "p:nvPicPr", "p:cNvPr")
                : path(e, "p:nvGrpSpPr", "p:cNvPr") != null ? path(e, "p:nvGrpSpPr", "p:cNvPr")
                : path(e, "p:nvGraphicFramePr", "p:cNvPr") != null ? path(e, "p:nvGraphicFramePr", "p:cNvPr")
                : path(e, "p:nvCxnSpPr", "p:cNvPr");
        shapeId = props == null ? null : attr(props, "id");
        final String marks = shapeId == null ? null : animated.get(shapeId);
        if (marks != null) {
            out.append("<div class=\"").append(marks).append("\" style=\"display:contents\">");
            plain(e, part, ctx, tx, template, out);
            out.append("</div>");
        } else {
            plain(e, part, ctx, tx, template, out);
        }
    }

    private void plain(Element e, String part, Ctx ctx, Tx tx, boolean template, StringBuilder out) {
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
        String calloutPath = null, calloutFill = null;
        double[][] polygon = null;
        // A callout's pointer sticks out of its box, so it is drawn as a picture rather than by clipping the box
        if (prst != null && prst.startsWith("wedge") && prst.endsWith("Callout") && (fill == null || solid(fill) != null)) {
            calloutPath = callout(prst, geom, box);
            calloutFill = fill == null ? "none" : solid(fill);
            fill = null;
        }
        if (calloutPath != null) {
            // drawn below
        } else if (cust != null) {
            shapePath = custom(cust, box);
        } else if (prst != null) {
            switch (prst) {
                case "rect":
                case "flowChartProcess":
                case "wedgeRectCallout":
                    break;
                case "arc":
                    break;
                case "round1Rect": {
                    final double r = ss * Math.min(adjust(geom, "adj", 16667), 50000) / 100000;
                    css.append("border-radius:0 ").append(fmt(r)).append("px 0 0;");
                    break;
                }
                case "round2SameRect": {
                    final double top = ss * Math.min(adjust(geom, "adj1", 16667), 50000) / 100000;
                    final double bottom = ss * Math.min(adjust(geom, "adj2", 0), 50000) / 100000;
                    css.append("border-radius:").append(fmt(top)).append("px ").append(fmt(top)).append("px ")
                            .append(fmt(bottom)).append("px ").append(fmt(bottom)).append("px;");
                    break;
                }
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
                    if (polygon == null) {
                        shapePath = presetPath(prst, geom, box);
                    }
                    break;
            }
        }
        if (fill != null) {
            css.append(fill);
        }
        final boolean outline = visible(line);
        final double lw = Math.max(line.width < 0 ? 1 : line.width, 0.75);
        if (outline && polygon == null && shapePath == null && calloutPath == null && !"arc".equals(prst)) {
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
        if (calloutPath != null) {
            out.append("<svg style=\"position:absolute;left:0;top:0;overflow:visible\" width=\"").append(fmt(box.w))
                    .append("\" height=\"").append(fmt(box.h)).append("\"><path d=\"").append(calloutPath).append("\" ")
                    .append(outline ? stroke(line).replace("fill=\"none\"", "fill=\"" + calloutFill + "\"")
                            : "fill=\"" + calloutFill + "\"").append("/></svg>");
        } else if ("arc".equals(prst) && outline) {
            final double a0 = adjust(geom, "adj1", 16200000) / 60000.0, a1 = adjust(geom, "adj2", 0) / 60000.0;
            final double sweep = ((a1 - a0) % 360 + 360) % 360;
            out.append("<svg style=\"position:absolute;left:0;top:0;overflow:visible\" width=\"").append(fmt(box.w))
                    .append("\" height=\"").append(fmt(box.h)).append("\"><path d=\"M")
                    .append(arcPoint(a0, box.w / 2, box.h / 2, box.w, box.h)).append("A").append(fmt(box.w / 2)).append(' ')
                    .append(fmt(box.h / 2)).append(" 0 ").append(sweep > 180 ? 1 : 0).append(" 1 ")
                    .append(arcPoint(a1, box.w / 2, box.h / 2, box.w, box.h)).append("\" ").append(stroke(line)).append("/></svg>");
        }
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
            case "upDownArrow": {
                final double x1 = w * (1 - adjust(geom, "adj1", 50000) / 100000) / 2;
                final double y = ss * adjust(geom, "adj2", 50000) / 100000;
                return new double[][]{{w / 2, 0}, {w, y}, {w - x1, y}, {w - x1, h - y}, {w, h - y}, {w / 2, h},
                        {0, h - y}, {x1, h - y}, {x1, y}, {0, y}};
            }
            case "snip1Rect": {
                final double x = ss * adjust(geom, "adj", 16667) / 100000;
                return new double[][]{{0, 0}, {w - x, 0}, {w, x}, {w, h}, {0, h}};
            }
            case "snip2SameRect": {
                final double x = ss * adjust(geom, "adj1", 16667) / 100000;
                return new double[][]{{x, 0}, {w - x, 0}, {w, x}, {w, h}, {0, h}, {0, x}};
            }
            case "snip2DiagRect": {
                final double x = ss * adjust(geom, "adj1", 0) / 100000, y = ss * adjust(geom, "adj2", 16667) / 100000;
                return new double[][]{{x, 0}, {w - y, 0}, {w, y}, {w, h - x}, {w - x, h}, {y, h}, {0, h - y}, {0, x}};
            }
            case "snipRoundRect": {
                final double x = ss * adjust(geom, "adj2", 16667) / 100000;
                return new double[][]{{0, 0}, {w - x, 0}, {w, x}, {w, h}, {0, h}};
            }
            case "frame": {
                final double t = ss * adjust(geom, "adj1", 12500) / 100000;
                return new double[][]{{0, 0}, {w, 0}, {w, h}, {0, h}, {0, 0}, {t, t}, {t, h - t}, {w - t, h - t},
                        {w - t, t}, {t, t}};
            }
            case "mathMinus": {
                final double y = h * (1 - adjust(geom, "adj1", 23520) / 50000 * 0.5) / 2;
                return new double[][]{{0, y}, {w, y}, {w, h - y}, {0, h - y}};
            }
            case "mathMultiply": {
                final double u = ss * adjust(geom, "adj1", 23520) / 100000 / Math.sqrt(2);
                return new double[][]{{u, 0}, {w / 2, h / 2 - u}, {w - u, 0}, {w, u}, {w / 2 + u, h / 2},
                        {w, h - u}, {w - u, h}, {w / 2, h / 2 + u}, {u, h}, {0, h - u}, {w / 2 - u, h / 2}, {0, u}};
            }
            case "mathEqual": {
                final double bar = h * adjust(geom, "adj1", 23520) / 100000;
                final double gap = h * adjust(geom, "adj2", 11760) / 100000;
                final double y2 = h / 2 - gap, y3 = h / 2 + gap;
                // The two bars are joined along the left edge by a line with no width
                return new double[][]{{0, y2 - bar}, {w, y2 - bar}, {w, y2}, {0, y2}, {0, y3}, {w, y3},
                        {w, y3 + bar}, {0, y3 + bar}};
            }
            case "flowChartPreparation":
                return new double[][]{{w * 0.2, 0}, {w * 0.8, 0}, {w, h / 2}, {w * 0.8, h}, {w * 0.2, h}, {0, h / 2}};
            case "flowChartMerge":
                return new double[][]{{0, 0}, {w, 0}, {w / 2, h}};
            case "flowChartManualInput":
                return new double[][]{{0, h * 0.2}, {w, 0}, {w, h}, {0, h}};
            case "flowChartManualOperation":
                return new double[][]{{0, 0}, {w, 0}, {w * 0.8, h}, {w * 0.2, h}};
            case "flowChartOffpageConnector":
                return new double[][]{{0, 0}, {w, 0}, {w, h * 0.8}, {w / 2, h}, {0, h * 0.8}};
            case "flowChartCollate":
                return new double[][]{{0, 0}, {w, 0}, {w / 2, h / 2}, {w, h}, {0, h}, {w / 2, h / 2}};
            case "flowChartSort":
                return new double[][]{{w / 2, 0}, {w, h / 2}, {w / 2, h}, {0, h / 2}};
            case "lightningBolt":
                return new double[][]{{w * 0.38, 0}, {w * 0.62, h * 0.33}, {w * 0.5, h * 0.36}, {w, h * 0.7},
                        {w * 0.62, h * 0.66}, {w * 0.7, h}, {w * 0.2, h * 0.58}, {w * 0.38, h * 0.55}, {w * 0.1, h * 0.28},
                        {w * 0.3, h * 0.3}};
            case "rightArrowCallout": {
                final double x = ss * adjust(geom, "adj3", 25000) / 100000;
                final double body = w * adjust(geom, "adj4", 64977) / 100000;
                final double y1 = h * (1 - adjust(geom, "adj1", 25000) / 100000 * 2 > 0
                        ? adjust(geom, "adj1", 25000) / 100000 : 0.25);
                return new double[][]{{0, 0}, {body, 0}, {body, h / 2 - y1 * h / 2}, {w - x, h / 2 - y1 * h / 2},
                        {w - x, h * 0.25 - y1 * 0}, {w, h / 2}, {w - x, h * 0.75}, {w - x, h / 2 + y1 * h / 2},
                        {body, h / 2 + y1 * h / 2}, {body, h}, {0, h}};
            }
            case "star4":
                return star(4, 0.4, w, h);
            case "star16":
                return star(16, 0.88, w, h);
            case "star24":
                return star(24, 0.9, w, h);
            case "star32":
                return star(32, 0.93, w, h);
            case "star7":
                return star(7, 0.51, w, h);
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

    /** Curved presets as an SVG path in pixels of the box; null if unknown */
    private static String presetPath(String name, Element geom, Box b) {
        final double w = b.w, h = b.h;
        switch (name) {
            case "donut": {
                final double t = Math.min(w, h) * adjust(geom, "adj", 25000) / 100000;
                return ring(w, h, 0, 0, w, h, true) + ring(w, h, t, t, w - t, h - t, false);
            }
            case "heart":
                return "M" + fmt(w / 2) + ' ' + fmt(h * 0.3) + "C" + fmt(w * 0.5) + ' ' + fmt(h * 0.1) + ' ' + fmt(w * 0.35) + " 0 "
                        + fmt(w * 0.25) + " 0C" + fmt(w * 0.1) + " 0 0 " + fmt(h * 0.12) + " 0 " + fmt(h * 0.3) + "C0 "
                        + fmt(h * 0.6) + ' ' + fmt(w * 0.4) + ' ' + fmt(h * 0.8) + ' ' + fmt(w / 2) + ' ' + fmt(h)
                        + "C" + fmt(w * 0.6) + ' ' + fmt(h * 0.8) + ' ' + fmt(w) + ' ' + fmt(h * 0.6) + ' ' + fmt(w) + ' '
                        + fmt(h * 0.3) + "C" + fmt(w) + ' ' + fmt(h * 0.12) + ' ' + fmt(w * 0.9) + " 0 " + fmt(w * 0.75)
                        + " 0C" + fmt(w * 0.65) + " 0 " + fmt(w * 0.5) + ' ' + fmt(h * 0.1) + ' ' + fmt(w / 2) + ' '
                        + fmt(h * 0.3) + "Z";
            case "moon": {
                final double x = w * adjust(geom, "adj", 50000) / 100000;
                return "M" + fmt(w) + " 0A" + fmt(w) + ' ' + fmt(h / 2) + " 0 0 0 " + fmt(w) + ' ' + fmt(h) + "A"
                        + fmt(Math.max(w - x, 1)) + ' ' + fmt(h / 2) + " 0 0 1 " + fmt(w) + " 0Z";
            }
            case "cloud":
                return "M" + fmt(w * 0.2) + ' ' + fmt(h * 0.85) + "A" + fmt(w * 0.17) + ' ' + fmt(h * 0.25) + " 0 0 1 "
                        + fmt(w * 0.15) + ' ' + fmt(h * 0.45) + "A" + fmt(w * 0.2) + ' ' + fmt(h * 0.25) + " 0 0 1 "
                        + fmt(w * 0.4) + ' ' + fmt(h * 0.2) + "A" + fmt(w * 0.2) + ' ' + fmt(h * 0.2) + " 0 0 1 "
                        + fmt(w * 0.75) + ' ' + fmt(h * 0.2) + "A" + fmt(w * 0.2) + ' ' + fmt(h * 0.28) + " 0 0 1 "
                        + fmt(w * 0.88) + ' ' + fmt(h * 0.6) + "A" + fmt(w * 0.15) + ' ' + fmt(h * 0.25) + " 0 0 1 "
                        + fmt(w * 0.75) + ' ' + fmt(h * 0.85) + "Z";
            case "sun": {
                final double[][] pts = star(8, 0.55, w, h);
                final StringBuilder d = new StringBuilder();
                for (int i = 0; i < pts.length; i++) {
                    d.append(i == 0 ? 'M' : 'L').append(fmt(pts[i][0])).append(' ').append(fmt(pts[i][1]));
                }
                return d.append('Z').toString();
            }
            case "pie":
            case "chord": {
                final boolean pie = name.equals("pie");
                final double a0 = adjust(geom, "adj1", pie ? 0 : 2700000) / 60000.0;
                final double a1 = adjust(geom, "adj2", 16200000) / 60000.0;
                final double sweep = ((a1 - a0) % 360 + 360) % 360;
                final double cx = w / 2, cy = h / 2;
                final String head = pie ? "M" + fmt(cx) + ' ' + fmt(cy) + "L" : "M";
                return head + arcPoint(a0, cx, cy, w, h) + "A" + fmt(cx) + ' ' + fmt(cy) + " 0 " + (sweep > 180 ? 1 : 0)
                        + " 1 " + arcPoint(a1, cx, cy, w, h) + "Z";
            }
            case "blockArc": {
                final double a0 = adjust(geom, "adj1", 10800000) / 60000.0, a1 = adjust(geom, "adj2", 0) / 60000.0;
                final double t = Math.min(w, h) * adjust(geom, "adj3", 25000) / 100000;
                final double sweep = ((a1 - a0) % 360 + 360) % 360;
                final double cx = w / 2, cy = h / 2, ix = Math.max(cx - t, 1), iy = Math.max(cy - t, 1);
                final int big = sweep > 180 ? 1 : 0;
                return "M" + arcPoint(a0, cx, cy, w, h) + "A" + fmt(cx) + ' ' + fmt(cy) + " 0 " + big + " 1 "
                        + arcPoint(a1, cx, cy, w, h) + "L" + arcPoint(a1, cx, cy, 2 * ix, 2 * iy) + "A" + fmt(ix) + ' ' + fmt(iy)
                        + " 0 " + big + " 0 " + arcPoint(a0, cx, cy, 2 * ix, 2 * iy) + "Z";
            }
            case "mathDivide": {
                final double bar = h * adjust(geom, "adj1", 23520) / 100000;
                final double r = h * adjust(geom, "adj3", 11760) / 100000;
                final double offset = bar / 2 + h * adjust(geom, "adj2", 5880) / 100000 + r;
                return "M0 " + fmt(h / 2 - bar / 2) + "H" + fmt(w) + "V" + fmt(h / 2 + bar / 2) + "H0Z"
                        + ring(w, h, w / 2 - r, h / 2 - offset - r, w / 2 + r, h / 2 - offset + r, true)
                        + ring(w, h, w / 2 - r, h / 2 + offset - r, w / 2 + r, h / 2 + offset + r, true);
            }
            case "mathNotEqual": {
                final double bar = h * adjust(geom, "adj1", 23520) / 100000;
                final double gap = h * adjust(geom, "adj2", 6600000) / 100000 > 0 ? h * 0.1176 : h * 0.1176;
                final double y2 = h / 2 - gap, y3 = h / 2 + gap;
                final double shift = h / 2 * Math.tan(Math.toRadians(20)), t = bar / Math.cos(Math.toRadians(20));
                return "M0 " + fmt(y2 - bar) + "H" + fmt(w) + "V" + fmt(y2) + "H0Z"
                        + "M0 " + fmt(y3) + "H" + fmt(w) + "V" + fmt(y3 + bar) + "H0Z"
                        + "M" + fmt(w / 2 + shift - t / 2) + " 0H" + fmt(w / 2 + shift + t / 2) + "L"
                        + fmt(w / 2 - shift + t / 2) + ' ' + fmt(h) + "H" + fmt(w / 2 - shift - t / 2) + "Z";
            }
            case "bentArrow": {
                final double ss = Math.min(w, h);
                final double th = ss * adjust(geom, "adj1", 25000) / 100000;
                final double aw2 = ss * adjust(geom, "adj2", 25000) / 100000;
                final double ah = ss * adjust(geom, "adj3", 25000) / 100000;
                final double dh2 = aw2 - th / 2;
                final double bs = Math.min(w - ah, h - dh2);
                final double bd = Math.min(ss * adjust(geom, "adj4", 43750) / 100000, Math.max(bs, 0));
                final double bd2 = Math.max(bd - th, 0), y3 = dh2 + th, x4 = w - ah;
                return "M0 " + fmt(h) + "V" + fmt(dh2 + bd) + "A" + fmt(bd) + ' ' + fmt(bd) + " 0 0 1 " + fmt(bd) + ' '
                        + fmt(dh2) + "H" + fmt(x4) + "V0L" + fmt(w) + ' ' + fmt(aw2) + "L" + fmt(x4) + ' ' + fmt(y3 + dh2)
                        + "V" + fmt(y3) + "H" + fmt(th + bd2) + "A" + fmt(bd2) + ' ' + fmt(bd2) + " 0 0 0 " + fmt(th) + ' '
                        + fmt(y3 + bd2) + "V" + fmt(h) + "Z";
            }
            case "uturnArrow": {
                final double ss = Math.min(w, h);
                final double a2 = Math.min(adjust(geom, "adj2", 25000), 25000);
                final double a1 = Math.min(adjust(geom, "adj1", 25000), a2 * 2);
                final double th = ss * a1 / 100000, aw2 = ss * a2 / 100000, dh2 = aw2 - th / 2;
                final double a3 = Math.min(adjust(geom, "adj3", 25000), Math.max(0, (100000 - a1 * ss / h) * h / ss));
                final double ah = ss * a3 / 100000;
                final double a5 = Math.max(Math.min(100000, adjust(geom, "adj5", 75000)), (a3 + a1) * ss / h);
                final double y5 = h * a5 / 100000, y4 = y5 - ah, x9 = w - dh2;
                final double bd = Math.min(ss * adjust(geom, "adj4", 43750) / 100000, Math.max(0, Math.min(x9 / 2, y4)));
                final double bd2 = Math.max(bd - th, 0), x3 = th + bd2, x8 = w - aw2, x6 = x8 - aw2, x7 = x6 + dh2;
                final double x4 = x9 - bd, x5 = x7 - bd2;
                return "M0 " + fmt(h) + "V" + fmt(bd) + "A" + fmt(bd) + ' ' + fmt(bd) + " 0 0 1 " + fmt(bd) + " 0H" + fmt(x4)
                        + "A" + fmt(bd) + ' ' + fmt(bd) + " 0 0 1 " + fmt(x9) + ' ' + fmt(bd) + "V" + fmt(y4) + "H" + fmt(w)
                        + "L" + fmt(x8) + ' ' + fmt(y5) + "L" + fmt(x6) + ' ' + fmt(y4) + "H" + fmt(x7) + "V" + fmt(x3)
                        + "A" + fmt(bd2) + ' ' + fmt(bd2) + " 0 0 0 " + fmt(x5) + ' ' + fmt(th) + "H" + fmt(x3) + "A"
                        + fmt(bd2) + ' ' + fmt(bd2) + " 0 0 0 " + fmt(th) + ' ' + fmt(x3) + "V" + fmt(h) + "Z";
            }
            default:
                return null;
        }
    }

    /** @return the colour of a plain fill such as "background:#336699;", or null for gradients and pictures */
    private static String solid(String fill) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("^background:(#[0-9a-fA-F]{3,8}|rgba?\\([0-9 ,.%]+\\));$")
                .matcher(fill);
        return m.matches() ? m.group(1) : null;
    }

    /** The outline of a speech-bubble callout: its box with a pointer reaching to the point the adjustments name */
    private static String callout(String name, Element geom, Box b) {
        final double w = b.w, h = b.h;
        final double dx = w * adjust(geom, "adj1", -20833) / 100000, dy = h * adjust(geom, "adj2", 62500) / 100000;
        final double px = w / 2 + dx, py = h / 2 + dy;
        if (name.equals("wedgeEllipseCallout")) {
            final double theta = Math.atan2(dy / h, dx / w), d = 0.3;
            final double[][] base = {{w / 2 + w / 2 * Math.cos(theta - d), h / 2 + h / 2 * Math.sin(theta - d)},
                    {px, py}, {w / 2 + w / 2 * Math.cos(theta + d), h / 2 + h / 2 * Math.sin(theta + d)}};
            return ring(w, h, 0, 0, w, h, true) + "M" + fmt(base[0][0]) + ' ' + fmt(base[0][1]) + "L" + fmt(base[1][0]) + ' '
                    + fmt(base[1][1]) + "L" + fmt(base[2][0]) + ' ' + fmt(base[2][1]) + "Z";
        }
        final double r = name.equals("wedgeRoundRectCallout")
                ? Math.min(w, h) * Math.min(adjust(geom, "adj3", 16667), 50000) / 100000 : 0;
        final boolean vertical = Math.abs(dy) > Math.abs(dx * h / w);
        final boolean top = vertical && dy < 0, bottom = vertical && dy >= 0, left = !vertical && dx < 0,
                right = !vertical && dx >= 0;
        final double x1 = w * (dx > 0 ? 7 : 2) / 12, x2 = w * (dx > 0 ? 10 : 5) / 12;
        final double y1 = h * (dy > 0 ? 7 : 2) / 12, y2 = h * (dy > 0 ? 10 : 5) / 12;
        final String tip = fmt(px) + ' ' + fmt(py);
        final StringBuilder d = new StringBuilder("M").append(fmt(r)).append(" 0");
        if (top) {
            d.append("L").append(fmt(x1)).append(" 0L").append(tip).append("L").append(fmt(x2)).append(" 0");
        }
        d.append("H").append(fmt(w - r)).append("A").append(fmt(r)).append(' ').append(fmt(r)).append(" 0 0 1 ")
                .append(fmt(w)).append(' ').append(fmt(r));
        if (right) {
            d.append("L").append(fmt(w)).append(' ').append(fmt(y1)).append("L").append(tip).append("L").append(fmt(w))
                    .append(' ').append(fmt(y2));
        }
        d.append("V").append(fmt(h - r)).append("A").append(fmt(r)).append(' ').append(fmt(r)).append(" 0 0 1 ")
                .append(fmt(w - r)).append(' ').append(fmt(h));
        if (bottom) {
            d.append("L").append(fmt(x2)).append(' ').append(fmt(h)).append("L").append(tip).append("L").append(fmt(x1))
                    .append(' ').append(fmt(h));
        }
        d.append("H").append(fmt(r)).append("A").append(fmt(r)).append(' ').append(fmt(r)).append(" 0 0 1 0 ")
                .append(fmt(h - r));
        if (left) {
            d.append("L0 ").append(fmt(y2)).append("L").append(tip).append("L0 ").append(fmt(y1));
        }
        return d.append("V").append(fmt(r)).append("A").append(fmt(r)).append(' ').append(fmt(r)).append(" 0 0 1 ")
                .append(fmt(r)).append(" 0Z").toString();
    }

    /** A point on the ellipse of a w by h box centred at cx, cy, at an angle in degrees clockwise from the right */
    private static String arcPoint(double degrees, double cx, double cy, double w, double h) {
        final double a = Math.toRadians(degrees);
        return fmt(cx + w / 2 * Math.cos(a)) + ' ' + fmt(cy + h / 2 * Math.sin(a));
    }

    private static String ring(double w, double h, double x0, double y0, double x1, double y1, boolean clockwise) {
        final double rx = (x1 - x0) / 2, ry = (y1 - y0) / 2, cy = (y0 + y1) / 2;
        final String sweep = clockwise ? "1" : "0";
        return "M" + fmt(x0) + ' ' + fmt(cy) + "A" + fmt(rx) + ' ' + fmt(ry) + " 0 1 " + sweep + ' ' + fmt(x1) + ' ' + fmt(cy)
                + "A" + fmt(rx) + ' ' + fmt(ry) + " 0 1 " + sweep + ' ' + fmt(x0) + ' ' + fmt(cy) + "Z";
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
        // A straight line has no width or no height: the SVG still needs a size to be painted
        final double w = Math.max(box.w, 1), h = Math.max(box.h, 1);
        final double x0 = box.flipH ? box.w : 0, x1 = box.flipH ? 0 : box.w;
        final double y0 = box.flipV ? box.h : 0, y1 = box.flipV ? 0 : box.h;
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
        // A picture in a placeholder takes its place and shape from the layout and master
        final Element ph = path(pic, "p:nvPicPr", "p:nvPr", "p:ph");
        final Element layoutSp = ph == null || ctx.layout == null || part.equals(ctx.layoutPart) ? null
                : match(ctx.layout, ph, false);
        final Element masterSp = ph == null || ctx.master == null || part.equals(ctx.masterPart) ? null
                : match(ctx.master, layoutSp != null ? path(layoutSp, "p:nvSpPr", "p:nvPr", "p:ph") : ph, true);
        final Element[] sps = {pic, layoutSp, masterSp};
        final Box box = box(firstWith(sps, "a:xfrm"), tx);
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
        final Element geom = firstWith(sps, "a:prstGeom");
        final String prst = attr(geom, "prst");
        if ("ellipse".equals(prst)) {
            css.append("border-radius:50%;");
        } else if ("roundRect".equals(prst)) {
            css.append("border-radius:").append(fmt(Math.min(box.w, box.h)
                    * adjust(geom, "adj", 16667) / 100000)).append("px;");
        }
        final Element cust = geom == null ? firstWith(sps, "a:custGeom") : null;
        final String shapePath = cust == null ? null : custom(cust, box);
        if (shapePath != null) {
            css.append("clip-path:path('").append(shapePath).append("');");
        }
        final Element duotone = path(blipFill, "a:blip", "a:duotone");
        final String filter = duotone == null ? null : duotoneFilter(duotone, ctx.theme, out);
        if (filter != null) {
            css.append("filter:url(#").append(filter).append(");");
        }
        final Line line = line(new Element[]{pic}, null, ctx.theme);
        if (visible(line) && shapePath == null) {
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

    private int filters;

    /**
     * Writes an SVG filter that maps the picture's brightness from the duotone's first colour to its
     * second, as Office does, and returns its id; null if the colours can't be read
     */
    private String duotoneFilter(Element duotone, OoxmlTheme theme, StringBuilder out) {
        final List<Element> colors = children(duotone, null);
        if (colors.size() < 2) {
            return null;
        }
        final double[] dark = rgb(theme.colorElement(colors.get(0)));
        final double[] light = rgb(theme.colorElement(colors.get(1)));
        if (dark == null || light == null) {
            return null;
        }
        final String id = "dt" + filters++;
        out.append("<svg width=0 height=0 style=\"position:absolute\"><filter id=").append(id)
                .append(" color-interpolation-filters=sRGB><feColorMatrix type=matrix values=\"")
                .append("0.2126 0.7152 0.0722 0 0 0.2126 0.7152 0.0722 0 0 0.2126 0.7152 0.0722 0 0 0 0 0 1 0\"/>")
                .append("<feComponentTransfer>");
        final String[] channel = {"R", "G", "B"};
        for (int i = 0; i < 3; i++) {
            out.append("<feFunc").append(channel[i]).append(" type=table tableValues=\"")
                    .append(fmt(dark[i])).append(' ').append(fmt(light[i])).append("\"/>");
        }
        out.append("</feComponentTransfer></filter></svg>");
        return id;
    }

    /** @return the channels of a #rrggbb or rgb(r,g,b) CSS colour from 0 to 1, or null */
    private static double[] rgb(String css) {
        if (css == null) {
            return null;
        }
        try {
            if (css.startsWith("#") && css.length() >= 7) {
                return new double[]{Integer.parseInt(css.substring(1, 3), 16) / 255.0,
                        Integer.parseInt(css.substring(3, 5), 16) / 255.0,
                        Integer.parseInt(css.substring(5, 7), 16) / 255.0};
            }
            if (css.startsWith("rgb")) {
                final String[] v = css.substring(css.indexOf('(') + 1, css.indexOf(')')).split(",");
                return new double[]{Double.parseDouble(v[0].trim()) / 255.0,
                        Double.parseDouble(v[1].trim()) / 255.0, Double.parseDouble(v[2].trim()) / 255.0};
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
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
            } else if (model != null) {
                diagramNodes(model, ctx, box, out);
            }
        }
    }

    /** SmartArt with no saved drawing: its text nodes as equal boxes in a row, the closest honest picture */
    private void diagramNodes(Element model, Ctx ctx, Box box, StringBuilder out) {
        final List<String> texts = new ArrayList<>();
        for (Element pt : children(child(model, "dgm:ptLst"), "dgm:pt")) {
            final String type = attr(pt, "type");
            if (type == null || type.equals("node")) {
                final String t = OoxmlPackage.text(child(pt, "dgm:t")).trim();
                if (!t.isEmpty()) {
                    texts.add(t);
                }
            }
        }
        if (texts.isEmpty()) {
            return;
        }
        final String accent = ctx.theme.color(syntheticScheme("accent1"));
        final boolean wide = box.w >= box.h;
        final double gap = 8, cell = ((wide ? box.w : box.h) - gap * (texts.size() - 1)) / texts.size();
        for (int i = 0; i < texts.size(); i++) {
            final double off = i * (cell + gap);
            out.append("<div class=o style=\"").append(place(box)).append("\"><div class=o style=\"")
                    .append(wide ? "left:" + fmt(off) + "px;top:0;width:" + fmt(cell) + "px;height:" + fmt(box.h) + "px"
                            : "top:" + fmt(off) + "px;left:0;height:" + fmt(cell) + "px;width:" + fmt(box.w) + "px")
                    .append(";background:").append(accent == null ? "#4472c4" : accent)
                    .append(";color:#fff;display:flex;align-items:center;justify-content:center;text-align:center;")
                    .append("font-size:16px;padding:6px\">").append(esc(texts.get(i))).append("</div></div>");
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
        final boolean ofShape = body.getParentNode() != null && "p:sp".equals(body.getParentNode().getNodeName());
        int index = -1;
        for (Element p : children(body, "a:p")) {
            index++;
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
            final boolean rtl = "1".equals(last(chain, "rtl"));
            if (marL != 0 || indent != 0) {
                css.append(rtl ? "padding-right:" : "padding-left:").append(fmt(Math.max(0, marL))).append("px;text-indent:")
                        .append(fmt(mark != null ? indent : indent)).append("px;");
            }
            if (algn != null) {
                css.append("text-align:").append("ctr".equals(algn) ? "center" : "r".equals(algn) ? "right"
                        : "just".equals(algn) ? "justify" : "left").append(';');
            }
            if (rtl) {
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
            final String build = ofShape && shapeId != null ? animated.get(shapeId + ":" + index) : null;
            out.append(build == null ? "<p" : "<p class=\"" + build + "\"").append(" style=\"").append(css).append("\">");
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
            css.append("font-family:").append(OfficeFonts.family(face, face.matches("(?i).*(times|georgia|cambria|garamond|serif|palatino|book).*") && !face.toLowerCase(Locale.ROOT).contains("sans")
                            ? "serif" : "sans-serif")).append(';');
        }
        final Element hi = lastChild(chain, "a:highlight");
        if (hi != null && theme.color(hi) != null) {
            css.append("background:").append(theme.color(hi)).append(';');
        }
        out.append("<span style=\"").append(css).append("\">").append(esc(text).replace("\u000b", "<br>"))
                .append("</span>");
    }
}
