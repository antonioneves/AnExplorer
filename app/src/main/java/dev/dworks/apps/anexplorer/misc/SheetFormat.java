package dev.dworks.apps.anexplorer.misc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Number formats and colours of Excel cells: the built-in and custom format codes (numbers,
 * percentages, thousands separators, dates and times) and the default colour palette.
 */
final class SheetFormat {

    private static final String[] PALETTE = {"000000", "ffffff", "ff0000", "00ff00", "0000ff", "ffff00",
            "ff00ff", "00ffff", "800000", "008000", "000080", "808000", "800080", "008080", "c0c0c0", "808080",
            "9999ff", "993366", "ffffcc", "ccffff", "660066", "ff8080", "0066cc", "ccccff", "000080", "ff00ff",
            "ffff00", "00ffff", "800080", "800000", "008080", "0000ff", "00ccff", "ccffff", "ccffcc", "ffff99",
            "99ccff", "ff99cc", "cc99ff", "ffcc99", "3366ff", "33cccc", "99cc00", "ffcc00", "ff9900", "ff6600",
            "666699", "969696", "003366", "339966", "003300", "333300", "993300", "993366", "333399", "333333"};
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December"};
    private static final String[] DAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
            "Saturday"};

    private SheetFormat() {
    }

    /** @return the colour for a BIFF8 palette index, or null for the automatic colours */
    static String color(int index, String[] custom) {
        if (index >= 8 && index < 8 + PALETTE.length) {
            if (custom != null && index - 8 < custom.length && custom[index - 8] != null) {
                return custom[index - 8];
            }
            return "#" + PALETTE[index - 8];
        }
        if (index >= 0 && index < 8) {
            return "#" + PALETTE[index];
        }
        return null;
    }

    /** @return the format code of a built-in format id, or null when the id is not built in */
    static String builtIn(int id) {
        switch (id) {
            case 0:
                return "General";
            case 1:
                return "0";
            case 2:
                return "0.00";
            case 3:
                return "#,##0";
            case 4:
                return "#,##0.00";
            case 9:
                return "0%";
            case 10:
                return "0.00%";
            case 11:
                return "0.00E+00";
            case 14:
                return "m/d/yyyy";
            case 15:
                return "d-mmm-yy";
            case 16:
                return "d-mmm";
            case 17:
                return "mmm-yy";
            case 18:
                return "h:mm AM/PM";
            case 19:
                return "h:mm:ss AM/PM";
            case 20:
                return "h:mm";
            case 21:
                return "h:mm:ss";
            case 22:
                return "m/d/yyyy h:mm";
            case 37:
                return "#,##0;(#,##0)";
            case 38:
                return "#,##0;(#,##0)";
            case 39:
                return "#,##0.00;(#,##0.00)";
            case 40:
                return "#,##0.00;(#,##0.00)";
            case 45:
                return "mm:ss";
            case 46:
                return "[h]:mm:ss";
            case 47:
                return "mm:ss.0";
            case 49:
                return "@";
            default:
                return null;
        }
    }

    /** @return the part of the format code that applies to the value, with its sign handled */
    private static String section(String code, double value) {
        final java.util.List<String> parts = new java.util.ArrayList<>();
        final StringBuilder part = new StringBuilder();
        boolean quoted = false;
        boolean bracket = false;
        for (int i = 0; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '[') {
                bracket = true;
            } else if (!quoted && c == ']') {
                bracket = false;
            }
            if (c == ';' && !quoted && !bracket) {
                parts.add(part.toString());
                part.setLength(0);
            } else {
                part.append(c);
            }
        }
        parts.add(part.toString());
        if (value < 0 && parts.size() > 1) {
            return parts.get(1);
        }
        if (value == 0 && parts.size() > 2) {
            return parts.get(2);
        }
        return parts.get(0);
    }

    static boolean isDate(String code) {
        boolean quoted = false;
        boolean bracket = false;
        for (int i = 0; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (quoted) {
                continue;
            } else if (c == '[') {
                bracket = true;
            } else if (c == ']') {
                bracket = false;
            } else if (c == '\\' || c == '_' || c == '*') {
                i++;
            } else if (!bracket && "dmyhsDMYHS".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** @return the value as text under that format code */
    static String format(double value, String code, boolean date1904) {
        if (code == null || code.isEmpty() || code.equalsIgnoreCase("General") || code.equals("@")) {
            return general(value);
        }
        final String section = section(code, value);
        if (isDate(section)) {
            return date(value, section, date1904);
        }
        return number(value, section, value < 0 && code.indexOf(';') >= 0);
    }

    /** @return the value in scientific notation, as a code such as 0.00E+00 asks: its mantissa decimals and exponent digits */
    private static String scientific(double value, String code) {
        final int e = Math.max(code.indexOf('E'), code.indexOf('e'));
        final int dot = code.indexOf('.');
        int decimals = 0;
        for (int i = dot < 0 ? e : dot + 1; dot >= 0 && i < e && "0#?".indexOf(code.charAt(i)) >= 0; i++) {
            decimals++;
        }
        int width = 0;
        for (int i = e + 2; i < code.length() && code.charAt(i) == '0'; i++) {
            width++;
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return general(value);
        }
        int exp = value == 0 ? 0 : (int) Math.floor(Math.log10(Math.abs(value)));
        double mantissa = value == 0 ? 0 : value / Math.pow(10, exp);
        if (Math.abs(Double.parseDouble(String.format(java.util.Locale.US, "%." + decimals + "f", mantissa))) >= 10) {
            mantissa /= 10;
            exp++;
        }
        final String digits = String.format(java.util.Locale.US, "%." + decimals + "f", mantissa);
        final String number = String.format(java.util.Locale.US, "%0" + Math.max(width, 1) + "d", Math.abs(exp));
        return digits + code.charAt(e) + (exp < 0 ? "-" : e + 1 < code.length() && code.charAt(e + 1) == '+' ? "+" : "") + number;
    }

    static String general(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "#NUM";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return new BigDecimal(value).round(new MathContext(11)).stripTrailingZeros().toPlainString();
    }

    private static String number(double value, String code, boolean ownSign) {
        final StringBuilder prefix = new StringBuilder();
        final StringBuilder suffix = new StringBuilder();
        final StringBuilder digits = new StringBuilder();
        boolean percent = false;
        boolean quoted = false;
        boolean bracket = false;
        boolean seenDigit = false;
        for (int i = 0; i < code.length(); i++) {
            final char c = code.charAt(i);
            final StringBuilder side = seenDigit && "0#?.,".indexOf(c) < 0 ? suffix : prefix;
            if (c == '"') {
                quoted = !quoted;
            } else if (quoted) {
                side.append(c);
            } else if (c == '[') {
                bracket = true;
                final int end = code.indexOf(']', i);
                if (end > i && code.charAt(i + 1) == '$') {
                    final String currency = code.substring(i + 2, end);
                    final int dash = currency.indexOf('-');
                    side.append(dash >= 0 ? currency.substring(0, dash) : currency);
                }
                i = end > i ? end : i;
                bracket = false;
            } else if (bracket) {
                continue;
            } else if (c == '\\') {
                if (i + 1 < code.length()) {
                    side.append(code.charAt(++i));
                }
            } else if (c == '_' || c == '*') {
                i++;
                if (c == '_') {
                    side.append(' ');
                }
            } else if ("0#?.,".indexOf(c) >= 0) {
                seenDigit = true;
                digits.append(c);
            } else if (c == '%') {
                percent = true;
                side.append(c);
            } else if (c == 'E' || c == 'e') {
                return scientific(value, code);
            } else {
                side.append(c);
            }
        }
        if (digits.length() == 0) {
            return prefix.toString() + suffix;
        }
        final String pattern = digits.toString();
        final int dot = pattern.indexOf('.');
        final int decimals = dot < 0 ? 0 : pattern.length() - dot - 1 - countOf(pattern.substring(dot + 1), ',');
        final boolean thousands = (dot < 0 ? pattern : pattern.substring(0, dot)).indexOf(',') >= 0;
        double shown = ownSign ? Math.abs(value) : value;
        if (percent) {
            shown *= 100;
        }
        BigDecimal rounded = new BigDecimal(shown).setScale(decimals, RoundingMode.HALF_UP);
        final boolean negative = rounded.signum() < 0;
        String text = rounded.abs().toPlainString();
        if (decimals > 0 && dot >= 0) {
            int optional = 0;
            for (int i = pattern.length() - 1; i > dot && "#".indexOf(pattern.charAt(i)) >= 0; i--) {
                optional++;
            }
            for (; optional > 0 && text.endsWith("0"); optional--) {
                text = text.substring(0, text.length() - 1);
            }
            if (text.endsWith(".")) {
                text = text.substring(0, text.length() - 1);
            }
        }
        final int point = text.indexOf('.');
        String whole = point < 0 ? text : text.substring(0, point);
        final String fraction = point < 0 ? "" : text.substring(point);
        final String intPattern = dot < 0 ? pattern : pattern.substring(0, dot);
        int minimum = 0;
        for (int i = 0; i < intPattern.length(); i++) {
            if (intPattern.charAt(i) == '0') {
                minimum++;
            }
        }
        if (whole.equals("0") && minimum == 0) {
            whole = "";
        }
        while (whole.length() < minimum) {
            whole = "0" + whole;
        }
        if (thousands) {
            final StringBuilder grouped = new StringBuilder();
            for (int i = 0; i < whole.length(); i++) {
                if (i > 0 && (whole.length() - i) % 3 == 0) {
                    grouped.append(',');
                }
                grouped.append(whole.charAt(i));
            }
            whole = grouped.toString();
        }
        return (negative && !ownSign ? "-" : "") + prefix + whole + fraction + suffix;
    }

    private static int countOf(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static String date(double serial, String code, boolean date1904) {
        if (serial < 0 || serial > 2958465) {
            return general(serial);
        }
        final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ENGLISH);
        calendar.clear();
        // Excel's 1900 system counts a 29 February 1900 that never existed
        final long epochDays = date1904 ? 24107 : serial < 61 ? 25568 : 25569;
        calendar.setTimeInMillis(Math.round(serial * 86400000d) - epochDays * 86400000L);
        final int hour = calendar.get(Calendar.HOUR_OF_DAY);
        final boolean twelve = code.toLowerCase(Locale.ROOT).contains("am/pm") || code.toLowerCase(Locale.ROOT).contains("a/p");
        final StringBuilder out = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                out.append(c);
                continue;
            }
            final char l = Character.toLowerCase(c);
            if (c == '[') {
                final int end = code.indexOf(']', i);
                if (end > i) {
                    final String inner = code.substring(i + 1, end).toLowerCase(Locale.ROOT);
                    if (inner.startsWith("h")) {
                        out.append((long) Math.floor(serial * 24));
                    } else if (inner.startsWith("m")) {
                        out.append((long) Math.floor(serial * 1440));
                    } else if (inner.startsWith("s")) {
                        out.append((long) Math.floor(serial * 86400));
                    }
                    i = end;
                    continue;
                }
            }
            if (c == '\\' && i + 1 < code.length()) {
                out.append(code.charAt(++i));
                continue;
            }
            if (c == '_' || c == '*') {
                i++;
                continue;
            }
            if (l == 'y' || l == 'd' || l == 'h' || l == 's' || l == 'm') {
                int run = 1;
                while (i + run < code.length() && Character.toLowerCase(code.charAt(i + run)) == l) {
                    run++;
                }
                i += run - 1;
                switch (l) {
                    case 'y':
                        out.append(run <= 2 ? String.format("%02d", calendar.get(Calendar.YEAR) % 100)
                                : String.valueOf(calendar.get(Calendar.YEAR)));
                        break;
                    case 'd':
                        if (run <= 2) {
                            out.append(run == 1 ? String.valueOf(calendar.get(Calendar.DAY_OF_MONTH))
                                    : String.format("%02d", calendar.get(Calendar.DAY_OF_MONTH)));
                        } else {
                            final String day = DAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1];
                            out.append(run == 3 ? day.substring(0, 3) : day);
                        }
                        break;
                    case 'h': {
                        final int h = twelve ? (hour % 12 == 0 ? 12 : hour % 12) : hour;
                        out.append(run == 1 ? String.valueOf(h) : String.format("%02d", h));
                        break;
                    }
                    case 's': {
                        final int s = calendar.get(Calendar.SECOND);
                        out.append(run == 1 ? String.valueOf(s) : String.format("%02d", s));
                        break;
                    }
                    default:
                        if (minuteContext(code, i - run + 1, i)) {
                            final int m = calendar.get(Calendar.MINUTE);
                            out.append(run == 1 ? String.valueOf(m) : String.format("%02d", m));
                        } else {
                            final int month = calendar.get(Calendar.MONTH);
                            if (run == 1) {
                                out.append(month + 1);
                            } else if (run == 2) {
                                out.append(String.format("%02d", month + 1));
                            } else if (run == 3) {
                                out.append(MONTHS[month], 0, 3);
                            } else if (run == 4) {
                                out.append(MONTHS[month]);
                            } else {
                                out.append(MONTHS[month], 0, 1);
                            }
                        }
                        break;
                }
                continue;
            }
            if (code.regionMatches(true, i, "AM/PM", 0, 5)) {
                out.append(hour < 12 ? "AM" : "PM");
                i += 4;
            } else if (code.regionMatches(true, i, "A/P", 0, 3)) {
                out.append(hour < 12 ? "A" : "P");
                i += 2;
            } else if (c == '.' && i + 1 < code.length() && code.charAt(i + 1) == '0') {
                int zeros = 0;
                while (i + 1 + zeros < code.length() && code.charAt(i + 1 + zeros) == '0') {
                    zeros++;
                }
                final String ms = String.format("%03d", calendar.get(Calendar.MILLISECOND));
                out.append('.').append(ms, 0, Math.min(3, zeros));
                i += zeros;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** An "m" is a minute when it follows an hour or precedes a second, and a month otherwise */
    private static boolean minuteContext(String code, int from, int to) {
        for (int i = from - 1; i >= 0; i--) {
            final char c = Character.toLowerCase(code.charAt(i));
            if (c == 'h') {
                return true;
            }
            if (c == 'd' || c == 'y') {
                return false;
            }
        }
        for (int i = to + 1; i < code.length(); i++) {
            final char c = Character.toLowerCase(code.charAt(i));
            if (c == 's') {
                return true;
            }
            if (c == 'd' || c == 'y') {
                return false;
            }
        }
        return false;
    }
}
