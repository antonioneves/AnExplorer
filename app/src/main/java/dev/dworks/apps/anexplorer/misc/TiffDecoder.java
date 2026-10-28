package dev.dworks.apps.anexplorer.misc;

import android.graphics.Bitmap;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodes the first page of a TIFF, which Android's own decoders don't read. It handles the
 * common baseline files: strips with no compression, PackBits, LZW or Deflate, in black and white,
 * grey, palette, RGB or CMYK, at 1 to 16 bits. Tiled, planar and JPEG-compressed files are
 * refused. Large images are sampled down while decoding so they fit in memory.
 */
public final class TiffDecoder {

    private static final int TAG_WIDTH = 256;
    private static final int TAG_HEIGHT = 257;
    private static final int TAG_BITS = 258;
    private static final int TAG_COMPRESSION = 259;
    private static final int TAG_PHOTOMETRIC = 262;
    private static final int TAG_STRIP_OFFSETS = 273;
    private static final int TAG_SAMPLES = 277;
    private static final int TAG_ROWS_PER_STRIP = 278;
    private static final int TAG_STRIP_COUNTS = 279;
    private static final int TAG_PLANAR = 284;
    private static final int TAG_PREDICTOR = 317;
    private static final int TAG_COLOR_MAP = 320;
    private static final int TAG_TILE_WIDTH = 322;

    private final byte[] b;
    private boolean little;

    private TiffDecoder(byte[] bytes) {
        b = bytes;
    }

    /** @return whether the bytes start like a TIFF file */
    public static boolean isTiff(byte[] bytes) {
        return bytes.length > 8 && (bytes[0] == 'I' && bytes[1] == 'I' && bytes[2] == 42 && bytes[3] == 0
                || bytes[0] == 'M' && bytes[1] == 'M' && bytes[2] == 0 && bytes[3] == 42);
    }

    /** @param maxSide the longest side of the result; larger images are sampled down */
    public static Bitmap decode(byte[] bytes, int maxSide) throws IOException {
        return new TiffDecoder(bytes).decodeFirstPage(maxSide);
    }

