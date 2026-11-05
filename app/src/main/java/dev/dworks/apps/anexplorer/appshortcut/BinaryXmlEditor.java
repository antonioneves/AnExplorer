/*
 * Copyright (C) 2026 The AnExplorer Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.dworks.apps.anexplorer.appshortcut;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Replaces strings in a compiled AndroidManifest.xml. The shortcut APK is a copy of the template,
 * so its package and label are swapped here instead of rebuilding it with aapt.
 */
final class BinaryXmlEditor {

    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int UTF8_FLAG = 0x00000100;
    private static final int SORTED_FLAG = 0x00000001;
    private static final int STRING_POOL_HEADER_SIZE = 28;

    private BinaryXmlEditor() {
    }

    static byte[] replaceStrings(byte[] xml, Map<String, String> replacements) {
        if (xml.length < 8 || u16(xml, 0) != RES_XML_TYPE) {
            throw new IllegalArgumentException("Not a binary XML document");
        }
        final int xmlSize = u32(xml, 4);
        int offset = 8;
        while (offset + 8 <= xmlSize) {
            final int type = u16(xml, offset);
            final int headerSize = u16(xml, offset + 2);
            final int size = u32(xml, offset + 4);
            if (size < headerSize || offset + size > xmlSize) {
                throw new IllegalArgumentException("Bad XML chunk at " + offset);
            }
            if (type == RES_STRING_POOL_TYPE) {
                final byte[] pool = rebuildPool(xml, offset, headerSize, size, replacements);
                final byte[] result = new byte[xmlSize - size + pool.length];
                System.arraycopy(xml, 0, result, 0, offset);
                System.arraycopy(pool, 0, result, offset, pool.length);
                System.arraycopy(xml, offset + size, result, offset + pool.length,
                        xmlSize - offset - size);
                putU32(result, 4, result.length);
                return result;
            }
            offset += size;
        }
        throw new IllegalArgumentException("No string pool in the binary XML");
    }

    private static byte[] rebuildPool(byte[] xml, int offset, int headerSize, int size,
                                      Map<String, String> replacements) {
        if (headerSize < STRING_POOL_HEADER_SIZE) {
            throw new IllegalArgumentException("Bad string pool header");
        }
        final int stringCount = u32(xml, offset + 8);
        final int styleCount = u32(xml, offset + 12);
        final int flags = u32(xml, offset + 16);
        final int stringsStart = u32(xml, offset + 20);
        final int stylesStart = u32(xml, offset + 24);
        final boolean utf8 = (flags & UTF8_FLAG) != 0;
        final int dataStart = offset + stringsStart;

        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final int[] stringOffsets = new int[stringCount];
        final Set<String> found = new HashSet<>();
        for (int i = 0; i < stringCount; i++) {
            final int stringOffset = u32(xml, offset + headerSize + i * 4);
            stringOffsets[i] = data.size();
            final String original = readString(xml, dataStart + stringOffset, utf8);
            final String replacement = replacements.get(original);
            if (replacement != null) {
                found.add(original);
            }
            writeString(data, replacement != null ? replacement : original, utf8);
        }
        if (found.size() != replacements.size()) {
            throw new IllegalArgumentException("The template manifest changed: " + replacements.keySet());
        }
        while ((data.size() & 3) != 0) {
            data.write(0);
        }

        final int newStringsStart = headerSize + (stringCount + styleCount) * 4;
        final int stringsEnd = newStringsStart + data.size();
        final int newStylesStart = styleCount > 0 ? stringsEnd : 0;
        final int styleBytes = styleCount > 0 ? size - stylesStart : 0;
        final int poolSize = (stringsEnd + styleBytes + 3) & ~3;

        final byte[] pool = new byte[poolSize];
        System.arraycopy(xml, offset, pool, 0, headerSize);
        putU32(pool, 4, poolSize);
        putU32(pool, 8, stringCount);
        putU32(pool, 12, styleCount);
        putU32(pool, 16, flags & ~SORTED_FLAG);
        putU32(pool, 20, newStringsStart);
        putU32(pool, 24, newStylesStart);

        int position = headerSize;
        for (int stringOffset : stringOffsets) {
            putU32(pool, position, stringOffset);
            position += 4;
        }
        for (int i = 0; i < styleCount; i++) {
            putU32(pool, position, u32(xml, offset + headerSize + (stringCount + i) * 4));
            position += 4;
        }
        final byte[] stringData = data.toByteArray();
        System.arraycopy(stringData, 0, pool, position, stringData.length);
        position += stringData.length;
        if (styleCount > 0) {
            System.arraycopy(xml, offset + stylesStart, pool, position, styleBytes);
        }
        return pool;
    }

    private static String readString(byte[] data, int start, boolean utf8) {
        if (utf8) {
            int offset = start;
            final int u16Length = readUtf8Length(data, offset);
            offset += (data[offset] & 0x80) != 0 ? 2 : 1;
            final int byteLength = readUtf8Length(data, offset);
            offset += (data[offset] & 0x80) != 0 ? 2 : 1;
            return new String(data, offset, byteLength, StandardCharsets.UTF_8);
        }
        int offset = start;
        int length = u16(data, offset);
        if ((length & 0x8000) != 0) {
            length = ((length & 0x7FFF) << 16) | u16(data, offset + 2);
            offset += 4;
        } else {
            offset += 2;
        }
        return new String(data, offset, length * 2, StandardCharsets.UTF_16LE);
    }

    private static int readUtf8Length(byte[] data, int offset) {
        final int first = data[offset] & 0xFF;
        if ((first & 0x80) != 0) {
            return ((first & 0x7F) << 8) | (data[offset + 1] & 0xFF);
        }
        return first;
    }

    private static void writeString(ByteArrayOutputStream out, String value, boolean utf8) {
        if (utf8) {
            final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            writeUtf8Length(out, value.length());
            writeUtf8Length(out, bytes.length);
            out.write(bytes, 0, bytes.length);
            out.write(0);
        } else {
            final int length = value.length();
            if (length > 0x7FFF) {
                writeU16(out, (length >>> 16) | 0x8000);
                writeU16(out, length & 0xFFFF);
            } else {
                writeU16(out, length);
            }
            final byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
            out.write(bytes, 0, bytes.length);
            out.write(0);
            out.write(0);
        }
    }

    private static void writeUtf8Length(ByteArrayOutputStream out, int length) {
        if (length > 0x7F) {
            out.write(((length >> 8) & 0x7F) | 0x80);
        }
        out.write(length & 0xFF);
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void putU32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}
