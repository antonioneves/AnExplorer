package dev.dworks.apps.anexplorer.misc;

import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.attr;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.child;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.children;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.esc;
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
 * Draws a .docx, .docm or .dotx as one long page: paragraphs and runs resolved through the style
 * chain and document defaults, numbering, tables with their styles, pictures, text boxes, charts,
 * headers, footers, footnotes and the first section's page size and margins.
 */
final class DocxPage {

    private static final String MAIN = "word/document.xml";
    private static final int MAX_NESTING = 24;

    private final OoxmlPackage pkg;
    private final OoxmlTheme theme;
    private final Map<String, Element> styles = new HashMap<>();
    private final Map<String, Element> abstracts = new HashMap<>();
    private final Map<String, Element> nums = new HashMap<>();
    private final Map<String, int[]> counters = new HashMap<>();
    private final Map<String, Element> notes = new HashMap<>();
    private final List<String> footnotes = new ArrayList<>();
    private Element docParagraph, docRun;
    private String defaultParagraph, defaultTable;
    /** Table style formatting that reaches the paragraphs and runs of the table being drawn */
    private final List<Element> tableParagraph = new ArrayList<>();
    private final List<Element> tableRun = new ArrayList<>();
    private int depth;

    private DocxPage(OoxmlPackage pkg) {
        this.pkg = pkg;
        final String themePart = pkg.targetOfType(MAIN, "theme");
        theme = new OoxmlTheme(themePart == null ? null : pkg.xml(themePart));
    }

    static String html(OoxmlPackage pkg) throws IOException {
        if (pkg.xml(MAIN) == null) {
            throw new IOException("Not a Word document");
        }
        return new DocxPage(pkg).render();
    }

    private String render() {
        loadStyles();
        loadNumbering();
        loadNotes();
        final Element body = child(pkg.xml(MAIN), "w:body");
        final Element sect = lastSection(body);
        final StringBuilder out = new StringBuilder(pageStyle(sect));
        reference(sect, "w:headerReference", "hd", out);
        final long cols = num(child(sect, "w:cols"), "w:num", 1);
        if (cols > 1 && cols <= 6) {
            final double gap = num(child(sect, "w:cols"), "w:space", 720) / 15;
            out.append("<div style=\"column-count:").append(cols).append(";column-gap:").append(fmt(gap)).append("px\">");
            blocks(body, out);
            out.append("</div>");
        } else {
            blocks(body, out);
        }
        for (int i = 0; i < footnotes.size(); i++) {
            if (i == 0) {
                out.append("<div class=fn>");
            }
            out.append(footnotes.get(i));
        }
        if (!footnotes.isEmpty()) {
            out.append("</div>");
        }
        reference(sect, "w:footerReference", "ft", out);
        return out.toString();
    }

    private static Element lastSection(Element body) {
        Element sect = child(body, "w:sectPr");
        if (sect == null) {
            final List<Element> paragraphs = children(body, "w:p");
            for (int i = paragraphs.size() - 1; i >= 0 && sect == null; i--) {
                sect = path(paragraphs.get(i), "w:pPr", "w:sectPr");
            }
        }
        return sect;
    }

    private String pageStyle(Element sect) {
        final Element size = child(sect, "w:pgSz"), mar = child(sect, "w:pgMar");
        final double width = num(size, "w:w", 12240);
        final double left = num(mar, "w:left", 1800), right = num(mar, "w:right", 1800);
        final double top = Math.abs(num(mar, "w:top", 1440)), bottom = Math.abs(num(mar, "w:bottom", 1440));
        if (width < 2880 || width - left - right < 1440) {
            return "";
        }
        return "<style>#page{box-sizing:border-box;max-width:100%;width:" + fmt(width / 15) + "px;padding:"
                + fmt(top / 15) + "px " + fmt(right / 15) + "px " + fmt(bottom / 15) + "px " + fmt(left / 15)
                + "px}</style>";
    }

    private void reference(Element sect, String kind, String css, StringBuilder out) {
        for (Element ref : children(sect, kind)) {
            if (!"default".equals(attr(ref, "w:type"))) {
                continue;
            }
            final String part = pkg.target(MAIN, attr(ref, "r:id"));
            final Element root = part == null ? null : pkg.xml(part);
            if (root != null) {
                final StringBuilder inner = new StringBuilder();
                blocks(root, inner);
                if (OoxmlPackage.text(root).trim().length() > 0 || inner.indexOf("<img") >= 0) {
                    out.append("<div class=").append(css).append('>').append(inner).append("</div>");
                }
            }
            return;
        }
    }

    // ---- styles and numbering ----

    private void loadStyles() {
        final Element root = pkg.xml("word/styles.xml");
        final Element defaults = child(root, "w:docDefaults");
        docParagraph = path(defaults, "w:pPrDefault", "w:pPr");
        docRun = path(defaults, "w:rPrDefault", "w:rPr");
        for (Element s : children(root, "w:style")) {
            final String id = attr(s, "w:styleId");
            if (id == null) {
                continue;
            }
            styles.put(id, s);
            if ("1".equals(attr(s, "w:default"))) {
                if ("paragraph".equals(attr(s, "w:type"))) {
                    defaultParagraph = id;
                } else if ("table".equals(attr(s, "w:type"))) {
                    defaultTable = id;
                }
            }
        }
    }

