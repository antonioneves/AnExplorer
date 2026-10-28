package dev.dworks.apps.anexplorer.misc;

import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.attr;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.child;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.children;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.num;

import org.w3c.dom.Element;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A DrawingML theme: its colours, fonts and the fill, line and effect styles that shapes point
 * at, plus how colour elements (srgbClr, schemeClr and the rest, with their modifiers) turn into CSS.
 */
final class OoxmlTheme {

    private static final Map<String, Integer> PRESET = new HashMap<>();

    static {
        final String[] names = {"black", "000000", "white", "ffffff", "red", "ff0000", "green", "008000", "blue",
                "0000ff", "yellow", "ffff00", "gray", "808080", "grey", "808080", "silver", "c0c0c0", "orange",
                "ffa500", "purple", "800080", "cyan", "00ffff", "magenta", "ff00ff", "lime", "00ff00", "navy",
                "000080", "maroon", "800000", "olive", "808000", "teal", "008080", "brown", "a52a2a", "pink",
                "ffc0cb", "gold", "ffd700", "darkBlue", "00008b", "darkRed", "8b0000", "darkGreen", "006400",
                "darkGray", "a9a9a9", "lightGray", "d3d3d3", "lightBlue", "add8e6", "lightGreen", "90ee90",
                "ltGray", "d3d3d3", "dkGray", "a9a9a9", "dkBlue", "00008b", "dkRed", "8b0000", "dkGreen", "006400",
                "ltBlue", "add8e6", "ltGreen", "90ee90", "ltYellow", "ffffe0", "ltPink", "ffb6c1", "violet", "ee82ee",
                "indigo", "4b0082", "turquoise", "40e0d0", "tan", "d2b48c", "beige", "f5f5dc", "coral", "ff7f50"};
        for (int i = 0; i + 1 < names.length; i += 2) {
            PRESET.put(names[i], Integer.parseInt(names[i + 1], 16));
        }
    }

    final Map<String, String> colors = new HashMap<>();
    String major = "Calibri Light";
    String minor = "Calibri";
    Element formats;
    /** The colour a style's phClr stands for while a style is being resolved */
    String phColor;
    /** Maps bg1, tx1 and such to theme slots; from the slide master */
    Map<String, String> colorMap = defaultMap();

    static Map<String, String> defaultMap() {
        final Map<String, String> map = new HashMap<>();
        map.put("bg1", "lt1");
        map.put("tx1", "dk1");
        map.put("bg2", "lt2");
        map.put("tx2", "dk2");
        return map;
    }

    OoxmlTheme(Element root) {
        if (root == null) {
            colors.put("dk1", "000000");
            colors.put("lt1", "ffffff");
            return;
        }
        final Element elements = child(root, "a:themeElements");
        final Element scheme = child(elements, "a:clrScheme");
        for (Element slot : children(scheme, null)) {
            final String name = slot.getNodeName().substring(slot.getNodeName().indexOf(':') + 1);
            final Element c = firstChild(slot);
            if (c == null) {
                continue;
            }
            String v = attr(c, "val");
            if ("a:sysClr".equals(c.getNodeName())) {
                v = attr(c, "lastClr") != null ? attr(c, "lastClr") : "dk1".equals(name) ? "000000" : "ffffff";
            }
            if (v != null) {
                colors.put(name, v);
            }
        }
        final Element fonts = child(elements, "a:fontScheme");
        final String maj = attr(child(child(fonts, "a:majorFont"), "a:latin"), "typeface");
        final String min = attr(child(child(fonts, "a:minorFont"), "a:latin"), "typeface");
        if (maj != null && !maj.isEmpty()) {
            major = maj;
        }
        if (min != null && !min.isEmpty()) {
            minor = min;
        }
        formats = child(elements, "a:fmtScheme");
    }

    private static Element firstChild(Element e) {
        final java.util.List<Element> list = children(e, null);
        return list.isEmpty() ? null : list.get(0);
    }

    /** @return the face a:latin names, resolving +mj-lt and +mn-lt to the theme's fonts */
    String font(String typeface) {
        if (typeface == null || typeface.isEmpty()) {
            return null;
        }
        if (typeface.startsWith("+mj")) {
            return major;
        }
        if (typeface.startsWith("+mn")) {
            return minor;
        }
        return typeface;
    }

