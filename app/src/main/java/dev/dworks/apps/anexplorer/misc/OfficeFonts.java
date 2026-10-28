package dev.dworks.apps.anexplorer.misc;

import android.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Office fonts that Android doesn't have, and the free look-alikes bundled with the app to stand in
 * for them. Carlito and Caladea have the same letter widths as Calibri and Cambria, so lines break
 * where Office broke them; Figtree only resembles Avenir.
 */
final class OfficeFonts {

    private static final class Stand {
        final String match;
        final String alias;
        final String[] files;
        final int[] weights;
        final boolean[] italics;

        Stand(String match, String alias, String[] files, int[] weights, boolean[] italics) {
            this.match = match;
            this.alias = alias;
            this.files = files;
            this.weights = weights;
            this.italics = italics;
        }
    }

    private static final boolean[] STYLES = {false, false, true, true};
    private static final int[] WEIGHTS = {400, 700, 400, 700};

    private static final Stand[] STANDS = {
            new Stand("avenir", "Avenir Stand-in", new String[]{"figtree"}, new int[]{0}, new boolean[]{false}),
            new Stand("calibri", "Calibri Stand-in", styled("carlito"), WEIGHTS, STYLES),
            new Stand("cambria", "Cambria Stand-in", styled("caladea"), WEIGHTS, STYLES),
    };

    private OfficeFonts() {
    }

    private static String[] styled(String family) {
        return new String[]{family + "-regular", family + "-bold", family + "-italic", family + "-bolditalic"};
    }

    /** @return the font-family value for a face as the file names it, with the stand-in after it if there is one */
    static String family(String face, String generic) {
        final String clean = face.replace("'", "").replace("\"", "");
        final String lower = clean.toLowerCase(Locale.ROOT);
        final StringBuilder out = new StringBuilder("'").append(clean).append("',");
        for (Stand s : STANDS) {
            if (lower.contains(s.match)) {
                out.append('\'').append(s.alias).append("',");
                break;
            }
        }
        return out.append(generic).toString();
    }

    /** @return the style that defines the stand-ins the page uses, or "" if it uses none */
    static String faces(CharSequence html) {
        final StringBuilder out = new StringBuilder();
        for (Stand s : STANDS) {
            if (html.toString().contains("'" + s.alias + "'")) {
                for (int i = 0; i < s.files.length; i++) {
                    final String data = data(s.files[i]);
                    if (data == null) {
                        continue;
                    }
                    out.append("@font-face{font-family:'").append(s.alias).append("';")
                            .append(s.weights[i] == 0 ? "font-weight:300 900;" : "font-weight:" + s.weights[i] + ";")
                            .append(s.italics[i] ? "font-style:italic;" : "")
                            .append("src:url(data:font/woff2;base64,").append(data).append(") format('woff2')}");
                }
            }
        }
        return out.length() == 0 ? "" : "<style>" + out + "</style>";
    }

    private static String data(String name) {
        try (InputStream in = OfficeFonts.class.getResourceAsStream("/fonts/" + name + ".woff2")) {
            return in == null ? null : Base64.encodeToString(PreviewFormatter.readAll(in, 256 * 1024), Base64.NO_WRAP);
        } catch (IOException e) {
            return null;
        }
    }
}
