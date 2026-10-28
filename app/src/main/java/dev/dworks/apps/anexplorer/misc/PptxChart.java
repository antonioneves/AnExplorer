package dev.dworks.apps.anexplorer.misc;

import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.attr;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.child;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.children;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.esc;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.fmt;
import static dev.dworks.apps.anexplorer.misc.OoxmlPackage.path;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a chart of a .pptx from the values cached in its chart part as a small SVG: bars, lines,
 * areas, scatter points, and pie or doughnut slices. Axes are plain; the series use the theme's accents.
 */
final class PptxChart {

    /** The number format of the chart being drawn, an Excel format code, or null for plain numbers */
    private static final ThreadLocal<String> FORMAT = new ThreadLocal<>();

    private static final String[] ACCENTS = {"accent1", "accent2", "accent3", "accent4", "accent5", "accent6"};

    private PptxChart() {
    }

    private static List<String> strings(Element ref) {
        final List<String> out = new ArrayList<>();
        final Element cache = path(ref, "c:strRef", "c:strCache") != null ? path(ref, "c:strRef", "c:strCache")
                : path(ref, "c:numRef", "c:numCache");
        for (Element pt : children(cache, "c:pt")) {
            out.add(OoxmlPackage.text(pt));
        }
        return out;
    }

    private static List<Double> numbers(Element ref) {
        final List<Double> out = new ArrayList<>();
        final Element cache = path(ref, "c:numRef", "c:numCache");
        for (Element pt : children(cache, "c:pt")) {
            try {
                out.add(Double.parseDouble(OoxmlPackage.text(pt).trim()));
            } catch (NumberFormatException e) {
                out.add(0.0);
            }
        }
        return out;
    }

    private static String color(OoxmlTheme theme, Element ser, int index) {
        final Element fill = path(ser, "c:spPr", "a:solidFill");
        final String own = fill == null ? null : theme.color(fill);
        if (own != null) {
            return own;
        }
        final String c = theme.colors.get(ACCENTS[index % ACCENTS.length]);
        return c == null ? "#4472c4" : "#" + c;
    }