    private int u16(int at) throws IOException {
        if (at < 0 || at + 2 > b.length) {
            throw new IOException("Truncated TIFF");
        }
        return little ? (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 : (b[at] & 0xFF) << 8 | (b[at + 1] & 0xFF);
    }

    private long u32(int at) throws IOException {
        final long first = u16(at);
        final long second = u16(at + 2);
        return little ? first | second << 16 : first << 16 | second;
    }

    /** Reads the directory into tag -> values, keeping only the numbers */
    private Map<Integer, long[]> directory(int at) throws IOException {
        final Map<Integer, long[]> tags = new HashMap<>();
        final int count = u16(at);
        for (int i = 0; i < count; i++) {
            final int entry = at + 2 + i * 12;
            final int tag = u16(entry);
            final int type = u16(entry + 2);
            final long n = u32(entry + 4);
            final int size = type == 3 ? 2 : type == 4 ? 4 : type == 1 || type == 7 ? 1 : 0;
            if (size == 0 || n > 1_000_000) {
                continue;
            }
            final long where = size * n <= 4 ? entry + 8 : u32(entry + 8);
            final long[] values = new long[(int) n];
            for (int j = 0; j < n; j++) {
                final int from = (int) where + j * size;
                values[j] = size == 1 ? b[from] & 0xFF : size == 2 ? u16(from) : u32(from);
            }
            tags.put(tag, values);
        }
        return tags;
    }

    private static long one(Map<Integer, long[]> tags, int tag, long fallback) {
        final long[] values = tags.get(tag);
        return values == null || values.length == 0 ? fallback : values[0];
    }

    private Bitmap decodeFirstPage(int maxSide) throws IOException {
        if (!isTiff(b)) {
            throw new IOException("Not a TIFF");
        }
        little = b[0] == 'I';
        final Map<Integer, long[]> tags = directory((int) u32(4));
        final int width = (int) one(tags, TAG_WIDTH, 0);
        final int height = (int) one(tags, TAG_HEIGHT, 0);
        final int bits = (int) one(tags, TAG_BITS, 1);
        final int compression = (int) one(tags, TAG_COMPRESSION, 1);
        final int photometric = (int) one(tags, TAG_PHOTOMETRIC, 1);
        final int samples = (int) one(tags, TAG_SAMPLES, 1);
        final int predictor = (int) one(tags, TAG_PREDICTOR, 1);
        final long[] offsets = tags.get(TAG_STRIP_OFFSETS);
        if (width <= 0 || height <= 0 || offsets == null || tags.containsKey(TAG_TILE_WIDTH)
                || one(tags, TAG_PLANAR, 1) != 1 || samples < 1 || samples > 5
                || bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 16) {
            throw new IOException("TIFF layout not supported");
        }
        if (compression != 1 && compression != 5 && compression != 8 && compression != 32946
                && compression != 32773) {
            throw new IOException("TIFF compression not supported");
        }
        final long[] counts = tags.get(TAG_STRIP_COUNTS);
        final long[] map = tags.get(TAG_COLOR_MAP);
        if (photometric == 3 && (map == null || map.length < 3 << bits)) {
            throw new IOException("Missing palette");
        }
        final long rowsPerStrip = one(tags, TAG_ROWS_PER_STRIP, height);
        final int rows = (int) Math.min(rowsPerStrip, height);
        final int rowBytes = (int) (((long) width * samples * bits + 7) / 8);
        final int step = Math.max(1, (Math.max(width, height) + maxSide - 1) / maxSide);
        final int outWidth = (width + step - 1) / step;
        final int outHeight = (height + step - 1) / step;
        final int[] pixels = new int[outWidth * outHeight];
        Arrays.fill(pixels, 0xFFFFFFFF);

        for (int strip = 0; strip < offsets.length && strip * rows < height; strip++) {
            final int first = strip * rows;
            final int stripRows = Math.min(rows, height - first);
            final int from = (int) offsets[strip];
            final int length = counts != null && strip < counts.length ? (int) counts[strip]
                    : b.length - from;
            if (from < 0 || length < 0 || from + length > b.length) {
                break;
            }
            final byte[] raw = unpack(compression, from, length, stripRows * rowBytes);
            for (int row = 0; row < stripRows; row++) {
                final int y = first + row;
                if (y % step != 0 || (row + 1) * rowBytes > raw.length) {
                    continue;
                }
                if (predictor == 2 && bits == 8) {
                    undoPredictor(raw, row * rowBytes, width, samples);
                }
                paint(pixels, (y / step) * outWidth, raw, row * rowBytes, width, step, samples, bits, photometric,
                        map);
            }
        }
        return Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888);
    }

    private byte[] unpack(int compression, int from, int length, int expected) throws IOException {
        switch (compression) {
            case 5:
                return lzw(b, from, length, expected);
            case 8:
            case 32946:
                return inflate(from, length, expected);
            case 32773:
                return packBits(from, length, expected);
            default:
                return Arrays.copyOfRange(b, from, from + Math.min(length, expected));
        }
    }

    private byte[] inflate(int from, int length, int expected) throws IOException {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(b, from, length);
            final byte[] out = new byte[expected];
            int total = 0;
            while (total < expected && !inflater.finished()) {
                final int read = inflater.inflate(out, total, expected - total);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                total += read;
            }
            return out;
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
    }

    private byte[] packBits(int from, int length, int expected) {
        final byte[] out = new byte[expected];
        int total = 0;
        int pos = from;
        final int end = from + length;
        while (pos < end && total < expected) {
            final int n = b[pos++];
            if (n >= 0) {
                final int count = Math.min(n + 1, Math.min(end - pos, expected - total));
                System.arraycopy(b, pos, out, total, count);
                total += count;
                pos += n + 1;
            } else if (n != -128 && pos < end) {
                final int count = Math.min(1 - n, expected - total);
                Arrays.fill(out, total, total + count, b[pos++]);
                total += count;
            }
        }
        return out;
    }

