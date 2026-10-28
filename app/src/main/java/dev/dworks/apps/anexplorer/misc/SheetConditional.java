package dev.dworks.apps.anexplorer.misc;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The conditional formatting of one worksheet: colour scales, data bars and the rules that pick a
 * differential format (highlight cells, top and bottom values, text, duplicates). Rules that need a
 * formula's result, and icon sets, are left out.
 */
final class SheetConditional {

    private static final class Rule {
        String type, operator, text;
        int dxf = -1, priority = Integer.MAX_VALUE, rank = 10;
        boolean percent, bottom, above = true, equal;
        final List<int[]> ranges = new ArrayList<>();
        final List<String[]> values = new ArrayList<>();
        final List<String> colors = new ArrayList<>();
        final List<String> formulas = new ArrayList<>();
    }

    private final List<Rule> rules = new ArrayList<>();
    private List<int[]> ranges = new ArrayList<>();
    private Rule rule;
    private boolean inFormula;

    void start(XmlPullParser p, String[] theme) {
        switch (p.getName()) {
            case "conditionalFormatting":
                ranges = new ArrayList<>();
                final String sqref = p.getAttributeValue(null, "sqref");
                for (String part : sqref == null ? new String[0] : sqref.trim().split("\\s+")) {
                    final int[] a = BinaryOffice.cellRef(part.indexOf(':') > 0 ? part.substring(0, part.indexOf(':')) : part);
                    final int[] b = part.indexOf(':') > 0 ? BinaryOffice.cellRef(part.substring(part.indexOf(':') + 1)) : a;
                    ranges.add(new int[]{a[0], b[0], a[1], b[1]});
                }
                break;
            case "cfRule":
                rule = new Rule();
                rule.type = p.getAttributeValue(null, "type");
                rule.operator = p.getAttributeValue(null, "operator");
                rule.text = p.getAttributeValue(null, "text");
                rule.dxf = attr(p, "dxfId", -1);
                rule.priority = attr(p, "priority", Integer.MAX_VALUE);
                rule.rank = attr(p, "rank", 10);
                rule.percent = "1".equals(p.getAttributeValue(null, "percent"));
                rule.bottom = "1".equals(p.getAttributeValue(null, "bottom"));
                rule.above = !"0".equals(p.getAttributeValue(null, "aboveAverage"));
                rule.equal = "1".equals(p.getAttributeValue(null, "equalAverage"));
                rule.ranges.addAll(ranges);
                break;
            case "cfvo":
                if (rule != null) {
                    rule.values.add(new String[]{p.getAttributeValue(null, "type"), p.getAttributeValue(null, "val")});
                }
                break;
            case "color":
                if (rule != null) {
                    rule.colors.add(BinaryOffice.xlsxColor(p, theme));
                }
                break;
            case "formula":
                inFormula = true;
                break;
            default:
                break;
        }
    }

    void text(String text) {
        if (inFormula && rule != null) {
            rule.formulas.add(text.trim());
        }
    }

    void end(String tag) {
        if ("formula".equals(tag)) {
            inFormula = false;
        } else if ("cfRule".equals(tag) && rule != null) {
            rules.add(rule);
            rule = null;
        }
    }

    boolean isEmpty() {
        return rules.isEmpty();
    }

