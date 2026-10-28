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
 * areas as lines, and pie or doughnut slices. Axes are plain; the series use the theme's accents.
 */
final class PptxChart {

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
        for (Element c : children(plot, null)) {
            final String n = c.getNodeName();
            if (n.endsWith("Chart") && !n.equals("c:plotVisOnly")) {
                chart = c;
                kind = n.substring(2);
                break;
            }
        }
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
        final boolean bars = kind.startsWith("bar");
        final boolean horizontal = bars && "bar".equals(attr(child(chart, "c:barDir"), "val"));
        final boolean stacked = "stacked".equals(attr(child(chart, "c:grouping"), "val"))
                || "percentStacked".equals(attr(child(chart, "c:grouping"), "val"));
        final List<List<Double>> data = new ArrayList<>();
        double max = 0, min = 0;
        int n = cats.size();
        for (Element s : series) {
            final List<Double> v = numbers(child(s, "c:val"));
            data.add(v);
            n = Math.max(n, v.size());
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
        final double left = 44, right = 10, top = top0, bottom = 28 + bot0;
        final double pw = w - left - right, ph = h - top - bottom;
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
                out.append("<text x=\"").append(fmt(left + slot * (i + 0.5))).append("\" y=\"").append(fmt(h - 10))
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
                }
            } else {
                final StringBuilder pts = new StringBuilder();
                for (int i = 0; i < v.size(); i++) {
                    final double x = left + slot * (i + 0.5);
                    final double y = top + ph - ph * (v.get(i) - min) / (max - min);
                    pts.append(fmt(x)).append(',').append(fmt(y)).append(' ');
                }
                out.append("<polyline points=\"").append(pts).append("\" fill=\"none\" stroke=\"").append(fill)
                        .append("\" stroke-width=\"2.5\" stroke-linejoin=\"round\"/>");
            }
        }
        out.append("<line x1=\"").append(fmt(left)).append("\" x2=\"").append(fmt(left + pw)).append("\" y1=\"")
                .append(fmt(zero)).append("\" y2=\"").append(fmt(zero)).append("\" stroke=\"#868686\"/>");
        if (hasLegend) {
            legend(out, names, swatches, w, h);
        }
        out.append("</svg>");
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

    private static String axis(double v) {
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
}