    /** TIFF's LZW: codes of 9 to 12 bits, most significant bit first, with the code size growing one entry early */
    private static byte[] lzw(byte[] in, int from, int length, int expected) {
        final byte[] out = new byte[expected];
        final int[] prefix = new int[4096];
        final byte[] suffix = new byte[4096];
        final byte[] chain = new byte[4096];
        int total = 0;
        int pos = from;
        final int end = from + length;
        int buffer = 0;
        int held = 0;
        int size = 9;
        int next = 258;
        int old = -1;
        while (total < expected) {
            while (held < size && pos < end) {
                buffer = buffer << 8 | in[pos++] & 0xFF;
                held += 8;
            }
            if (held < size) {
                break;
            }
            final int code = buffer >> (held - size) & (1 << size) - 1;
            held -= size;
            buffer &= (1 << held) - 1;
            if (code == 257) {
                break;
            }
            if (code == 256) {
                size = 9;
                next = 258;
                old = -1;
                continue;
            }
            if (old == -1) {
                if (code < 256) {
                    out[total++] = (byte) code;
                }
                old = code;
                continue;
            }
            int current = code;
            if (code >= next) {
                if (next >= 4096) {
                    break;
                }
                prefix[next] = old;
                suffix[next] = firstOf(old, prefix, suffix);
                current = next;
            } else if (next < 4096) {
                prefix[next] = old;
                suffix[next] = firstOf(code, prefix, suffix);
            }
            int n = 0;
            int c = current;
            while (c >= 258 && n < chain.length - 1) {
                chain[n++] = suffix[c];
                c = prefix[c];
            }
            chain[n++] = (byte) c;
            for (int i = n - 1; i >= 0 && total < expected; i--) {
                out[total++] = chain[i];
            }
            if (next < 4096) {
                next++;
            }
            if (next >= (1 << size) - 1 && size < 12) {
                size++;
            }
            old = code;
        }
        return out;
    }

    private static byte firstOf(int code, int[] prefix, byte[] suffix) {
        int c = code;
        while (c >= 258) {
            c = prefix[c];
        }
        return (byte) c;
    }

    private static void undoPredictor(byte[] raw, int row, int width, int samples) {
        for (int i = samples; i < width * samples; i++) {
            raw[row + i] += raw[row + i - samples];
        }
    }

    private int sample(byte[] raw, int row, int index, int bits) {
        switch (bits) {
            case 8:
                return raw[row + index] & 0xFF;
            case 16:
                return raw[row + index * 2 + (little ? 1 : 0)] & 0xFF;
            default: {
                final int bit = index * bits;
                final int value = raw[row + bit / 8] & 0xFF;
                return value >> (8 - bits - bit % 8) & (1 << bits) - 1;
            }
        }
    }

    private void paint(int[] pixels, int at, byte[] raw, int row, int width, int step, int samples, int bits,
                       int photometric, long[] map) {
        final int max = (1 << Math.min(bits, 8)) - 1;
        for (int x = 0, out = at; x < width; x += step, out++) {
            final int base = x * samples;
            int r;
            int g;
            int bl;
            if (photometric == 2 && samples >= 3) {
                r = sample(raw, row, base, bits);
                g = sample(raw, row, base + 1, bits);
                bl = sample(raw, row, base + 2, bits);
            } else if (photometric == 5 && samples >= 4) {
                final int k = sample(raw, row, base + 3, bits);
                r = 255 - Math.min(255, sample(raw, row, base, bits) + k);
                g = 255 - Math.min(255, sample(raw, row, base + 1, bits) + k);
                bl = 255 - Math.min(255, sample(raw, row, base + 2, bits) + k);
            } else if (photometric == 3) {
                final int index = sample(raw, row, base, bits);
                final int count = 1 << bits;
                r = (int) map[index] >> 8;
                g = (int) map[count + index] >> 8;
                bl = (int) map[2 * count + index] >> 8;
            } else {
                int v = sample(raw, row, base, bits) * 255 / max;
                if (photometric == 0) {
                    v = 255 - v;
                }
                r = v;
                g = v;
                bl = v;
            }
            pixels[out] = 0xFF000000 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | bl & 0xFF;
        }
    }
}