    /** Adds the CSS the rules give to the cells of the sheet, the highest priority last so it wins */
    void apply(BinaryOffice.Sheet sheet, Map<Long, Double> numbers, List<String> dxfs) {
        final Map<Integer, Integer> rowAt = new HashMap<>();
        for (int i = 0; i < sheet.rowNumbers.size(); i++) {
            rowAt.put(sheet.rowNumbers.get(i) - 1, i);
        }
        final List<Rule> order = new ArrayList<>(rules);
        Collections.sort(order, (a, b) -> Integer.compare(b.priority, a.priority));
        for (Rule r : order) {
            final List<long[]> cells = new ArrayList<>();
            final List<Double> values = new ArrayList<>();
            final Map<String, Integer> seen = new HashMap<>();
            for (int[] range : r.ranges) {
                for (int row = range[0]; row <= range[1] && row < range[0] + 5000; row++) {
                    if (!rowAt.containsKey(row)) {
                        continue;
                    }
                    for (int col = range[2]; col <= range[3] && col < range[2] + 256; col++) {
                        cells.add(new long[]{row, col});
                        final List<String> line = sheet.rows.get(rowAt.get(row));
                        if (col < line.size() && !line.get(col).isEmpty()) {
                            seen.merge(line.get(col), 1, Integer::sum);
                        }
                        final Double v = numbers.get(key(row, col));
                        if (v != null) {
                            values.add(v);
                        }
                    }
                }
            }
            for (long[] cell : cells) {
                final int row = rowAt.get((int) cell[0]);
                final int col = (int) cell[1];
                final List<String> styles = sheet.styles.get(row);
                if (col >= styles.size()) {
                    continue;
                }
                final Double v = numbers.get(key((int) cell[0], col));
                final String css = css(r, v, sheet.rows.get(row).get(col), values, seen, dxfs);
                if (css != null) {
                    styles.set(col, styles.get(col) + css);
                }
            }
        }
    }

    private static String css(Rule r, Double v, String shown, List<Double> values, Map<String, Integer> seen,
                              List<String> dxfs) {
        final String type = r.type == null ? "" : r.type;
        switch (type) {
            case "colorScale": {
                if (v == null || values.isEmpty() || r.colors.size() < 2 || r.colors.contains(null)
                        || r.values.size() != r.colors.size()) {
                    return null;
                }
                final double[] at = new double[r.values.size()];
                for (int i = 0; i < at.length; i++) {
                    at[i] = threshold(r.values.get(i), values);
                }
                int i = 0;
                while (i < at.length - 2 && v > at[i + 1]) {
                    i++;
                }
                final double span = at[i + 1] - at[i];
                final double f = span <= 0 ? 0 : Math.max(0, Math.min(1, (v - at[i]) / span));
                return "background:" + mix(r.colors.get(i), r.colors.get(i + 1), f) + ";";
            }
            case "dataBar": {
                if (v == null || values.isEmpty() || r.colors.isEmpty() || r.colors.get(0) == null) {
                    return null;
                }
                final double lo = r.values.size() > 0 ? threshold(r.values.get(0), values) : Collections.min(values);
                final double hi = r.values.size() > 1 ? threshold(r.values.get(1), values) : Collections.max(values);
                final double f = hi <= lo ? 1 : Math.max(0, Math.min(1, (v - lo) / (hi - lo)));
                final int percent = (int) Math.round(10 + 80 * f);
                return "background:linear-gradient(90deg," + r.colors.get(0) + " " + percent
                        + "%,transparent " + percent + "%);";
            }
            default:
                break;
        }
        if (r.dxf < 0 || r.dxf >= dxfs.size() || !matches(r, type, v, shown, values, seen)) {
            return null;
        }
        return dxfs.get(r.dxf);
    }