    static void draw(Element chartSpace, OoxmlTheme theme, double w, double h, StringBuilder out) {
        final Element plot = path(chartSpace, "c:chart", "c:plotArea");
        Element chart = null;
        String kind = null;
        final List<Element> others = new ArrayList<>();
        for (Element c : children(plot, null)) {
            final String n = c.getNodeName();
            if (n.endsWith("Chart") && !n.equals("c:plotVisOnly")) {
                if (chart == null) {
                    chart = c;
                    kind = n.substring(2);
                } else if (n.equals("c:lineChart")) {
                    others.add(c);
                }
            }
        }
        FORMAT.set(formatCode(plot, chart));
        out.append("<svg width=\"").append(fmt(w)).append("\" height=\"").append(fmt(h)).append("\" font-size=\"12\" ")
                .append("font-family=\"sans-serif\">");
        if (chart == null) {
            out.append("<rect width=\"").append(fmt(w)).append("\" height=\"").append(fmt(h))
                    .append("\" fill=\"#f2f2f2\" stroke=\"#bbb\"/>");
            out.append("</svg>");
            return;
        }
        final List<Element> series = children(chart, "c:ser");
        final List<String> cats = series.isEmpty() ? new ArrayList<String>() : strings(child(series.get(0), "c:cat"));
        final boolean pie = kind.startsWith("pie") || kind.startsWith("doughnut") || kind.startsWith("ofPie");
        final String title = title(chartSpace);
        final boolean hasLegend = path(chartSpace, "c:chart", "c:legend") != null;
        final double top0 = title == null ? 10 : 34, bot0 = hasLegend ? 24 : 0;
        final List<String> names = new ArrayList<>();
        final List<String> swatches = new ArrayList<>();
        if (title != null) {
            out.append("<text x=\"").append(fmt(w / 2)).append("\" y=\"22\" text-anchor=\"middle\" font-size=\"16\" ")
                    .append("fill=\"#404040\">").append(esc(title)).append("</text>");
        }
        if (pie && !series.isEmpty()) {
            final List<Double> v = numbers(child(series.get(0), "c:val"));
            double sum = 0;
            for (double d : v) {
                sum += Math.max(d, 0);
            }
            final double r = Math.min(w, h - top0 - bot0) / 2 - 8, cx = w / 2, cy = top0 + (h - top0 - bot0) / 2;
            for (int i = 0; i < v.size(); i++) {
                names.add(i < cats.size() ? cats.get(i) : String.valueOf(i + 1));
                swatches.add(point(theme, series.get(0), i));
            }
            double a = -Math.PI / 2;
            for (int i = 0; i < v.size() && sum > 0; i++) {
                final double sweep = Math.max(v.get(i), 0) / sum * Math.PI * 2;
                final double x0 = cx + r * Math.cos(a), y0 = cy + r * Math.sin(a);
                final double x1 = cx + r * Math.cos(a + sweep), y1 = cy + r * Math.sin(a + sweep);
                final String fill = point(theme, series.get(0), i);
                if (sweep >= Math.PI * 2 - 1e-6) {
                    out.append("<circle cx=\"").append(fmt(cx)).append("\" cy=\"").append(fmt(cy)).append("\" r=\"")
                            .append(fmt(r)).append("\" fill=\"").append(fill).append("\"/>");
                } else {
                    out.append("<path d=\"M").append(fmt(cx)).append(' ').append(fmt(cy)).append('L').append(fmt(x0))
                            .append(' ').append(fmt(y0)).append('A').append(fmt(r)).append(' ').append(fmt(r))
                            .append(" 0 ").append(sweep > Math.PI ? 1 : 0).append(" 1 ").append(fmt(x1)).append(' ')
                            .append(fmt(y1)).append("Z\" fill=\"").append(fill).append("\" stroke=\"#fff\"/>");
                }
                a += sweep;
            }
            if (kind.startsWith("doughnut")) {
                out.append("<circle cx=\"").append(fmt(cx)).append("\" cy=\"").append(fmt(cy)).append("\" r=\"")
                        .append(fmt(r * 0.5)).append("\" fill=\"#fff\"/>");
            }
            if (hasLegend) {
                legend(out, names, swatches, w, h);
            }
            out.append("</svg>");
            return;
        }
        if (kind.startsWith("scatter") && !series.isEmpty()) {
            scatter(out, plot, series, theme, title, hasLegend, w, h, top0, bot0);
            return;
        }
        if (kind.startsWith("radar") && !series.isEmpty()) {
            radar(out, chart, series, cats, theme, hasLegend, w, h, top0, bot0);
            return;
        }
        final boolean bars = kind.startsWith("bar");
        final boolean area = kind.startsWith("area");
        final boolean labels = labels(chart);
        final boolean horizontal = bars && "bar".equals(attr(child(chart, "c:barDir"), "val"));
        final boolean stacked = "stacked".equals(attr(child(chart, "c:grouping"), "val"))
                || "percentStacked".equals(attr(child(chart, "c:grouping"), "val"));
        final boolean percent = "percentStacked".equals(attr(child(chart, "c:grouping"), "val"));
        final List<List<Double>> data = new ArrayList<>();
        double max = 0, min = 0;
        int n = cats.size();
        for (Element s : series) {
            final List<Double> v = numbers(child(s, "c:val"));
            data.add(v);
            n = Math.max(n, v.size());
        }
        if (percent) {
            // The values below are shares from 0 to 100, not fractions
            final String code = FORMAT.get();
            FORMAT.set(code != null && code.contains("%") ? "0\"%\"" : null);
            for (int i = 0; i < n; i++) {
                double total = 0;
                for (List<Double> v : data) {
                    total += i < v.size() ? Math.abs(v.get(i)) : 0;
                }
                for (List<Double> v : data) {
                    if (i < v.size() && total > 0) {
                        v.set(i, v.get(i) / total * 100);
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            double pos = 0, neg = 0;
            for (List<Double> v : data) {
                final double d = i < v.size() ? v.get(i) : 0;
                if (stacked) {
                    if (d >= 0) {
                        pos += d;
                    } else {
                        neg += d;
                    }
                } else {
                    max = Math.max(max, d);
                    min = Math.min(min, d);
                }
            }
            max = Math.max(max, pos);
            min = Math.min(min, neg);
        }
        final Element scaling = path(plot, "c:valAx", "c:scaling");
        if (scaling != null) {
            max = number(child(scaling, "c:max"), max);
            min = number(child(scaling, "c:min"), min);
        }
        if (max == min) {
            max = min + 1;
        }
        for (int i = 0; i < series.size(); i++) {
            names.add(seriesName(series.get(i), i));
            swatches.add(color(theme, series.get(i), i));
        }
        if (horizontal) {
            horizontalBars(out, series, data, cats, names, swatches, stacked, min, max, w, h, top0, bot0, hasLegend);
            return;
        }
        final String catTitle = axisTitle(plot, "c:catAx"), valTitle = axisTitle(plot, "c:valAx");
        final boolean secondary = !others.isEmpty() && !sameAxis(chart, others.get(0));
        final double left = valTitle == null ? 44 : 60, right = secondary ? 44 : 10, top = top0;
        final double bottom = 28 + bot0 + (catTitle == null ? 0 : 16);
        final double pw = w - left - right, ph = h - top - bottom;
        if (valTitle != null) {
            out.append("<text transform=\"translate(14 ").append(fmt(top + ph / 2)).append(") rotate(-90)\" ")
                    .append("text-anchor=\"middle\" fill=\"#595959\">").append(esc(valTitle)).append("</text>");
        }
        if (catTitle != null) {
            out.append("<text x=\"").append(fmt(left + pw / 2)).append("\" y=\"").append(fmt(h - bot0 - 6))
                    .append("\" text-anchor=\"middle\" fill=\"#595959\">").append(esc(catTitle)).append("</text>");
        }
        // Axis and grid
        for (int g = 0; g <= 4; g++) {
            final double val = min + (max - min) * g / 4;
            final double y = top + ph - ph * g / 4;
            out.append("<line x1=\"").append(fmt(left)).append("\" x2=\"").append(fmt(left + pw)).append("\" y1=\"")
                    .append(fmt(y)).append("\" y2=\"").append(fmt(y)).append("\" stroke=\"#d9d9d9\"/>");
            out.append("<text x=\"").append(fmt(left - 4)).append("\" y=\"").append(fmt(y + 4))
                    .append("\" text-anchor=\"end\" fill=\"#595959\">").append(esc(axis(val))).append("</text>");
        }
        final double slot = pw / Math.max(n, 1);
        final double zero = top + ph - ph * (0 - min) / (max - min);
        for (int i = 0; i < n; i++) {
            if (i < cats.size()) {
                out.append("<text x=\"").append(fmt(left + slot * (i + 0.5))).append("\" y=\"")
                        .append(fmt(h - bot0 - 10 - (catTitle == null ? 0 : 16)))
                        .append("\" text-anchor=\"middle\" fill=\"#595959\">").append(esc(cats.get(i)))
                        .append("</text>");
            }
        }
        final int count = Math.max(series.size(), 1);
        final double[] acc = new double[n];
        for (int s = 0; s < series.size(); s++) {
            final String fill = color(theme, series.get(s), s);
            final List<Double> v = data.get(s);
            if (bars) {
                final double group = slot * 0.7;
                final double bw = stacked ? group : group / count;
                for (int i = 0; i < v.size(); i++) {
                    final double d = v.get(i);
                    final double from = stacked ? acc[i] : 0;
                    final double y0 = top + ph - ph * (from - min) / (max - min);
                    final double y1 = top + ph - ph * (from + d - min) / (max - min);
                    final double x = left + slot * i + (slot - group) / 2 + (stacked ? 0 : bw * s);
                    acc[i] += d;
                    out.append("<rect x=\"").append(fmt(x)).append("\" y=\"").append(fmt(Math.min(y0, y1)))
                            .append("\" width=\"").append(fmt(bw)).append("\" height=\"")
                            .append(fmt(Math.abs(y0 - y1))).append("\" fill=\"").append(fill).append("\"/>");
                    if (labels) {
                        label(out, x + bw / 2, Math.min(y0, y1) - 3, d);
                    }
                }
            } else {
                final StringBuilder pts = new StringBuilder();
                final StringBuilder base = new StringBuilder();
                for (int i = 0; i < v.size(); i++) {
                    final double x = left + slot * (i + 0.5);
                    final double from = area && stacked ? acc[i] : 0;
                    final double y = top + ph - ph * (from + v.get(i) - min) / (max - min);
                    pts.append(fmt(x)).append(',').append(fmt(y)).append(' ');
                    base.insert(0, fmt(x) + "," + fmt(top + ph - ph * (from - min) / (max - min)) + " ");
                    acc[i] += v.get(i);
                    if (labels) {
                        label(out, x, y - 6, v.get(i));
                    }
                }
                if (area) {
                    out.append("<polygon points=\"").append(pts).append(base).append("\" fill=\"").append(fill)
                            .append("\" fill-opacity=\"0.75\"/>");
                } else {
                    out.append("<polyline points=\"").append(pts).append("\" fill=\"none\" stroke=\"").append(fill)
                            .append("\" stroke-width=\"2.5\" stroke-linejoin=\"round\"/>");
                }
            }
        }
        out.append("<line x1=\"").append(fmt(left)).append("\" x2=\"").append(fmt(left + pw)).append("\" y1=\"")
                .append(fmt(zero)).append("\" y2=\"").append(fmt(zero)).append("\" stroke=\"#868686\"/>");
        int index = series.size();
        for (Element other : others) {
            final boolean same = sameAxis(chart, other);
            final List<List<Double>> lines = new ArrayList<>();
            double lo = same ? min : 0, hi = same ? max : 0;
            for (Element ser : children(other, "c:ser")) {
                final List<Double> v = numbers(child(ser, "c:val"));
                lines.add(v);
                if (!same) {
                    for (double d : v) {
                        lo = Math.min(lo, d);
                        hi = Math.max(hi, d);
                    }
                }
            }
            if (hi == lo) {
                hi = lo + 1;
            }
            if (!same) {
                for (int g = 0; g <= 4; g++) {
                    out.append("<text x=\"").append(fmt(left + pw + 4)).append("\" y=\"")
                            .append(fmt(top + ph - ph * g / 4 + 4)).append("\" fill=\"#595959\">")
                            .append(esc(axis(lo + (hi - lo) * g / 4))).append("</text>");
                }
            }
            final List<Element> sers = children(other, "c:ser");
            for (int k = 0; k < sers.size(); k++, index++) {
                final String fill = color(theme, sers.get(k), index);
                final StringBuilder pts = new StringBuilder(), dots = new StringBuilder();
                final List<Double> v = lines.get(k);
                for (int i = 0; i < v.size(); i++) {
                    final double x = left + slot * (i + 0.5), y = top + ph - ph * (v.get(i) - lo) / (hi - lo);
                    pts.append(fmt(x)).append(',').append(fmt(y)).append(' ');
                    dots.append("<circle cx=\"").append(fmt(x)).append("\" cy=\"").append(fmt(y))
                            .append("\" r=\"3.5\" fill=\"").append(fill).append("\"/>");
                }
                out.append("<polyline points=\"").append(pts).append("\" fill=\"none\" stroke=\"").append(fill)
                        .append("\" stroke-width=\"2.5\" stroke-linejoin=\"round\"/>").append(dots);
                names.add(seriesName(sers.get(k), index));
                swatches.add(fill);
            }
        }
        if (hasLegend) {
            legend(out, names, swatches, w, h);
        }
        out.append("</svg>");
    }

    /** @return true if both chart groups are plotted against the same value axis */
    private static boolean sameAxis(Element a, Element b) {
        final List<Element> x = children(a, "c:axId"), y = children(b, "c:axId");
        return x.size() < 2 || y.size() < 2 || java.util.Objects.equals(attr(x.get(1), "val"), attr(y.get(1), "val"));
    }

    private static String point(OoxmlTheme theme, Element ser, int index) {
        for (Element dPt : children(ser, "c:dPt")) {
            if (String.valueOf(index).equals(attr(child(dPt, "c:idx"), "val"))) {
                final Element fill = path(dPt, "c:spPr", "a:solidFill");
                final String c = fill == null ? null : theme.color(fill);
                if (c != null) {
                    return c;
                }
            }
        }
        final String c = theme.colors.get(ACCENTS[index % ACCENTS.length]);
        return c == null ? "#4472c4" : "#" + c;
    }

    /** @return the value axis' number format, or the one the series' values were cached with, unless it is plain */
    private static String formatCode(Element plot, Element chart) {
        String code = attr(path(plot, "c:valAx", "c:numFmt"), "formatCode");
        if (code == null && chart != null) {
            final Element cached = path(child(chart, "c:ser"), "c:val", "c:numRef", "c:numCache", "c:formatCode");
            code = cached == null ? null : OoxmlPackage.text(cached).trim();
        }
        return code == null || code.isEmpty() || code.equalsIgnoreCase("General") ? null : code;
    }

    /**
     * Formats a number with the first section of an Excel format code: its digits, thousands separator,
     * percent sign and any literal text around the number. Dates and scientific codes are shown plain.
     */
    private static String axis(double v) {
        final String code = FORMAT.get();
        if (code == null) {
            return plain(v);
        }
        final String section = code.split(";")[0];
        final StringBuilder before = new StringBuilder(), after = new StringBuilder();
        int decimals = 0;
        boolean grouping = false, number = false, percent = false, fraction = false;
        for (int i = 0; i < section.length(); i++) {
            final char c = section.charAt(i);
            final StringBuilder side = number ? after : before;
            if (c == '"') {
                final int end = section.indexOf('"', i + 1);
                side.append(section, i + 1, end < 0 ? section.length() : end);
                i = end < 0 ? section.length() : end;
            } else if (c == '[') {
                final int end = section.indexOf(']', i);
                final String inner = end < 0 ? "" : section.substring(i + 1, end);
                if (inner.startsWith("$")) {
                    side.append(inner.substring(1).split("-")[0]);
                }
                i = end < 0 ? section.length() : end;
            } else if (c == '\\' || c == '_' || c == '*') {
                if (c == '\\' && i + 1 < section.length()) {
                    side.append(section.charAt(i + 1));
                }
                i++;
            } else if (c == '0' || c == '#' || c == '?') {
                if (number && after.length() > 0) {
                    return plain(v);
                }
                number = true;
                if (fraction) {
                    decimals++;
                }
            } else if (c == ',' && number && !fraction) {
                grouping = true;
            } else if (c == '.' && number) {
                fraction = true;
            } else if (c == '%') {
                percent = true;
                side.append('%');
            } else if (c == 'E' || c == 'e' || c == 'y' || c == 'd' || c == 'm' || c == 'h' || c == 's' || c == '@') {
                return plain(v);
            } else {
                side.append(c);
            }
        }
        if (!number) {
            return plain(v);
        }
        final double shown = percent ? v * 100 : v;
        final String digits = String.format(java.util.Locale.US, grouping ? "%,." + decimals + "f" : "%." + decimals + "f",
                Math.abs(shown));
        final boolean negative = shown < 0 && Double.parseDouble(digits.replace(",", "")) != 0;
        return (negative ? "-" : "") + before + digits + after;
    }

    private static String plain(double v) {
        return Math.abs(v) >= 1000 ? fmt(Math.round(v)) : fmt(Math.round(v * 10) / 10.0);
    }

    private static String title(Element chartSpace) {
        final Element t = path(chartSpace, "c:chart", "c:title");
        if (t == null || "1".equals(attr(path(chartSpace, "c:chart", "c:autoTitleDeleted"), "val"))) {
            return null;
        }
        final String text = OoxmlPackage.text(child(t, "c:tx")).trim();
        return text.isEmpty() ? null : text;
    }

    private static String seriesName(Element ser, int index) {
        final String n = OoxmlPackage.text(path(ser, "c:tx", "c:strRef", "c:strCache")).trim();
        return n.isEmpty() ? "Series " + (index + 1) : n;
    }

    /** Rings for the value scale, a spoke and label per category, and one polygon per series */
    private static void radar(StringBuilder out, Element chart, List<Element> series, List<String> cats,
                              OoxmlTheme theme, boolean hasLegend, double w, double h, double top0, double bot0) {
        final List<List<Double>> data = new ArrayList<>();
        final List<String> names = new ArrayList<>(), colors = new ArrayList<>();
        int n = cats.size();
        double max = 0;
        for (int i = 0; i < series.size(); i++) {
            final List<Double> v = numbers(child(series.get(i), "c:val"));
            data.add(v);
            n = Math.max(n, v.size());
            for (double d : v) {
                max = Math.max(max, d);
            }
            names.add(seriesName(series.get(i), i));
            colors.add(color(theme, series.get(i), i));
        }
        if (max <= 0) {
            max = 1;
        }
        final boolean filled = "filled".equals(attr(child(chart, "c:radarStyle"), "val"));
        final double cx = w / 2, cy = top0 + (h - top0 - bot0) / 2, r = Math.min(w / 2 - 70, (h - top0 - bot0) / 2 - 22);
        for (int g = 1; g <= 4; g++) {
            final StringBuilder ring = new StringBuilder();
            for (int i = 0; i < n; i++) {
                final double a = -Math.PI / 2 + Math.PI * 2 * i / n;
                ring.append(fmt(cx + r * g / 4 * Math.cos(a))).append(',').append(fmt(cy + r * g / 4 * Math.sin(a)))
                        .append(' ');
            }
            out.append("<polygon points=\"").append(ring).append("\" fill=\"none\" stroke=\"#d9d9d9\"/>");
            out.append("<text x=\"").append(fmt(cx - 4)).append("\" y=\"").append(fmt(cy - r * g / 4 + 3))
                    .append("\" text-anchor=\"end\" fill=\"#595959\" font-size=\"10\">").append(esc(axis(max * g / 4))).append("</text>");
        }
        for (int i = 0; i < n; i++) {
            final double a = -Math.PI / 2 + Math.PI * 2 * i / n, cos = Math.cos(a), sin = Math.sin(a);
            out.append("<line x1=\"").append(fmt(cx)).append("\" y1=\"").append(fmt(cy)).append("\" x2=\"")
                    .append(fmt(cx + r * cos)).append("\" y2=\"").append(fmt(cy + r * sin))
                    .append("\" stroke=\"#d9d9d9\"/>");
            out.append("<text x=\"").append(fmt(cx + (r + 10) * cos)).append("\" y=\"").append(fmt(cy + (r + 10) * sin + (sin < -0.9 ? -2 : 4)))
                    .append("\" text-anchor=\"").append(cos > 0.3 ? "start" : cos < -0.3 ? "end" : "middle")
                    .append("\" fill=\"#595959\">").append(esc(i < cats.size() ? cats.get(i) : String.valueOf(i + 1)))
                    .append("</text>");
        }
        for (int s = 0; s < data.size(); s++) {
            final StringBuilder points = new StringBuilder();
            for (int i = 0; i < n; i++) {
                final double d = i < data.get(s).size() ? Math.max(data.get(s).get(i), 0) : 0;
                final double a = -Math.PI / 2 + Math.PI * 2 * i / n;
                points.append(fmt(cx + r * d / max * Math.cos(a))).append(',')
                        .append(fmt(cy + r * d / max * Math.sin(a))).append(' ');
            }
            out.append("<polygon points=\"").append(points).append("\" fill=\"").append(colors.get(s))
                    .append("\" fill-opacity=\"").append(filled ? "0.45" : "0").append("\" stroke=\"").append(colors.get(s))
                    .append("\" stroke-width=\"2\"/>");
        }
        if (hasLegend) {
            legend(out, names, colors, w, h);
        }
        out.append("</svg>");
    }

    private static void legend(StringBuilder out, List<String> names, List<String> colors, double w, double h) {
        double total = 0;
        for (String n : names) {
            total += 26 + n.length() * 7;
        }
        double x = Math.max(4, (w - total) / 2);
        for (int i = 0; i < names.size(); i++) {
            out.append("<rect x=\"").append(fmt(x)).append("\" y=\"").append(fmt(h - 17)).append("\" width=\"11\" height=\"11\" fill=\"")
                    .append(colors.get(i)).append("\"/><text x=\"").append(fmt(x + 16)).append("\" y=\"").append(fmt(h - 7))
                    .append("\" fill=\"#595959\">").append(esc(names.get(i))).append("</text>");
            x += 26 + names.get(i).length() * 7;
        }
    }

    /** Bars that run left to right, with the categories down the side */
    private static void horizontalBars(StringBuilder out, List<Element> series, List<List<Double>> data,
                                       List<String> cats, List<String> names, List<String> colors, boolean stacked,
                                       double min, double max, double w, double h, double top0, double bot0,
                                       boolean hasLegend) {
        int n = cats.size();
        for (List<Double> v : data) {
            n = Math.max(n, v.size());
        }
        double label = 30;
        for (String c : cats) {
            label = Math.max(label, Math.min(c.length() * 7 + 8, 160));
        }
        final double left = label, right = 14, top = top0, bottom = 24 + bot0;
        final double pw = w - left - right, ph = h - top - bottom;
        for (int g = 0; g <= 4; g++) {
            final double val = min + (max - min) * g / 4;
            final double x = left + pw * g / 4;
            out.append("<line x1=\"").append(fmt(x)).append("\" x2=\"").append(fmt(x)).append("\" y1=\"").append(fmt(top))
                    .append("\" y2=\"").append(fmt(top + ph)).append("\" stroke=\"#d9d9d9\"/><text x=\"").append(fmt(x))
                    .append("\" y=\"").append(fmt(h - bot0 - 8)).append("\" text-anchor=\"middle\" fill=\"#595959\">")
                    .append(esc(axis(val))).append("</text>");
        }
        final double slot = ph / Math.max(n, 1);
        final int count = Math.max(series.size(), 1);
        final double[] acc = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < cats.size()) {
                // The first category sits at the bottom, as in Office
                out.append("<text x=\"").append(fmt(left - 6)).append("\" y=\"").append(fmt(top + ph - slot * (i + 0.5) + 4))
                        .append("\" text-anchor=\"end\" fill=\"#595959\">").append(esc(cats.get(i))).append("</text>");
            }
        }
        for (int s = 0; s < series.size(); s++) {
            final List<Double> v = data.get(s);
            final double group = slot * 0.7, bh = stacked ? group : group / count;
            for (int i = 0; i < v.size(); i++) {
                final double d = v.get(i), from = stacked ? acc[i] : 0;
                final double x0 = left + pw * (from - min) / (max - min), x1 = left + pw * (from + d - min) / (max - min);
                final double y = top + ph - slot * (i + 1) + (slot - group) / 2 + (stacked ? 0 : bh * (count - 1 - s));
                acc[i] += d;
                out.append("<rect x=\"").append(fmt(Math.min(x0, x1))).append("\" y=\"").append(fmt(y)).append("\" width=\"")
                        .append(fmt(Math.abs(x1 - x0))).append("\" height=\"").append(fmt(bh)).append("\" fill=\"")
                        .append(colors.get(s)).append("\"/>");
            }
        }
        if (hasLegend) {
            legend(out, names, colors, w, h);
        }
        out.append("</svg>");
    }

    private static double number(Element e, double fallback) {
        try {
            return e == null ? fallback : Double.parseDouble(attr(e, "val"));
        } catch (RuntimeException x) {
            return fallback;
        }
    }

    /** @return true if the chart or one of its series asks for the values to be written on the marks */
    private static boolean labels(Element chart) {
        if ("1".equals(attr(path(chart, "c:dLbls", "c:showVal"), "val"))) {
            return true;
        }
        for (Element ser : children(chart, "c:ser")) {
            if ("1".equals(attr(path(ser, "c:dLbls", "c:showVal"), "val"))) {
                return true;
            }
        }
        return false;
    }

    private static void label(StringBuilder out, double x, double y, double v) {
        out.append("<text x=\"").append(fmt(x)).append("\" y=\"").append(fmt(y))
                .append("\" text-anchor=\"middle\" font-size=\"11\" fill=\"#404040\">").append(esc(axis(v))).append("</text>");
    }

    private static String axisTitle(Element plot, String axis) {
        final Element t = path(plot, axis, "c:title");
        final String text = t == null ? "" : OoxmlPackage.text(child(t, "c:tx")).trim();
        return text.isEmpty() ? null : text;
    }

    /** Points, and lines between them if the series has a line, on a numeric x axis */
    private static void scatter(StringBuilder out, Element plot, List<Element> series, OoxmlTheme theme, String title,
                                boolean hasLegend, double w, double h, double top0, double bot0) {
        final List<List<Double>> xs = new ArrayList<>(), ys = new ArrayList<>();
        double xmin = Double.MAX_VALUE, xmax = -Double.MAX_VALUE, ymin = 0, ymax = 0;
        final List<String> names = new ArrayList<>(), colors = new ArrayList<>();
        for (int s = 0; s < series.size(); s++) {
            final List<Double> x = numbers(child(series.get(s), "c:xVal")), y = numbers(child(series.get(s), "c:yVal"));
            for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
                xmin = Math.min(xmin, x.get(i));
                xmax = Math.max(xmax, x.get(i));
                ymin = Math.min(ymin, y.get(i));
                ymax = Math.max(ymax, y.get(i));
            }
            xs.add(x);
            ys.add(y);
            names.add(seriesName(series.get(s), s));
            colors.add(color(theme, series.get(s), s));
        }
        if (xmin > xmax) {
            xmin = 0;
            xmax = 1;
        }
        if (xmax == xmin) {
            xmax = xmin + 1;
        }
        if (ymax == ymin) {
            ymax = ymin + 1;
        }
        final double left = 50, right = 14, top = top0, bottom = 26 + bot0;
        final double pw = w - left - right, ph = h - top - bottom;
        for (int g = 0; g <= 4; g++) {
            final double y = top + ph - ph * g / 4, x = left + pw * g / 4;
            out.append("<line x1=\"").append(fmt(left)).append("\" x2=\"").append(fmt(left + pw)).append("\" y1=\"").append(fmt(y))
                    .append("\" y2=\"").append(fmt(y)).append("\" stroke=\"#d9d9d9\"/><text x=\"").append(fmt(left - 4))
                    .append("\" y=\"").append(fmt(y + 4)).append("\" text-anchor=\"end\" fill=\"#595959\">")
                    .append(esc(axis(ymin + (ymax - ymin) * g / 4))).append("</text><text x=\"").append(fmt(x))
                    .append("\" y=\"").append(fmt(h - bot0 - 8)).append("\" text-anchor=\"middle\" fill=\"#595959\">")
                    .append(esc(axis(xmin + (xmax - xmin) * g / 4))).append("</text>");
        }
        for (int s = 0; s < series.size(); s++) {
            final StringBuilder pts = new StringBuilder(), dots = new StringBuilder();
            for (int i = 0; i < Math.min(xs.get(s).size(), ys.get(s).size()); i++) {
                final double x = left + pw * (xs.get(s).get(i) - xmin) / (xmax - xmin);
                final double y = top + ph - ph * (ys.get(s).get(i) - ymin) / (ymax - ymin);
                pts.append(fmt(x)).append(',').append(fmt(y)).append(' ');
                dots.append("<circle cx=\"").append(fmt(x)).append("\" cy=\"").append(fmt(y)).append("\" r=\"4\" fill=\"")
                        .append(colors.get(s)).append("\"/>");
            }
            if (child(path(series.get(s), "c:spPr", "a:ln"), "a:noFill") == null
                    && !"marker".equals(attr(path(plot, "c:scatterChart", "c:scatterStyle"), "val"))) {
                out.append("<polyline points=\"").append(pts).append("\" fill=\"none\" stroke=\"").append(colors.get(s))
                        .append("\" stroke-width=\"2\"/>");
            }
            out.append(dots);
        }
        if (hasLegend) {
            legend(out, names, colors, w, h);
        }
        out.append("</svg>");
    }
}