    /** @return the CSS colour of the first colour child of the element, or null if it has none */
    String color(Element holder) {
        if (holder == null) {
            return null;
        }
        for (Element c : children(holder, null)) {
            final String css = colorElement(c);
            if (css != null) {
                return css;
            }
        }
        return null;
    }

    /** @return the CSS colour of the element if it is a colour element, else null */
    String colorElement(Element c) {
        final String tag = c.getNodeName();
        int rgb;
        final String val = attr(c, "val");
        switch (tag) {
            case "a:srgbClr":
                try {
                    rgb = Integer.parseInt(val.trim(), 16);
                } catch (RuntimeException e) {
                    return null;
                }
                break;
            case "a:schemeClr": {
                if (val == null) {
                    return null;
                }
                if ("phClr".equals(val)) {
                    if (phColor == null) {
                        return null;
                    }
                    rgb = parseCss(phColor);
                    break;
                }
                String slot = colorMap.containsKey(val) ? colorMap.get(val) : val;
                if ("tx1".equals(slot)) {
                    slot = "dk1";
                } else if ("bg1".equals(slot)) {
                    slot = "lt1";
                }
                final String hex = colors.get(slot);
                if (hex == null) {
                    return null;
                }
                rgb = Integer.parseInt(hex, 16);
                break;
            }
            case "a:sysClr": {
                final String last = attr(c, "lastClr");
                rgb = last != null ? Integer.parseInt(last, 16) : "windowText".equals(val) ? 0 : 0xFFFFFF;
                break;
            }
            case "a:prstClr": {
                final Integer v = PRESET.get(val);
                rgb = v == null ? 0 : v;
                break;
            }
            case "a:scrgbClr":
                rgb = channel(num(c, "r", 0)) << 16 | channel(num(c, "g", 0)) << 8 | channel(num(c, "b", 0));
                break;
            case "a:hslClr": {
                final double[] hsl = {num(c, "hue", 0) / 60000.0 / 360, num(c, "sat", 0) / 100000.0,
                        num(c, "lum", 0) / 100000.0};
                rgb = fromHsl(hsl);
                break;
            }
            default:
                return null;
        }
        double alpha = 1;
        for (Element m : children(c, null)) {
            final double v = num(m, "val", 0) / 100000.0;
            switch (m.getNodeName()) {
                case "a:alpha":
                    alpha = v;
                    break;
                case "a:alphaMod":
                    alpha *= v;
                    break;
                case "a:alphaOff":
                    alpha += v;
                    break;
                case "a:lumMod": {
                    final double[] hsl = toHsl(rgb);
                    hsl[2] = Math.min(1, hsl[2] * v);
                    rgb = fromHsl(hsl);
                    break;
                }
                case "a:lumOff": {
                    final double[] hsl = toHsl(rgb);
                    hsl[2] = Math.max(0, Math.min(1, hsl[2] + v));
                    rgb = fromHsl(hsl);
                    break;
                }
                case "a:satMod": {
                    final double[] hsl = toHsl(rgb);
                    hsl[1] = Math.min(1, hsl[1] * v);
                    rgb = fromHsl(hsl);
                    break;
                }
                case "a:tint":
                    rgb = mix(rgb, 0xFFFFFF, v);
                    break;
                case "a:shade":
                    rgb = mix(rgb, 0x000000, v);
                    break;
                default:
                    break;
            }
        }
        alpha = Math.max(0, Math.min(1, alpha));
        if (alpha >= 0.995) {
            return String.format(Locale.US, "#%06x", rgb);
        }
        return String.format(Locale.US, "rgba(%d,%d,%d,%.3f)", rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, alpha);
    }

    private static int channel(long v) {
        return (int) Math.max(0, Math.min(255, Math.round(v / 100000.0 * 255)));
    }