    private static boolean matches(Rule r, String type, Double v, String shown, List<Double> values,
                                   Map<String, Integer> seen) {
        final String text = shown == null ? "" : shown;
        final String needle = r.text == null ? "" : r.text.toLowerCase(Locale.ROOT);
        switch (type) {
            case "cellIs": {
                if (r.formulas.isEmpty() || r.operator == null) {
                    return false;
                }
                final Double a = number(r.formulas.get(0));
                final Double b = r.formulas.size() > 1 ? number(r.formulas.get(1)) : null;
                if (a == null) {
                    final String quoted = literal(r.formulas.get(0));
                    return quoted != null && (r.operator.equals("equal") ? text.equalsIgnoreCase(quoted)
                            : r.operator.equals("notEqual") && !text.equalsIgnoreCase(quoted));
                }
                if (v == null) {
                    return false;
                }
                switch (r.operator) {
                    case "lessThan":
                        return v < a;
                    case "lessThanOrEqual":
                        return v <= a;
                    case "greaterThan":
                        return v > a;
                    case "greaterThanOrEqual":
                        return v >= a;
                    case "equal":
                        return v.equals(a);
                    case "notEqual":
                        return !v.equals(a);
                    case "between":
                        return b != null && v >= Math.min(a, b) && v <= Math.max(a, b);
                    case "notBetween":
                        return b != null && (v < Math.min(a, b) || v > Math.max(a, b));
                    default:
                        return false;
                }
            }
            case "top10": {
                if (v == null || values.isEmpty()) {
                    return false;
                }
                final List<Double> sorted = new ArrayList<>(values);
                Collections.sort(sorted);
                if (!r.bottom) {
                    Collections.reverse(sorted);
                }
                final int n = r.percent ? (int) Math.max(1, Math.floor(sorted.size() * r.rank / 100.0))
                        : Math.min(r.rank, sorted.size());
                return n > 0 && (r.bottom ? v <= sorted.get(n - 1) : v >= sorted.get(n - 1));
            }
            case "aboveAverage": {
                if (v == null || values.isEmpty()) {
                    return false;
                }
                double sum = 0;
                for (double d : values) {
                    sum += d;
                }
                final double mean = sum / values.size();
                return r.above ? (r.equal ? v >= mean : v > mean) : (r.equal ? v <= mean : v < mean);
            }
            case "containsText":
                return !needle.isEmpty() && text.toLowerCase(Locale.ROOT).contains(needle);
            case "notContainsText":
                return !needle.isEmpty() && !text.toLowerCase(Locale.ROOT).contains(needle);
            case "beginsWith":
                return !needle.isEmpty() && text.toLowerCase(Locale.ROOT).startsWith(needle);
            case "endsWith":
                return !needle.isEmpty() && text.toLowerCase(Locale.ROOT).endsWith(needle);
            case "duplicateValues":
                return !text.isEmpty() && seen.getOrDefault(text, 0) > 1;
            case "uniqueValues":
                return !text.isEmpty() && seen.getOrDefault(text, 0) == 1;
            default:
                return false;
        }
    }

    private static double threshold(String[] cfvo, List<Double> values) {
        final double min = Collections.min(values), max = Collections.max(values);
        final String type = cfvo[0] == null ? "num" : cfvo[0];
        final Double given = cfvo[1] == null ? null : number(cfvo[1]);
        switch (type) {
            case "min":
                return min;
            case "max":
                return max;
            case "percent":
                return min + (max - min) * (given == null ? 0 : given) / 100;
            case "percentile": {
                final List<Double> sorted = new ArrayList<>(values);
                Collections.sort(sorted);
                final double at = (sorted.size() - 1) * (given == null ? 0 : given) / 100;
                final int lo = (int) Math.floor(at), hi = (int) Math.ceil(at);
                return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (at - lo);
            }
            default:
                return given == null ? min : given;
        }
    }

    private static String mix(String a, String b, double f) {
        final int x = Integer.parseInt(a.substring(1), 16), y = Integer.parseInt(b.substring(1), 16);
        final StringBuilder out = new StringBuilder("#");
        for (int shift = 16; shift >= 0; shift -= 8) {
            final double c = ((x >> shift) & 0xFF) * (1 - f) + ((y >> shift) & 0xFF) * f;
            out.append(String.format(Locale.US, "%02x", (int) Math.round(c)));
        }
        return out.toString();
    }

    private static Double number(String text) {
        try {
            return Double.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String literal(String formula) {
        return formula.length() >= 2 && formula.startsWith("\"") && formula.endsWith("\"")
                ? formula.substring(1, formula.length() - 1) : null;
    }

    private static int attr(XmlPullParser p, String name, int fallback) {
        try {
            final String v = p.getAttributeValue(null, name);
            return v == null ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static long key(int row, int column) {
        return (long) row << 32 | column;
    }
}