    private void loadNumbering() {
        final Element root = pkg.xml("word/numbering.xml");
        for (Element a : children(root, "w:abstractNum")) {
            abstracts.put(attr(a, "w:abstractNumId"), a);
        }
        for (Element n : children(root, "w:num")) {
            nums.put(attr(n, "w:numId"), n);
        }
    }

    private void loadNotes() {
        for (String part : new String[]{"word/footnotes.xml", "word/endnotes.xml"}) {
            for (Element n : children(pkg.xml(part), null)) {
                final String id = attr(n, "w:id");
                if (id != null && attr(n, "w:type") == null) {
                    notes.put(part.charAt(5) + id, n);
                }
            }
        }
    }

    /** The style and the styles it is based on, base first */
    private List<Element> styleChain(String id) {
        final List<Element> chain = new ArrayList<>();
        for (int i = 0; id != null && i < MAX_NESTING; i++) {
            final Element s = styles.get(id);
            if (s == null) {
                break;
            }
            chain.add(0, s);
            id = attr(child(s, "w:basedOn"), "w:val");
        }
        return chain;
    }

    private static List<Element> properties(List<Element> styleChain, String name) {
        final List<Element> out = new ArrayList<>();
        for (Element s : styleChain) {
            final Element p = child(s, name);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private static Element last(List<Element> chain, String name) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            final Element e = child(chain.get(i), name);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static String val(List<Element> chain, String name) {
        return attr(last(chain, name), "w:val");
    }

    private static boolean on(List<Element> chain, String name) {
        final Element e = last(chain, name);
        if (e == null) {
            return false;
        }
        final String v = attr(e, "w:val");
        return v == null || !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("none"));
    }

    private static void add(List<Element> chain, Element e) {
        if (e != null) {
            chain.add(e);
        }
    }

    // ---- colours and borders ----

    private String themeColor(String name) {
        if (name == null) {
            return null;
        }
        final String slot;
        switch (name) {
            case "text1":
            case "dark1":
                slot = "dk1";
                break;
            case "background1":
            case "light1":
                slot = "lt1";
                break;
            case "text2":
            case "dark2":
                slot = "dk2";
                break;
            case "background2":
            case "light2":
                slot = "lt2";
                break;
            case "hyperlink":
                slot = "hlink";
                break;
            case "followedHyperlink":
                slot = "folHlink";
                break;
            default:
                slot = name;
                break;
        }
        final String hex = theme.colors.get(slot);
        return hex == null ? null : "#" + hex;
    }

    /** A w:color or w:shd style colour: a hex value, or a theme colour with its tint or shade */
    private String color(Element e, String valueAttr) {
        if (e == null) {
            return null;
        }
        String c = null;
        final String named = attr(e, "w:themeColor") != null ? attr(e, "w:themeColor")
                : attr(e, "w:theme" + (valueAttr.equals("w:fill") ? "Fill" : "Color"));
        if (named != null) {
            c = themeColor(named);
            if (c != null) {
                final String tint = attr(e, "w:themeTint") != null ? attr(e, "w:themeTint")
                        : attr(e, "w:themeFillTint");
                final String shade = attr(e, "w:themeShade") != null ? attr(e, "w:themeShade")
                        : attr(e, "w:themeFillShade");
                final int rgb = OoxmlTheme.parseCss(c);
                c = String.format(Locale.US, "#%06x", tint != null ? mix(rgb, 255, hex(tint))
                        : shade != null ? mix(rgb, 0, hex(shade)) : rgb);
            }
        }
        if (c == null) {
            final String v = attr(e, valueAttr);
            if (v != null && v.matches("[0-9a-fA-F]{6}")) {
                c = "#" + v;
            }
        }
        return c;
    }

    private static double hex(String v) {
        try {
            return Integer.parseInt(v, 16) / 255.0;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int mix(int rgb, int toward, double keep) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            final double c = rgb >> shift & 255;
            out |= (int) Math.round(c * keep + toward * (1 - keep)) << shift;
        }
        return out;
    }

    private String shade(Element shd) {
        if (shd == null) {
            return null;
        }
        final String fill = attr(shd, "w:fill");
        if (fill == null || fill.equals("auto")) {
            return null;
        }
        return color(shd, "w:fill");
    }

    /** One border side as CSS, or null if it draws nothing */
    private String border(Element b) {
        if (b == null) {
            return null;
        }
        final String v = attr(b, "w:val");
        if (v == null || v.equals("nil") || v.equals("none")) {
            return null;
        }
        final double w = Math.max(1, num(b, "w:sz", 4) / 8.0 * 4 / 3);
        String c = color(b, "w:color");
        if (c == null) {
            c = "#000";
        }
        final String style = v.contains("dash") ? "dashed" : v.contains("dot") ? "dotted"
                : v.equals("double") ? "double" : "solid";
        return fmt(v.equals("double") ? Math.max(3, w * 3) : w) + "px " + style + " " + c;
    }

    // ---- block content ----

    private void blocks(Element parent, StringBuilder out) {
        if (parent == null || ++depth > MAX_NESTING) {
            depth--;
            return;
        }
        for (Element e : children(parent, null)) {
            switch (e.getNodeName()) {
                case "w:p":
                    paragraph(e, out);
                    break;
                case "w:tbl":
                    table(e, out);
                    break;
                case "w:sdt":
                    blocks(child(e, "w:sdtContent"), out);
                    break;
                case "w:ins":
                case "w:customXml":
                    blocks(e, out);
                    break;
                case "mc:AlternateContent": {
                    final Element pick = child(e, "mc:Choice") != null ? child(e, "mc:Choice") : child(e, "mc:Fallback");
                    blocks(pick, out);
                    break;
                }
                default:
                    break;
            }
        }
        depth--;
    }

    private static String numberFormat(String fmt, int n) {
        switch (fmt == null ? "decimal" : fmt) {
            case "lowerLetter":
                return String.valueOf((char) ('a' + (n - 1) % 26));
            case "upperLetter":
                return String.valueOf((char) ('A' + (n - 1) % 26));
            case "lowerRoman":
            case "upperRoman": {
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
                return fmt.equals("lowerRoman") ? s.toString().toLowerCase(Locale.ROOT) : s.toString();
            }
            case "decimalZero":
                return (n < 10 ? "0" : "") + n;
            case "none":
                return "";
            default:
                return String.valueOf(n);
        }
    }

    private static String bulletGlyph(String text) {
        if (text == null || text.isEmpty()) {
            return "•";
        }
        switch (text.charAt(0)) {
            case '':
            case '':
                return text.charAt(0) == '' ? "▪" : "•";
            case '':
                return "➢";
            case '':
                return "✓";
            case '':
                return "❖";
            case '':
                return "■";
            default:
                return text.charAt(0) >= '' ? "•" : text;
        }
    }

    /** The label of a numbered or bulleted paragraph, advancing the list's counters */
    private String label(Element lvl, String numId, int level) {
        final Element num = nums.get(numId);
        final String abstractId = attr(child(num, "w:abstractNumId"), "w:val");
        int[] c = counters.get(abstractId == null ? numId : abstractId);
        if (c == null) {
            c = new int[9];
            for (int i = 0; i < c.length; i++) {
                c[i] = -1;
            }
            counters.put(abstractId == null ? numId : abstractId, c);
        }
        final Element abs = abstracts.get(abstractId);
        final int start = (int) num(child(lvl, "w:start"), "w:val", 1);
        c[level] = c[level] < 0 ? start : c[level] + 1;
        for (int i = level + 1; i < c.length; i++) {
            c[i] = -1;
        }
        final String format = val(java.util.Collections.singletonList(lvl), "w:numFmt");
        final String text = val(java.util.Collections.singletonList(lvl), "w:lvlText");
        if ("bullet".equals(format)) {
            return bulletGlyph(text);
        }
        if (text == null) {
            return numberFormat(format, c[level]);
        }
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            final char ch = text.charAt(i);
            if (ch == '%' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))) {
                final int idx = text.charAt(++i) - '1';
                if (idx >= 0 && idx < c.length) {
                    final Element other = levelOf(abs, idx);
                    out.append(numberFormat(val(java.util.Collections.singletonList(other), "w:numFmt"),
                            c[idx] < 0 ? (int) num(child(other, "w:start"), "w:val", 1) : c[idx]));
                }
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static Element levelOf(Element abs, int level) {
        for (Element l : children(abs, "w:lvl")) {
            if (num(l, "w:ilvl", -1) == level) {
                return l;
            }
        }
        return null;
    }

    private void paragraph(Element p, StringBuilder out) {
        final Element direct = child(p, "w:pPr");
        String styleId = attr(child(direct, "w:pStyle"), "w:val");
        if (styleId == null || !styles.containsKey(styleId)) {
            styleId = defaultParagraph;
        }
        final List<Element> style = styleChain(styleId);
        final List<Element> pChain = new ArrayList<>();
        add(pChain, docParagraph);
        pChain.addAll(tableParagraph);
        pChain.addAll(properties(style, "w:pPr"));
        final List<Element> rChain = new ArrayList<>();
        add(rChain, docRun);
        rChain.addAll(tableRun);
        rChain.addAll(properties(style, "w:rPr"));

        // Numbering comes from the paragraph or its style; its indents sit between the two
        final List<Element> withDirect = new ArrayList<>(pChain);
        add(withDirect, direct);
        final Element numPr = last(withDirect, "w:numPr");
        final String numId = attr(child(numPr, "w:numId"), "w:val");
        String label = null;
        double labelWidth = 0;
        Element lvl = null;
        if (numId != null && !numId.equals("0") && nums.containsKey(numId)) {
            final int level = (int) Math.max(0, Math.min(8, num(child(numPr, "w:ilvl"), "w:val", 0)));
            final Element abs = abstracts.get(attr(child(nums.get(numId), "w:abstractNumId"), "w:val"));
            lvl = levelOf(abs, level);
            for (Element o : children(nums.get(numId), "w:lvlOverride")) {
                if (num(o, "w:ilvl", -1) == level && child(child(o, "w:lvl"), "w:start") != null) {
                    lvl = child(o, "w:lvl");
                }
            }
            if (lvl != null) {
                label = label(lvl, numId, level);
                add(pChain, child(lvl, "w:pPr"));
                final List<Element> lvlRun = new ArrayList<>();
                add(lvlRun, child(lvl, "w:rPr"));
                rChain.addAll(lvlRun);
            }
        }
        add(pChain, direct);

        final boolean bidi = on(pChain, "w:bidi");
        final StringBuilder css = new StringBuilder("margin:0;white-space:pre-wrap;");
        final String jc = val(pChain, "w:jc");
        if (jc != null) {
            css.append("text-align:").append(jc.equals("center") ? "center" : jc.equals("both")
                    || jc.equals("distribute") ? "justify" : jc.equals("right") || jc.equals("end")
                    ? (bidi ? "start" : "right") : (bidi ? "end" : "left")).append(';');
        }
        if (bidi) {
            css.append("direction:rtl;");
        }
        final Element ind = last(pChain, "w:ind");
        if (ind != null) {
            final double left = indent(pChain, "w:left", "w:start") / 15;
            final double right = indent(pChain, "w:right", "w:end") / 15;
            final double first = indent(pChain, "w:firstLine", null) / 15;
            final double hanging = indent(pChain, "w:hanging", null) / 15;
            css.append(bidi ? "padding-right:" : "padding-left:").append(fmt(Math.max(0, left))).append("px;")
                    .append(bidi ? "padding-left:" : "padding-right:").append(fmt(Math.max(0, right))).append("px;");
            if (hanging > 0) {
                css.append("text-indent:").append(fmt(-hanging)).append("px;");
                labelWidth = hanging;
            } else if (first != 0) {
                css.append("text-indent:").append(fmt(first)).append("px;");
            }
        }
        final Element spacing = last(pChain, "w:spacing");
        double lineScale = 1.2;
        if (spacing != null) {
            css.append("margin-top:").append(fmt(num(spacing, "w:before", 0) / 15.0)).append("px;margin-bottom:")
                    .append(fmt(num(spacing, "w:after", 0) / 15.0)).append("px;");
            final String line = attr(spacing, "w:line");
            if (line != null) {
                final double twips = num(spacing, "w:line", 240);
                final String rule = attr(spacing, "w:lineRule");
                if (rule == null || rule.equals("auto")) {
                    lineScale = twips / 240 * 1.2;
                    css.append("line-height:").append(fmt(lineScale)).append(';');
                } else if (rule.equals("exact")) {
                    css.append("line-height:").append(fmt(twips / 15)).append("px;");
                } else {
                    css.append("min-height:").append(fmt(twips / 15)).append("px;");
                }
            }
        }
        final Element shd = last(pChain, "w:shd");
        final String fill = shade(shd);
        if (fill != null) {
            css.append("background:").append(fill).append(';');
        }
        final Element borders = last(pChain, "w:pBdr");
        final String[] sides = {"top", "left", "bottom", "right"};
        for (String side : sides) {
            final String b = border(child(borders, "w:" + side));
            if (b != null) {
                css.append("border-").append(side).append(':').append(b).append(';');
                css.append("padding-").append(side).append(":2px;");
            }
        }
        css.append(runStyle(rChain, rChain, true));

        final boolean pageBreak = on(pChain, "w:pageBreakBefore");
        if (pageBreak) {
            out.append("<hr>");
        }
        out.append("<p style=\"").append(css).append("\">");
        if (label != null && !label.isEmpty()) {
            final String markStyle = runStyle(rChain, rChain, false);
            out.append("<span style=\"display:inline-block;text-indent:0;white-space:pre;min-width:")
                    .append(fmt(Math.max(labelWidth, 8))).append("px;").append(markStyle).append("\">")
                    .append(esc(label)).append("</span>");
        }
        final int before = out.length();
        inline(p, rChain, out);
        if (out.length() == before) {
            out.append("&nbsp;");
        }
        out.append("</p>");
        if (child(direct, "w:sectPr") != null && !"continuous".equals(attr(path(direct, "w:sectPr", "w:type"), "w:val"))) {
            out.append("<hr>");
        }
    }

    private static double indent(List<Element> chain, String name, String alias) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            final Element ind = child(chain.get(i), "w:ind");
            if (ind == null) {
                continue;
            }
            if (ind.hasAttribute(name)) {
                return num(ind, name, 0);
            }
            if (alias != null && ind.hasAttribute(alias)) {
                return num(ind, alias, 0);
            }
        }
        return 0;
    }

    // ---- runs ----

    /** The CSS of a run's properties; the paragraph's own look is written once on the paragraph */
    private String runStyle(List<Element> chain, List<Element> base, boolean paragraph) {
        final StringBuilder css = new StringBuilder();
        final String sz = val(chain, "w:sz");
        if (paragraph || (sz != null && !sz.equals(val(base, "w:sz")))) {
            css.append("font-size:").append(fmt((sz == null ? 20 : Double.parseDouble(sz)) / 2)).append("pt;");
        }
        final Element fonts = last(chain, "w:rFonts");
        String face = attr(fonts, "w:ascii");
        final String themed = attr(fonts, "w:asciiTheme");
        if (themed != null) {
            face = themed.startsWith("major") ? theme.major : theme.minor;
        }
        if (face == null && paragraph) {
            face = theme.minor;
        }
        if (face != null) {
            css.append("font-family:").append(OfficeFonts.family(face,
                    face.matches("(?i).*(times|georgia|cambria|garamond|palatino|book|serif).*")
                            && !face.toLowerCase(Locale.ROOT).contains("sans") ? "serif" : "sans-serif")).append(';');
        }
        if (on(chain, "w:b")) {
            css.append("font-weight:bold;");
        }
        if (on(chain, "w:i")) {
            css.append("font-style:italic;");
        }
        final boolean underline = on(chain, "w:u");
        final boolean strike = on(chain, "w:strike") || on(chain, "w:dstrike");
        if (underline || strike) {
            css.append("text-decoration:").append(underline ? "underline " : "").append(strike ? "line-through" : "")
                    .append(';');
        }
        if (on(chain, "w:caps")) {
            css.append("text-transform:uppercase;");
        } else if (on(chain, "w:smallCaps")) {
            css.append("font-variant:small-caps;");
        }
        final String align = val(chain, "w:vertAlign");
        if ("superscript".equals(align)) {
            css.append("vertical-align:super;font-size:").append("75%;");
        } else if ("subscript".equals(align)) {
            css.append("vertical-align:sub;font-size:75%;");
        }
        final String c = color(last(chain, "w:color"), "w:val");
        if (c != null) {
            css.append("color:").append(c).append(';');
        }
        final String hi = val(chain, "w:highlight");
        final String hiColor = highlight(hi);
        final String fill = shade(last(chain, "w:shd"));
        if (hiColor != null) {
            css.append("background:").append(hiColor).append(';');
        } else if (fill != null && !paragraph) {
            css.append("background:").append(fill).append(';');
        }
        final Element spacing = last(chain, "w:spacing");
        if (spacing != null && num(spacing, "w:val", 0) != 0) {
            css.append("letter-spacing:").append(fmt(num(spacing, "w:val", 0) / 15.0)).append("px;");
        }
        if (on(chain, "w:vanish")) {
            css.append("display:none;");
        }
        return css.toString();
    }

    private static String highlight(String name) {
        if (name == null) {
            return null;
        }
        switch (name) {
            case "yellow":
                return "#ffff00";
            case "green":
                return "#00ff00";
            case "cyan":
                return "#00ffff";
            case "magenta":
                return "#ff00ff";
            case "blue":
                return "#0000ff";
            case "red":
                return "#ff0000";
            case "darkBlue":
                return "#000080";
            case "darkCyan":
                return "#008080";
            case "darkGreen":
                return "#008000";
            case "darkMagenta":
                return "#800080";
            case "darkRed":
                return "#800000";
            case "darkYellow":
                return "#808000";
            case "darkGray":
                return "#808080";
            case "lightGray":
                return "#c0c0c0";
            case "black":
                return "#000000";
            case "white":
                return "#ffffff";
            default:
                return null;
        }
    }

    private void inline(Element container, List<Element> base, StringBuilder out) {
        for (Element e : children(container, null)) {
            switch (e.getNodeName()) {
                case "w:r":
                    run(e, base, out);
                    break;
                case "w:hyperlink": {
                    final String hl = theme.colors.get("hlink");
                    out.append("<a style=\"color:").append(hl == null ? "#0563c1" : "#" + hl)
                            .append(";text-decoration:underline\">");
                    final List<Element> link = base;
                    inline(e, link, out);
                    out.append("</a>");
                    break;
                }
                case "w:ins":
                    out.append("<ins style=\"color:#1a7f37;text-decoration:underline\">");
                    inline(e, base, out);
                    out.append("</ins>");
                    break;
                case "w:del":
                    out.append("<del style=\"color:#c0392b\">");
                    inline(e, base, out);
                    out.append("</del>");
                    break;
                case "w:smartTag":
                case "w:fldSimple":
                case "w:customXml":
                case "w:dir":
                case "w:bdo":
                    inline(e, base, out);
                    break;
                case "w:sdt":
                    inline(child(e, "w:sdtContent"), base, out);
                    break;
                default:
                    break;
            }
        }
    }

    private void run(Element r, List<Element> base, StringBuilder out) {
        final Element direct = child(r, "w:rPr");
        final List<Element> chain = new ArrayList<>(base);
        final String charStyle = attr(child(direct, "w:rStyle"), "w:val");
        for (Element s : styleChain(charStyle)) {
            add(chain, child(s, "w:rPr"));
        }
        add(chain, direct);
        final String css = runStyle(chain, base, false);
        final boolean styled = !css.isEmpty();
        if (styled) {
            out.append("<span style=\"").append(css).append("\">");
        }
        runContent(r, out);
        if (styled) {
            out.append("</span>");
        }
    }

    private void runContent(Element r, StringBuilder out) {
        for (Element e : children(r, null)) {
            switch (e.getNodeName()) {
                case "w:t":
                case "w:delText":
                    out.append(esc(OoxmlPackage.text(e)));
                    break;
                case "w:tab":
                    out.append("<span style=\"display:inline-block;width:48px\"></span>");
                    break;
                case "w:br":
                case "w:cr": {
                    final String type = attr(e, "w:type");
                    out.append("page".equals(type) ? "<hr>" : "<br>");
                    break;
                }
                case "w:noBreakHyphen":
                    out.append('‑');
                    break;
                case "w:sym": {
                    final String ch = attr(e, "w:char");
                    try {
                        out.append(ch == null ? "" : bulletGlyph(String.valueOf((char) Integer.parseInt(ch, 16))));
                    } catch (NumberFormatException x) {
                        // no glyph
                    }
                    break;
                }
                case "w:drawing":
                    drawing(e, out);
                    break;
                case "w:pict":
                    legacyPicture(e, out);
                    break;
                case "w:footnoteReference":
                case "w:endnoteReference":
                    note(e, out);
                    break;
                case "mc:AlternateContent": {
                    final Element pick = child(e, "mc:Choice") != null ? child(e, "mc:Choice") : child(e, "mc:Fallback");
                    if (pick != null) {
                        runContent(pick, out);
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }

    private void note(Element ref, StringBuilder out) {
        final boolean foot = "w:footnoteReference".equals(ref.getNodeName());
        final Element note = notes.get((foot ? "f" : "e") + attr(ref, "w:id"));
        final int n = footnotes.size() + 1;
        out.append("<sup>").append(n).append("</sup>");
        footnotes.add(null);
        final StringBuilder body = new StringBuilder("<p style=\"margin:0\"><sup>").append(n).append("</sup> ");
        final StringBuilder inner = new StringBuilder();
        blocks(note, inner);
        // The note's own paragraphs go inline after the number
        body.append(inner.toString().replaceAll("^<p[^>]*>", "")).append("</p>");
        footnotes.set(n - 1, body.toString());
    }

    // ---- pictures ----

    private static Element find(Element root, String name) {
        if (root == null) {
            return null;
        }
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

    private void drawing(Element d, StringBuilder out) {
        final Element holder = child(d, "wp:inline") != null ? child(d, "wp:inline") : child(d, "wp:anchor");
        if (holder == null) {
            return;
        }
        final boolean anchored = "wp:anchor".equals(holder.getNodeName());
        final Element extent = child(holder, "wp:extent");
        final double w = num(extent, "cx", 0) / 9525.0, h = num(extent, "cy", 0) / 9525.0;
        final StringBuilder box = new StringBuilder();
        if (anchored) {
            final Element hPos = child(holder, "wp:positionH");
            final String align = attr(child(hPos, "wp:align"), "w:val") != null ? OoxmlPackage.text(child(hPos, "wp:align"))
                    : null;
            final double offset = num(child(hPos, "wp:posOffset") == null ? null : child(hPos, "wp:posOffset"), "x", 0);
            final String off = child(hPos, "wp:posOffset") == null ? null : OoxmlPackage.text(child(hPos, "wp:posOffset"));
            double offsetPx = 0;
            try {
                offsetPx = off == null ? 0 : Double.parseDouble(off.trim()) / 9525.0;
            } catch (NumberFormatException e) {
                offsetPx = offset;
            }
            final String side = align == null ? (offsetPx > 300 ? "right" : "left") : align.trim();
            if (side.equals("center")) {
                box.append("display:block;margin:0 auto;");
            } else {
                box.append("float:").append(side.equals("right") ? "right" : "left").append(";margin:0 8px 4px;");
            }
        }
        final Element chart = find(holder, "c:chart");
        if (chart != null) {
            final String part = pkg.target(MAIN, attr(chart, "r:id"));
            out.append("<span style=\"display:inline-block;").append(box).append("width:").append(fmt(w))
                    .append("px;height:").append(fmt(h)).append("px\">");
            PptxChart.draw(pkg.xml(part), theme, w, h, out);
            out.append("</span>");
            return;
        }
        final Element blip = find(holder, "a:blip");
        final String uri = blip == null ? null : pkg.picture(pkg.target(MAIN, attr(blip, "r:embed")));
        if (uri != null) {
            final Element src = find(holder, "a:srcRect");
            final double l = num(src, "l", 0) / 100000.0, t = num(src, "t", 0) / 100000.0;
            final double rr = num(src, "r", 0) / 100000.0, b = num(src, "b", 0) / 100000.0;
            final double kw = Math.max(1 - l - rr, 0.01), kh = Math.max(1 - t - b, 0.01);
            out.append("<span style=\"display:inline-block;position:relative;overflow:hidden;vertical-align:bottom;")
                    .append(box).append("width:").append(fmt(w)).append("px;height:").append(fmt(h)).append("px\">")
                    .append("<img src=\"").append(uri).append("\" style=\"position:absolute;max-width:none;width:")
                    .append(fmt(w / kw)).append("px;height:").append(fmt(h / kh)).append("px;left:")
                    .append(fmt(-l / kw * w)).append("px;top:").append(fmt(-t / kh * h)).append("px\"></span>");
            return;
        }
        final Element text = find(holder, "w:txbxContent");
        if (text != null) {
            out.append("<span style=\"display:inline-block;vertical-align:top;box-sizing:border-box;")
                    .append(box).append("width:").append(fmt(Math.max(w, 40))).append("px;padding:4px;")
                    .append("white-space:normal;border:1px solid #999\">");
            blocks(text, out);
            out.append("</span>");
        }
    }

    /** An old-style picture: a VML image with its size in the style attribute */
    private void legacyPicture(Element pict, StringBuilder out) {
        final Element image = find(pict, "v:imagedata");
        final String uri = image == null ? null : pkg.picture(pkg.target(MAIN, attr(image, "r:id")));
        if (uri == null) {
            return;
        }
        final Element shape = find(pict, "v:shape");
        final String style = attr(shape, "style");
        String size = "";
        if (style != null) {
            for (String part : style.split(";")) {
                final String[] kv = part.split(":", 2);
                if (kv.length == 2 && (kv[0].trim().equals("width") || kv[0].trim().equals("height"))) {
                    size += kv[0].trim() + ":" + kv[1].trim().replace("pt", "pt") + ";";
                }
            }
        }
        out.append("<img src=\"").append(uri).append("\" style=\"").append(size).append("\">");
    }

    // ---- tables ----

    private void table(Element tbl, StringBuilder out) {
        final Element props = child(tbl, "w:tblPr");
        String styleId = attr(child(props, "w:tblStyle"), "w:val");
        if (styleId == null || !styles.containsKey(styleId)) {
            styleId = defaultTable;
        }
        final List<Element> style = styleChain(styleId);
        final Element look = child(props, "w:tblLook");
        final int mask = look != null && look.hasAttribute("w:val") ? hexInt(attr(look, "w:val")) : 0x04A0;
        final boolean firstRow = look != null && look.hasAttribute("w:firstRow") ? num(look, "w:firstRow", 0) == 1
                : (mask & 0x20) != 0;
        final boolean banded = look != null && look.hasAttribute("w:noHBand") ? num(look, "w:noHBand", 0) == 0
                : (mask & 0x200) == 0;

        final Map<String, Element> borders = new HashMap<>();
        for (Element s : style) {
            for (Element b : children(path(s, "w:tblPr", "w:tblBorders"), null)) {
                borders.put(b.getNodeName(), b);
            }
        }
        for (Element b : children(child(props, "w:tblBorders"), null)) {
            borders.put(b.getNodeName(), b);
        }
        double[] cellMar = {0, 108, 0, 108};
        final List<Element> margins = new ArrayList<>();
        for (Element s : style) {
            add(margins, path(s, "w:tblPr", "w:tblCellMar"));
        }
        add(margins, child(props, "w:tblCellMar"));
        final String[] marSides = {"top", "left", "bottom", "right"};
        for (int i = 0; i < 4; i++) {
            final Element side = lastSide(margins, marSides[i]);
            if (side != null) {
                cellMar[i] = num(side, "w:w", (long) cellMar[i]);
            }
        }

        final List<Double> grid = new ArrayList<>();
        double total = 0;
        for (Element g : children(child(tbl, "w:tblGrid"), "w:gridCol")) {
            final double w = num(g, "w:w", 0) / 15.0;
            grid.add(w);
            total += w;
        }
        final String jc = attr(child(props, "w:jc"), "w:val");
        out.append("<table style=\"border-collapse:collapse;table-layout:fixed;max-width:100%;margin:")
                .append("center".equals(jc) ? "0 auto" : "0").append(";width:").append(fmt(total)).append("px\">");
        out.append("<colgroup>");
        for (double w : grid) {
            out.append("<col style=\"width:").append(fmt(w)).append("px\">");
        }
        out.append("</colgroup>");

        final List<Element> rows = children(tbl, "w:tr");
        final List<List<Element>> cells = new ArrayList<>();
        final List<List<Integer>> columns = new ArrayList<>();
        for (Element tr : rows) {
            final List<Element> list = children(tr, "w:tc");
            final List<Integer> cols = new ArrayList<>();
            int col = 0;
            col += (int) num(path(tr, "w:trPr", "w:gridBefore"), "w:val", 0);
            for (Element tc : list) {
                cols.add(col);
                col += (int) num(path(tc, "w:tcPr", "w:gridSpan"), "w:val", 1);
            }
            cells.add(list);
            columns.add(cols);
        }

        final List<Element> savedP = new ArrayList<>(tableParagraph), savedR = new ArrayList<>(tableRun);
        for (int r = 0; r < rows.size(); r++) {
            out.append("<tr>");
            final boolean header = firstRow && r == 0;
            final int band = r - (firstRow ? 1 : 0);
            for (int c = 0; c < cells.get(r).size(); c++) {
                final Element tc = cells.get(r).get(c);
                final Element tcPr = child(tc, "w:tcPr");
                final Element vMerge = child(tcPr, "w:vMerge");
                if (vMerge != null && !"restart".equals(attr(vMerge, "w:val"))) {
                    continue;
                }
                int rowSpan = 1;
                if (vMerge != null) {
                    for (int k = r + 1; k < rows.size(); k++) {
                        final int at = columns.get(r).get(c);
                        boolean continued = false;
                        for (int j = 0; j < cells.get(k).size(); j++) {
                            final Element m = child(child(cells.get(k).get(j), "w:tcPr"), "w:vMerge");
                            if (columns.get(k).get(j) == at && m != null && !"restart".equals(attr(m, "w:val"))) {
                                continued = true;
                            }
                        }
                        if (!continued) {
                            break;
                        }
                        rowSpan++;
                    }
                }
                final int span = (int) num(child(tcPr, "w:gridSpan"), "w:val", 1);
                final int col = columns.get(r).get(c);
                final StringBuilder css = new StringBuilder("box-sizing:border-box;overflow:hidden;");

                // Conditional formatting of the table style, from the whole table down to the first row
                tableParagraph.clear();
                tableRun.clear();
                tableParagraph.addAll(savedP);
                tableRun.addAll(savedR);
                String fill = null;
                for (Element s : style) {
                    add(tableParagraph, path(s, "w:pPr"));
                    add(tableRun, path(s, "w:rPr"));
                    fill = firstNonNull(shade(child(child(s, "w:tcPr"), "w:shd")), fill);
                    for (Element cond : children(s, "w:tblStylePr")) {
                        final String type = attr(cond, "w:type");
                        final boolean hit = header && "firstRow".equals(type)
                                || banded && !header && band % 2 == 0 && "band1Horz".equals(type)
                                || banded && !header && band % 2 == 1 && "band2Horz".equals(type);
                        if (hit) {
                            add(tableRun, child(cond, "w:rPr"));
                            add(tableParagraph, child(cond, "w:pPr"));
                            fill = firstNonNull(shade(path(cond, "w:tcPr", "w:shd")), fill);
                        }
                    }
                }
                fill = firstNonNull(shade(child(tcPr, "w:shd")), fill);
                if (fill != null) {
                    css.append("background:").append(fill).append(';');
                }

                final String[] sides = {"top", "left", "bottom", "right"};
                for (String side : sides) {
                    Element b = child(child(tcPr, "w:tcBorders"), "w:" + side);
                    if (b == null) {
                        final boolean edge = side.equals("top") ? r == 0 : side.equals("bottom")
                                ? r == rows.size() - 1 && rowSpan == 1 : side.equals("left") ? col == 0
                                : col + span >= grid.size();
                        b = borders.get("w:" + (edge ? side : side.equals("top") || side.equals("bottom")
                                ? "insideH" : "insideV"));
                    }
                    final String css1 = border(b);
                    css.append("border-").append(side).append(':').append(css1 == null ? "0" : css1).append(';');
                }
                final Element mar = child(tcPr, "w:tcMar");
                final double[] pad = new double[4];
                for (int i = 0; i < 4; i++) {
                    pad[i] = num(child(mar, "w:" + marSides[i]), "w:w", (long) cellMar[i]) / 15.0;
                }
                css.append("padding:").append(fmt(pad[0])).append("px ").append(fmt(pad[3])).append("px ")
                        .append(fmt(pad[2])).append("px ").append(fmt(pad[1])).append("px;");
                final String align = attr(child(tcPr, "w:vAlign"), "w:val");
                css.append("vertical-align:").append("center".equals(align) ? "middle" : "bottom".equals(align)
                        ? "bottom" : "top").append(';');

                out.append("<td");
                if (span > 1) {
                    out.append(" colspan=").append(span);
                }
                if (rowSpan > 1) {
                    out.append(" rowspan=").append(rowSpan);
                }
                out.append(" style=\"").append(css).append("\">");
                final int before = out.length();
                blocks(tc, out);
                if (out.length() == before) {
                    out.append("&nbsp;");
                }
                out.append("</td>");
            }
            out.append("</tr>");
        }
        tableParagraph.clear();
        tableRun.clear();
        tableParagraph.addAll(savedP);
        tableRun.addAll(savedR);
        out.append("</table>");
    }

    private static Element lastSide(List<Element> margins, String side) {
        for (int i = margins.size() - 1; i >= 0; i--) {
            final Element e = child(margins.get(i), "w:" + side);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static int hexInt(String v) {
        try {
            return Integer.parseInt(v.trim(), 16);
        } catch (NumberFormatException e) {
            return 0x04A0;
        }
    }
}
