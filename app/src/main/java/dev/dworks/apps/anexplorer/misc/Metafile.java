package dev.dworks.apps.anexplorer.misc;

import static dev.dworks.apps.anexplorer.misc.BinaryOffice.u16;
import static dev.dworks.apps.anexplorer.misc.BinaryOffice.u32;

import android.util.Base64;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a Windows metafile (EMF or WMF), the vector pictures Office keeps for clip art, charts
 * and pasted drawings, into an SVG image a WebView can show. Reads the common drawing records
 * ([MS-EMF], [MS-WMF]): lines, rectangles, ellipses, polygons, curves, paths, text and bitmaps.
 * Gradients, clipping and the newer EMF+ records are left out.
 */
final class Metafile {

    private static final Charset UTF16 = Charset.forName("UTF-16LE");
    private static final Charset ANSI = Charset.forName("windows-1252");
    private static final int MAX_SVG = 12 * 1024 * 1024;

    private Metafile() {
    }

    /** @return the picture as an SVG data URI, or null if it holds nothing that can be drawn */
    static String toSvg(byte[] b) {
        try {
            final Painter p = new Painter();
            if (b.length > 44 && u32(b, 40) == 0x464D4520) {
                p.emf(b);
            } else if (b.length > 22 && u32(b, 0) == 0x9AC6CDD7) {
                p.wmf(b);
            } else {
                return null;
            }
            final String svg = p.svg();
            if (svg == null || svg.length() > MAX_SVG) {
                return null;
            }
            return "data:image/svg+xml;base64," + Base64.encodeToString(svg.getBytes(Charset.forName("UTF-8")),
                    Base64.NO_WRAP);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int s16(byte[] b, int at) {
        return (short) u16(b, at);
    }

    private static String color(int ref) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", ref & 0xFF, ref >> 8 & 0xFF, ref >> 16 & 0xFF);
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final class Pen {
        String color;
        double width = 1;
        int style;
    }

    private static final class Brush {
        String color;
    }

    private static final class Font {
        int height = 16, escapement, weight;
        boolean italic, underline;
        String face = "sans-serif";
    }

    /** Draws the records into SVG markup, following the device context state the way GDI does */
    private static final class Painter {
        final StringBuilder body = new StringBuilder();
        final Map<Integer, Object> objects = new HashMap<>();
        final StringBuilder path = new StringBuilder();
        boolean inPath;
        double winX, winY, winW, winH;
        boolean haveWindow;
        double boundsX, boundsY, boundsW, boundsH;
        double pxW, pxH;
        Pen pen = black();
        Brush brush = white();
        Font font = new Font();
        String textColor = "#000000";
        int textAlign, fillMode = 1;
        double curX, curY;
        double[] matrix = {1, 0, 0, 1, 0, 0};
        int drawn;

        static Pen black() {
            final Pen p = new Pen();
            p.color = "#000000";
            return p;
        }

        static Brush white() {
            final Brush b = new Brush();
            b.color = "#ffffff";
            return b;
        }

        String svg() {
            if (drawn == 0) {
                return null;
            }
            final double x = haveWindow ? winX : boundsX, y = haveWindow ? winY : boundsY;
            double w = haveWindow ? winW : boundsW, h = haveWindow ? winH : boundsH;
            if (w <= 0 || h <= 0) {
                return null;
            }
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"" + num(x) + " " + num(y) + " " + num(w)
                    + " " + num(h) + "\" width=\"" + num(pxW > 0 ? pxW : w) + "\" height=\"" + num(pxH > 0 ? pxH : h)
                    + "\" preserveAspectRatio=\"none\">" + body + "</svg>";
        }

        String fillAttr() {
            return brush.color == null ? "none" : brush.color;
        }

        String strokeAttr() {
            if (pen.color == null) {
                return "stroke=\"none\"";
            }
            String s = "stroke=\"" + pen.color + "\" stroke-width=\"" + num(Math.max(pen.width, 1)) + "\"";
            if (pen.style == 1) {
                s += " stroke-dasharray=\"" + num(pen.width * 6) + "," + num(pen.width * 3) + "\"";
            } else if (pen.style == 2) {
                s += " stroke-dasharray=\"" + num(pen.width) + "," + num(pen.width * 2) + "\"";
            } else if (pen.style == 3 || pen.style == 4) {
                s += " stroke-dasharray=\"" + num(pen.width * 6) + "," + num(pen.width * 2) + "," + num(pen.width)
                        + "," + num(pen.width * 2) + "\"";
            }
            return s;
        }

        String transform() {
            return matrix[0] == 1 && matrix[1] == 0 && matrix[2] == 0 && matrix[3] == 1 && matrix[4] == 0
                    && matrix[5] == 0 ? "" : " transform=\"matrix(" + num(matrix[0]) + " " + num(matrix[1]) + " "
                    + num(matrix[2]) + " " + num(matrix[3]) + " " + num(matrix[4]) + " " + num(matrix[5]) + ")\"";
        }

        /** Adds outline data to the open path, or draws it right away */
        void shape(String d, boolean closed) {
            if (inPath) {
                path.append(d);
                return;
            }
            drawn++;
            body.append("<path d=\"").append(d).append("\" fill=\"").append(closed ? fillAttr() : "none")
                    .append("\" ").append(fillMode == 2 ? "fill-rule=\"nonzero\" " : "fill-rule=\"evenodd\" ")
                    .append(strokeAttr()).append(transform()).append("/>");
        }

        String points(byte[] b, int at, int count, boolean wide, boolean close) {
            final StringBuilder d = new StringBuilder();
            for (int i = 0; i < count; i++) {
                final double x = wide ? u32(b, at + i * 8) : s16(b, at + i * 4);
                final double y = wide ? u32(b, at + i * 8 + 4) : s16(b, at + i * 4 + 2);
                d.append(i == 0 ? "M" : "L").append(num(x)).append(' ').append(num(y)).append(' ');
            }
            return close ? d.append("Z ").toString() : d.toString();
        }

        String bezier(byte[] b, int at, int count, boolean wide, boolean first) {
            final StringBuilder d = new StringBuilder();
            int i = 0;
            if (first && count > 0) {
                d.append("M").append(num(wide ? u32(b, at) : s16(b, at))).append(' ')
                        .append(num(wide ? u32(b, at + 4) : s16(b, at + 2))).append(' ');
                i = 1;
            }
            for (; i + 2 < count; i += 3) {
                d.append("C");
                for (int k = 0; k < 3; k++) {
                    final int o = at + (i + k) * (wide ? 8 : 4);
                    d.append(num(wide ? u32(b, o) : s16(b, o))).append(' ')
                            .append(num(wide ? u32(b, o + 4) : s16(b, o + 2))).append(' ');
                }
            }
            return d.toString();
        }

        void rect(double l, double t, double r, double bt) {
            shape("M" + num(l) + " " + num(t) + " L" + num(r) + " " + num(t) + " L" + num(r) + " " + num(bt)
                    + " L" + num(l) + " " + num(bt) + " Z ", true);
        }

        void ellipse(double l, double t, double r, double bt) {
            final double rx = Math.abs(r - l) / 2, ry = Math.abs(bt - t) / 2, cx = (l + r) / 2, cy = (t + bt) / 2;
            shape("M" + num(cx - rx) + " " + num(cy) + " A" + num(rx) + " " + num(ry) + " 0 1 0 " + num(cx + rx)
                    + " " + num(cy) + " A" + num(rx) + " " + num(ry) + " 0 1 0 " + num(cx - rx) + " " + num(cy)
                    + " Z ", true);
        }

        void roundRect(double l, double t, double r, double bt, double cw, double ch) {
            final double rx = Math.min(Math.abs(cw) / 2, Math.abs(r - l) / 2);
            final double ry = Math.min(Math.abs(ch) / 2, Math.abs(bt - t) / 2);
            shape("M" + num(l + rx) + " " + num(t) + " H" + num(r - rx) + " A" + num(rx) + " " + num(ry) + " 0 0 1 "
                    + num(r) + " " + num(t + ry) + " V" + num(bt - ry) + " A" + num(rx) + " " + num(ry) + " 0 0 1 "
                    + num(r - rx) + " " + num(bt) + " H" + num(l + rx) + " A" + num(rx) + " " + num(ry) + " 0 0 1 "
                    + num(l) + " " + num(bt - ry) + " V" + num(t + ry) + " A" + num(rx) + " " + num(ry) + " 0 0 1 "
                    + num(l + rx) + " " + num(t) + " Z ", true);
        }

        void line(double x, double y) {
            shape("M" + num(curX) + " " + num(curY) + " L" + num(x) + " " + num(y) + " ", false);
            curX = x;
            curY = y;
        }

        void text(String text, double x, double y) {
            if (text.trim().isEmpty()) {
                return;
            }
            final int size = Math.abs(font.height) == 0 ? 16 : Math.abs(font.height);
            final int vertical = textAlign & 24, horizontal = textAlign & 6;
            final double base = vertical == 24 ? y : vertical == 8 ? y - size * 0.2 : y + size * 0.8;
            drawn++;
            body.append("<text x=\"").append(num(x)).append("\" y=\"").append(num(base)).append("\" font-size=\"")
                    .append(size).append("\" font-family=\"").append(esc(font.face)).append(",sans-serif\" fill=\"")
                    .append(textColor).append('"');
            if (font.weight >= 600) {
                body.append(" font-weight=\"bold\"");
            }
            if (font.italic) {
                body.append(" font-style=\"italic\"");
            }
            if (font.underline) {
                body.append(" text-decoration=\"underline\"");
            }
            if (horizontal == 6) {
                body.append(" text-anchor=\"middle\"");
            } else if (horizontal == 2) {
                body.append(" text-anchor=\"end\"");
            }
            String t = transform();
            if (font.escapement != 0) {
                t = t.isEmpty() ? " transform=\"rotate(" + num(-font.escapement / 10.0) + " " + num(x) + " " + num(base) + ")\""
                        : t;
            }
            body.append(" xml:space=\"preserve\"").append(t).append('>').append(esc(text)).append("</text>");
        }

        void image(byte[] b, int bmi, int bmiLen, int bits, int bitsLen, double x, double y, double w, double h) {
            final String uri = dib(b, bmi, bmiLen, bits, bitsLen);
            if (uri == null || w == 0 || h == 0) {
                return;
            }
            drawn++;
            body.append("<image x=\"").append(num(Math.min(x, x + w))).append("\" y=\"").append(num(Math.min(y, y + h)))
                    .append("\" width=\"").append(num(Math.abs(w))).append("\" height=\"").append(num(Math.abs(h)))
                    .append("\" preserveAspectRatio=\"none\" href=\"").append(uri).append('"').append(transform())
                    .append("/>");
        }

        // ---- EMF ----

        void emf(byte[] b) {
            boundsX = u32(b, 8);
            boundsY = u32(b, 12);
            boundsW = u32(b, 16) - boundsX + 1;
            boundsH = u32(b, 20) - boundsY + 1;
            if (boundsW <= 1 || boundsH <= 1) {
                // Some writers leave the device bounds empty; the frame is in hundredths of a millimetre
                boundsX = 0;
                boundsY = 0;
                boundsW = (u32(b, 32) - u32(b, 24)) * 96 / 2540.0;
                boundsH = (u32(b, 36) - u32(b, 28)) * 96 / 2540.0;
            }
            pxW = (u32(b, 32) - u32(b, 24)) * 96 / 2540.0;
            pxH = (u32(b, 36) - u32(b, 28)) * 96 / 2540.0;
            double extX = 0, extY = 0, orgX = 0, orgY = 0;
            boolean haveExt = false;
            final java.util.ArrayDeque<Object[]> saved = new java.util.ArrayDeque<>();
            int pos = 0;
            while (pos + 8 <= b.length) {
                final int type = u32(b, pos), size = u32(b, pos + 4);
                if (size < 8 || pos + size > b.length || size % 4 != 0) {
                    break;
                }
                final int r = pos;
                switch (type) {
                    case 9: // SETWINDOWEXTEX
                        extX = u32(b, r + 8);
                        extY = u32(b, r + 12);
                        haveExt = true;
                        break;
                    case 10: // SETWINDOWORGEX
                        orgX = u32(b, r + 8);
                        orgY = u32(b, r + 12);
                        break;
                    case 14:
                        pos = b.length;
                        continue;
                    case 19:
                        fillMode = u32(b, r + 8);
                        break;
                    case 22:
                        textAlign = u32(b, r + 8);
                        break;
                    case 24:
                        textColor = color(u32(b, r + 8));
                        break;
                    case 27:
                        curX = u32(b, r + 8);
                        curY = u32(b, r + 12);
                        if (inPath) {
                            path.append("M").append(num(curX)).append(' ').append(num(curY)).append(' ');
                        }
                        break;
                    case 33:
                        saved.push(new Object[]{pen, brush, font, textColor, matrix.clone(), textAlign});
                        break;
                    case 34: {
                        final int n = Math.max(1, -u32(b, r + 8));
                        for (int i = 0; i < n && !saved.isEmpty(); i++) {
                            final Object[] s = saved.pop();
                            pen = (Pen) s[0];
                            brush = (Brush) s[1];
                            font = (Font) s[2];
                            textColor = (String) s[3];
                            matrix = (double[]) s[4];
                            textAlign = (Integer) s[5];
                        }
                        break;
                    }
                    case 35:
                        matrix = xform(b, r + 8);
                        break;
                    case 36: {
                        final double[] m = xform(b, r + 8);
                        final int mode = u32(b, r + 32);
                        if (mode == 1) {
                            matrix = new double[]{1, 0, 0, 1, 0, 0};
                        } else if (mode == 2) {
                            matrix = multiply(m, matrix);
                        } else if (mode == 3) {
                            matrix = multiply(matrix, m);
                        } else if (mode == 4) {
                            matrix = m;
                        }
                        break;
                    }
                    case 37:
                        select(u32(b, r + 8));
                        break;
                    case 38: { // CREATEPEN
                        final Pen p = new Pen();
                        final int style = u32(b, r + 12) & 0xF;
                        p.style = style;
                        p.color = style == 5 ? null : color(u32(b, r + 24));
                        p.width = Math.max(1, u32(b, r + 16));
                        objects.put(u32(b, r + 8), p);
                        break;
                    }
                    case 95: { // EXTCREATEPEN
                        final Pen p = new Pen();
                        final int style = u32(b, r + 28) & 0xF;
                        p.style = style;
                        p.color = style == 5 ? null : color(u32(b, r + 40));
                        p.width = Math.max(1, u32(b, r + 32));
                        objects.put(u32(b, r + 8), p);
                        break;
                    }
                    case 39: { // CREATEBRUSHINDIRECT
                        final Brush br = new Brush();
                        br.color = u32(b, r + 12) == 1 ? null : color(u32(b, r + 16));
                        objects.put(u32(b, r + 8), br);
                        break;
                    }
                    case 82: { // EXTCREATEFONTINDIRECTW
                        final Font f = new Font();
                        f.height = u32(b, r + 12);
                        f.escapement = u32(b, r + 20);
                        f.weight = u32(b, r + 28);
                        f.italic = (b[r + 32] & 0xFF) != 0;
                        f.underline = (b[r + 33] & 0xFF) != 0;
                        int end = r + 40;
                        while (end + 1 < r + 104 && (b[end] != 0 || b[end + 1] != 0)) {
                            end += 2;
                        }
                        f.face = new String(b, r + 40, end - (r + 40), UTF16);
                        objects.put(u32(b, r + 8), f);
                        break;
                    }
                    case 40:
                        objects.remove(u32(b, r + 8));
                        break;
                    case 42:
                        ellipse(u32(b, r + 8), u32(b, r + 12), u32(b, r + 16), u32(b, r + 20));
                        break;
                    case 43:
                        rect(u32(b, r + 8), u32(b, r + 12), u32(b, r + 16), u32(b, r + 20));
                        break;
                    case 44:
                        roundRect(u32(b, r + 8), u32(b, r + 12), u32(b, r + 16), u32(b, r + 20), u32(b, r + 24),
                                u32(b, r + 28));
                        break;
                    case 54:
                        line(u32(b, r + 8), u32(b, r + 12));
                        break;
                    case 59:
                        inPath = true;
                        path.setLength(0);
                        break;
                    case 60:
                        inPath = false;
                        break;
                    case 61:
                        if (inPath) {
                            path.append("Z ");
                        }
                        break;
                    case 62:
                    case 63:
                    case 64: {
                        final boolean fill = type != 64, stroke = type != 62;
                        if (path.length() > 0) {
                            drawn++;
                            body.append("<path d=\"").append(path).append("\" fill=\"").append(fill ? fillAttr() : "none")
                                    .append("\" fill-rule=\"").append(fillMode == 2 ? "nonzero" : "evenodd").append("\" ")
                                    .append(stroke ? strokeAttr() : "stroke=\"none\"").append(transform()).append("/>");
                        }
                        path.setLength(0);
                        break;
                    }
                    case 2: case 85:
                        shape(bezier(b, r + 28, u32(b, r + 24), type == 2, true), false);
                        break;
                    case 5: case 88:
                        shape("M" + num(curX) + " " + num(curY) + " " + bezier(b, r + 28, u32(b, r + 24), type == 5, false), false);
                        break;
                    case 3: case 86:
                        shape(points(b, r + 28, u32(b, r + 24), type == 3, true), true);
                        break;
                    case 4: case 87:
                        shape(points(b, r + 28, u32(b, r + 24), type == 4, false), false);
                        break;
                    case 6: case 89: {
                        final boolean wide = type == 6;
                        final int n = u32(b, r + 24);
                        final StringBuilder d = new StringBuilder("M" + num(curX) + " " + num(curY) + " ");
                        for (int i = 0; i < n; i++) {
                            final int o = r + 28 + i * (wide ? 8 : 4);
                            d.append("L").append(num(wide ? u32(b, o) : s16(b, o))).append(' ')
                                    .append(num(wide ? u32(b, o + 4) : s16(b, o + 2))).append(' ');
                            curX = wide ? u32(b, o) : s16(b, o);
                            curY = wide ? u32(b, o + 4) : s16(b, o + 2);
                        }
                        shape(d.toString(), false);
                        break;
                    }
                    case 7: case 8: case 90: case 91: {
                        final boolean wide = type == 7 || type == 8, close = type == 8 || type == 91;
                        final int polys = u32(b, r + 24);
                        int at = r + 32 + polys * 4;
                        final StringBuilder d = new StringBuilder();
                        for (int i = 0; i < polys; i++) {
                            final int n = u32(b, r + 32 + i * 4);
                            d.append(points(b, at, n, wide, close));
                            at += n * (wide ? 8 : 4);
                        }
                        shape(d.toString(), close);
                        break;
                    }
                    case 84: { // EXTTEXTOUTW
                        final int chars = u32(b, r + 44), off = u32(b, r + 48);
                        if (chars > 0 && chars < 4096 && r + off + chars * 2 <= b.length) {
                            text(new String(b, r + off, chars * 2, UTF16), u32(b, r + 36), u32(b, r + 40));
                        }
                        break;
                    }
                    case 81: { // STRETCHDIBITS
                        image(b, r + u32(b, r + 48), u32(b, r + 52), r + u32(b, r + 56), u32(b, r + 60),
                                u32(b, r + 24), u32(b, r + 28), u32(b, r + 72), u32(b, r + 76));
                        break;
                    }
                    case 76: case 77: { // BITBLT, STRETCHBLT
                        if (u32(b, r + 88) > 0) {
                            image(b, r + u32(b, r + 84), u32(b, r + 88), r + u32(b, r + 92), u32(b, r + 96),
                                    u32(b, r + 24), u32(b, r + 28), u32(b, r + 32), u32(b, r + 36));
                        }
                        break;
                    }
                    default:
                        break;
                }
                pos += size;
            }
            if (haveExt && extX != 0 && extY != 0) {
                haveWindow = true;
                winX = orgX;
                winY = orgY;
                winW = extX;
                winH = extY;
            }
        }

        static double[] xform(byte[] b, int at) {
            final double[] m = new double[6];
            for (int i = 0; i < 6; i++) {
                m[i] = Float.intBitsToFloat(u32(b, at + i * 4));
            }
            return m;
        }

        /** a followed by b, in the column-vector form SVG uses */
        static double[] multiply(double[] a, double[] b) {
            return new double[]{
                    a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
                    a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
                    a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5]};
        }

        void select(int handle) {
            if ((handle & 0x80000000) != 0) {
                switch (handle & 0x7FFFFFFF) {
                    case 0: brush = solid("#ffffff"); break;
                    case 1: brush = solid("#c0c0c0"); break;
                    case 2: brush = solid("#808080"); break;
                    case 3: brush = solid("#404040"); break;
                    case 4: brush = solid("#000000"); break;
                    case 5: brush = solid(null); break;
                    case 6: pen = pen("#ffffff"); break;
                    case 7: pen = pen("#000000"); break;
                    case 8: pen = pen(null); break;
                    default: break;
                }
                return;
            }
            final Object o = objects.get(handle);
            if (o instanceof Pen) {
                pen = (Pen) o;
            } else if (o instanceof Brush) {
                brush = (Brush) o;
            } else if (o instanceof Font) {
                font = (Font) o;
            }
        }

        static Brush solid(String c) {
            final Brush br = new Brush();
            br.color = c;
            return br;
        }

        static Pen pen(String c) {
            final Pen p = new Pen();
            p.color = c;
            return p;
        }

        // ---- WMF ----

        void wmf(byte[] b) {
            final int left = s16(b, 6), top = s16(b, 8), right = s16(b, 10), bottom = s16(b, 12);
            final int inch = Math.max(1, u16(b, 14));
            boundsX = left;
            boundsY = top;
            boundsW = right - left;
            boundsH = bottom - top;
            pxW = boundsW * 96.0 / inch;
            pxH = boundsH * 96.0 / inch;
            double orgX = 0, orgY = 0, extX = 0, extY = 0;
            int pos = 22 + u16(b, 22 + 2) * 2;
            while (pos + 6 <= b.length) {
                final int words = u32(b, pos), fn = u16(b, pos + 4);
                if (words < 3 || pos + words * 2 > b.length) {
                    break;
                }
                final int p = pos + 6;
                switch (fn) {
                    case 0x020B:
                        orgY = s16(b, p);
                        orgX = s16(b, p + 2);
                        break;
                    case 0x020C:
                        extY = s16(b, p);
                        extX = s16(b, p + 2);
                        break;
                    case 0x0106:
                        fillMode = u16(b, p);
                        break;
                    case 0x012E:
                        textAlign = u16(b, p);
                        break;
                    case 0x0209:
                        textColor = color(u32(b, p));
                        break;
                    case 0x02FA: {
                        final Pen pn = new Pen();
                        pn.style = u16(b, p) & 0xF;
                        pn.color = pn.style == 5 ? null : color(u32(b, p + 6));
                        pn.width = Math.max(1, s16(b, p + 2));
                        add(pn);
                        break;
                    }
                    case 0x02FC: {
                        final Brush br = new Brush();
                        br.color = u16(b, p) == 1 ? null : color(u32(b, p + 2));
                        add(br);
                        break;
                    }
                    case 0x02FB: {
                        final Font f = new Font();
                        f.height = s16(b, p);
                        f.escapement = s16(b, p + 4);
                        f.weight = s16(b, p + 8);
                        f.italic = (b[p + 10] & 0xFF) != 0;
                        f.underline = (b[p + 11] & 0xFF) != 0;
                        int end = p + 18;
                        final int limit = Math.min(pos + words * 2, p + 18 + 32);
                        while (end < limit && b[end] != 0) {
                            end++;
                        }
                        f.face = new String(b, p + 18, end - (p + 18), ANSI);
                        add(f);
                        break;
                    }
                    case 0x012D:
                        select(objects.containsKey(u16(b, p)) ? u16(b, p) : -1);
                        break;
                    case 0x01F0:
                        objects.remove(u16(b, p));
                        break;
                    case 0x0214:
                        curY = s16(b, p);
                        curX = s16(b, p + 2);
                        break;
                    case 0x0213:
                        line(s16(b, p + 2), s16(b, p));
                        break;
                    case 0x041B:
                        rect(s16(b, p + 6), s16(b, p + 4), s16(b, p + 2), s16(b, p));
                        break;
                    case 0x0418:
                        ellipse(s16(b, p + 6), s16(b, p + 4), s16(b, p + 2), s16(b, p));
                        break;
                    case 0x061C:
                        roundRect(s16(b, p + 10), s16(b, p + 8), s16(b, p + 6), s16(b, p + 4), s16(b, p + 2),
                                s16(b, p));
                        break;
                    case 0x0324:
                    case 0x0325:
                        shape(points(b, p + 2, u16(b, p), false, fn == 0x0324), fn == 0x0324);
                        break;
                    case 0x0538: {
                        final int polys = u16(b, p);
                        int at = p + 2 + polys * 2;
                        final StringBuilder d = new StringBuilder();
                        for (int i = 0; i < polys; i++) {
                            final int n = u16(b, p + 2 + i * 2);
                            d.append(points(b, at, n, false, true));
                            at += n * 4;
                        }
                        shape(d.toString(), true);
                        break;
                    }
                    case 0x0521: {
                        final int n = u16(b, p);
                        final int at = p + 2 + (n + 1 & ~1);
                        text(new String(b, p + 2, n, ANSI), s16(b, at + 2), s16(b, at));
                        break;
                    }
                    case 0x0A32: {
                        final int n = u16(b, p + 4), options = u16(b, p + 6);
                        final int at = p + 8 + ((options & 6) != 0 ? 8 : 0);
                        if (n > 0 && at + n <= b.length) {
                            text(new String(b, at, n, ANSI), s16(b, p + 2), s16(b, p));
                        }
                        break;
                    }
                    case 0x0F43: { // STRETCHDIB
                        final int dib = p + 22;
                        image(b, dib, dibHeader(b, dib), dib + dibHeader(b, dib), pos + words * 2 - dib - dibHeader(b, dib),
                                s16(b, p + 20), s16(b, p + 18), s16(b, p + 16), s16(b, p + 14));
                        break;
                    }
                    case 0x0B41: { // DIBSTRETCHBLT
                        final int dib = p + 20;
                        image(b, dib, dibHeader(b, dib), dib + dibHeader(b, dib), pos + words * 2 - dib - dibHeader(b, dib),
                                s16(b, p + 18), s16(b, p + 16), s16(b, p + 14), s16(b, p + 12));
                        break;
                    }
                    default:
                        break;
                }
                pos += words * 2;
            }
            if (extX != 0 && extY != 0) {
                haveWindow = true;
                winX = orgX;
                winY = orgY;
                winW = extX;
                winH = extY;
            }
        }

        /** The size of a DIB's header plus its colour table */
        static int dibHeader(byte[] b, int at) {
            final int size = u32(b, at);
            int colors = u32(b, at + 32);
            final int bits = u16(b, at + 14);
            if (colors == 0 && bits <= 8) {
                colors = 1 << bits;
            }
            return size + colors * 4 + (u32(b, at + 16) == 3 && size == 40 ? 12 : 0);
        }

        /** WMF objects take the lowest free slot */
        void add(Object o) {
            int i = 0;
            while (objects.containsKey(i)) {
                i++;
            }
            objects.put(i, o);
        }
    }

    /** A device independent bitmap as a data URI: a BMP file, or the JPEG or PNG a DIB can hold */
    private static String dib(byte[] b, int bmi, int bmiLen, int bits, int bitsLen) {
        if (bmi < 0 || bmiLen < 12 || bits < 0 || bitsLen <= 0 || bmi + bmiLen > b.length
                || bits + bitsLen > b.length) {
            return null;
        }
        final int compression = bmiLen >= 20 ? u32(b, bmi + 16) : 0;
        if (compression == 4 || compression == 5) {
            return "data:image/" + (compression == 4 ? "jpeg" : "png") + ";base64,"
                    + Base64.encodeToString(b, bits, bitsLen, Base64.NO_WRAP);
        }
        final byte[] bmp = new byte[14 + bmiLen + bitsLen];
        final int total = bmp.length, offset = 14 + bmiLen;
        bmp[0] = 'B';
        bmp[1] = 'M';
        for (int i = 0; i < 4; i++) {
            bmp[2 + i] = (byte) (total >> 8 * i);
            bmp[10 + i] = (byte) (offset >> 8 * i);
        }
        System.arraycopy(b, bmi, bmp, 14, bmiLen);
        System.arraycopy(b, bits, bmp, offset, bitsLen);
        return "data:image/bmp;base64," + Base64.encodeToString(bmp, Base64.NO_WRAP);
    }
}