    /** A tint or shade keeps that share of the colour and mixes the rest from the other end */
    private static int mix(int rgb, int toward, double keep) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            final double c = rgb >> shift & 255;
            final double t = toward >> shift & 255;
            out |= (int) Math.round(c * keep + t * (1 - keep)) << shift;
        }
        return out;
    }

    static int parseCss(String css) {
        if (css.startsWith("#")) {
            return Integer.parseInt(css.substring(1, 7), 16);
        }
        final String[] p = css.substring(css.indexOf('(') + 1, css.indexOf(')')).split(",");
        return Integer.parseInt(p[0].trim()) << 16 | Integer.parseInt(p[1].trim()) << 8 | Integer.parseInt(p[2].trim());
    }

    static double[] toHsl(int rgb) {
        final double r = (rgb >> 16 & 255) / 255.0, g = (rgb >> 8 & 255) / 255.0, b = (rgb & 255) / 255.0;
        final double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        final double l = (max + min) / 2;
        double h = 0, s = 0;
        if (max != min) {
            final double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == r) {
                h = (g - b) / d + (g < b ? 6 : 0);
            } else if (max == g) {
                h = (b - r) / d + 2;
            } else {
                h = (r - g) / d + 4;
            }
            h /= 6;
        }
        return new double[]{h, s, l};
    }

    static int fromHsl(double[] hsl) {
        final double h = hsl[0], s = hsl[1], l = hsl[2];
        double r = l, g = l, b = l;
        if (s != 0) {
            final double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            final double p = 2 * l - q;
            r = hue(p, q, h + 1.0 / 3);
            g = hue(p, q, h);
            b = hue(p, q, h - 1.0 / 3);
        }
        return (int) Math.round(r * 255) << 16 | (int) Math.round(g * 255) << 8 | (int) Math.round(b * 255);
    }

    private static double hue(double p, double q, double t) {
        if (t < 0) {
            t += 1;
        }
        if (t > 1) {
            t -= 1;
        }
        if (t < 1.0 / 6) {
            return p + (q - p) * 6 * t;
        }
        if (t < 0.5) {
            return q;
        }
        if (t < 2.0 / 3) {
            return p + (q - p) * (2.0 / 3 - t) * 6;
        }
        return p;
    }

    /** @return the nth (1-based) entry of one of the format scheme's style lists, or null */
    Element style(String list, int index) {
        final Element l = child(formats, list);
        final java.util.List<Element> items = children(l, null);
        return index >= 1 && index <= items.size() ? items.get(index - 1) : null;
    }

    /**
     * @return the CSS background of a fill element's parent (spPr, bgPr and such): the first of
     * solidFill, gradFill, blipFill, pattFill or noFill ("background:none;"); null if it has none
     */
    String fill(Element props, OoxmlPackage pkg, String part) {
        for (Element f : children(props, null)) {
            final String css = fillElement(f, pkg, part);
            if (css != null) {
                return css;
            }
        }
        return null;
    }

    /** @return the CSS background for one fill element, or null if the element is not a usable fill */
    String fillElement(Element f, OoxmlPackage pkg, String part) {
        switch (f.getNodeName()) {
            case "a:noFill":
                return "background:none;";
            case "a:solidFill": {
                final String c = color(f);
                return c == null ? null : "background:" + c + ";";
            }
            case "a:gradFill":
                return gradient(f);
            case "a:pattFill": {
                final String c = color(child(f, "a:fgClr"));
                return c == null ? null : "background:" + c + ";";
            }
            case "a:blipFill":
            case "p:blipFill": {
                final String uri = pkg == null ? null
                        : pkg.picture(pkg.target(part, attr(child(f, "a:blip"), "r:embed")));
                return uri == null ? null : "background:url(" + uri + ") center/100% 100% no-repeat;";
            }
            default:
                return null;
        }
    }

    private String gradient(Element f) {
        final StringBuilder stops = new StringBuilder();
        for (Element gs : children(child(f, "a:gsLst"), "a:gs")) {
            final String c = color(gs);
            if (c == null) {
                continue;
            }
            if (stops.length() > 0) {
                stops.append(',');
            }
            stops.append(c).append(' ').append(num(gs, "pos", 0) / 1000.0).append('%');
        }
        if (stops.length() == 0) {
            return null;
        }
        if (child(f, "a:path") != null) {
            return "background:radial-gradient(circle at 50% 50%," + stops + ");";
        }
        final Element lin = child(f, "a:lin");
        final double deg = lin == null ? 0 : num(lin, "ang", 0) / 60000.0;
        return "background:linear-gradient(" + OoxmlPackage.fmt(deg + 90) + "deg," + stops + ");";
    }
}
