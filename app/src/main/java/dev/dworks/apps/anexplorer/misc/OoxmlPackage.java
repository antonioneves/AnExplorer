package dev.dworks.apps.anexplorer.misc;

import android.util.Base64;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * An Office Open XML file (.docx, .pptx) read into memory: its XML parts as DOM trees, its
 * relationships and its pictures. Element names keep their prefixes, as in p:sp and a:off, since
 * Office writes the same prefixes everywhere.
 */
final class OoxmlPackage {

    private static final int MAX_PART = 24 * 1024 * 1024;
    private static final int MAX_TOTAL = 64 * 1024 * 1024;
    private static final int MAX_PICTURE = 6 * 1024 * 1024;
    private static final int MAX_PICTURES_TOTAL = 24 * 1024 * 1024;

    /** One relationship: its type's last word, and the absolute part name or the link it points at */
    static final class Rel {
        final String type;
        final String target;
        final boolean external;

        Rel(String type, String target, boolean external) {
            this.type = type;
            this.target = target;
            this.external = external;
        }
    }

    private final Map<String, byte[]> parts = new HashMap<>();
    private final Map<String, Element> trees = new HashMap<>();
    private final Map<String, Map<String, Rel>> relations = new HashMap<>();
    private final Map<String, String> pictures = new HashMap<>();
    private int pictureBytes;

    static OoxmlPackage read(InputStream in) throws IOException {
        final OoxmlPackage pkg = new OoxmlPackage();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                final byte[] bytes = PreviewFormatter.readAll(zip, MAX_PART);
                total += bytes.length;
                if (total > MAX_TOTAL) {
                    throw new IOException("Package too large");
                }
                pkg.parts.put(entry.getName(), bytes);
            }
        }
        if (pkg.parts.isEmpty()) {
            // An encrypted package is not a zip at all
            throw new IOException("Not an Office Open XML package");
        }
        return pkg;
    }

    boolean has(String part) {
        return parts.containsKey(part);
    }

    List<String> names(String prefix) {
        final List<String> out = new ArrayList<>();
        for (String name : parts.keySet()) {
            if (name.startsWith(prefix)) {
                out.add(name);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** @return the root element of an XML part, or null if the part is missing or unreadable */
    Element xml(String part) {
        if (trees.containsKey(part)) {
            return trees.get(part);
        }
        Element root = null;
        final byte[] bytes = parts.get(part);
        if (bytes != null) {
            try {
                final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(false);
                factory.setExpandEntityReferences(false);
                final DocumentBuilder builder = factory.newDocumentBuilder();
                root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            } catch (Exception | StackOverflowError e) {
                root = null;
            }
        }
        trees.put(part, root);
        return root;
    }

    /** @return the relationships of a part by id; targets are resolved against the part's folder */
    Map<String, Rel> rels(String part) {
        Map<String, Rel> map = relations.get(part);
        if (map != null) {
            return map;
        }
        map = new HashMap<>();
        final int slash = part.lastIndexOf('/');
        final String relsPart = part.substring(0, slash + 1) + "_rels/" + part.substring(slash + 1) + ".rels";
        final Element root = xml(relsPart);
        if (root != null) {
            for (Element rel : children(root, "Relationship")) {
                final String type = attr(rel, "Type");
                final String target = attr(rel, "Target");
                final String id = attr(rel, "Id");
                if (id == null || target == null) {
                    continue;
                }
                final boolean external = "External".equals(attr(rel, "TargetMode"));
                map.put(id, new Rel(type == null ? "" : type.substring(type.lastIndexOf('/') + 1),
                        external ? target : resolve(part, target), external));
            }
        }
        relations.put(part, map);
        return map;
    }

    /** @return the part a relationship id points at, or null */
    String target(String part, String id) {
        final Rel rel = id == null ? null : rels(part).get(id);
        return rel == null || rel.external ? null : rel.target;
    }

    /** @return the first part of a relationship type, such as "slideLayout" */
    String targetOfType(String part, String type) {
        for (Rel rel : rels(part).values()) {
            if (rel.type.equals(type) && !rel.external) {
                return rel.target;
            }
        }
        return null;
    }

    static String resolve(String from, String target) {
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        final List<String> path = new ArrayList<>();
        final String folder = from.contains("/") ? from.substring(0, from.lastIndexOf('/')) : "";
        for (String piece : (folder.isEmpty() ? target : folder + "/" + target).split("/")) {
            if (piece.equals("..")) {
                if (!path.isEmpty()) {
                    path.remove(path.size() - 1);
                }
            } else if (!piece.isEmpty() && !piece.equals(".")) {
                path.add(piece);
            }
        }
        return android.text.TextUtils.join("/", path);
    }

    /** @return the picture as a data URI an img can show, or null if it is missing, too big or unknown */
    String picture(String part) {
        if (part == null) {
            return null;
        }
        if (pictures.containsKey(part)) {
            return pictures.get(part);
        }
        String uri = null;
        final byte[] bytes = parts.get(part);
        final String ext = part.substring(part.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (bytes != null && bytes.length <= MAX_PICTURE && pictureBytes + bytes.length <= MAX_PICTURES_TOTAL) {
            String mime = null;
            switch (ext) {
                case "png":
                    mime = "image/png";
                    break;
                case "jpg":
                case "jpeg":
                    mime = "image/jpeg";
                    break;
                case "gif":
                    mime = "image/gif";
                    break;
                case "webp":
                    mime = "image/webp";
                    break;
                case "bmp":
                    mime = "image/bmp";
                    break;
                case "svg":
                    mime = "image/svg+xml";
                    break;
                case "emf":
                case "wmf": {
                    String svg = null;
                    try {
                        svg = Metafile.toSvg(bytes);
                    } catch (RuntimeException e) {
                        svg = null;
                    }
                    if (svg != null) {
                        pictureBytes += svg.length();
                        uri = "data:image/svg+xml;base64," + Base64.encodeToString(
                                svg.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
                    }
                    break;
                }
                default:
                    break;
            }
            if (mime != null) {
                pictureBytes += bytes.length;
                uri = "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
            }
        }
        pictures.put(part, uri);
        return uri;
    }

    // ---- DOM helpers ----

    static List<Element> children(Element parent, String name) {
        final List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && (name == null || name.equals(n.getNodeName()))) {
                out.add((Element) n);
            }
        }
        return out;
    }

    static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    /** @return the element reached by walking down the named children */
    static Element path(Element parent, String... names) {
        Element e = parent;
        for (String name : names) {
            e = child(e, name);
            if (e == null) {
                return null;
            }
        }
        return e;
    }

    static String attr(Element e, String name) {
        if (e == null || !e.hasAttribute(name)) {
            return null;
        }
        return e.getAttribute(name);
    }

    static long num(Element e, String name, long fallback) {
        final String v = attr(e, name);
        if (v == null) {
            return fallback;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException x) {
            try {
                return Math.round(Double.parseDouble(v.trim()));
            } catch (NumberFormatException y) {
                return fallback;
            }
        }
    }

    static boolean flag(Element e, String name, boolean fallback) {
        final String v = attr(e, name);
        if (v == null) {
            return fallback;
        }
        return v.equals("1") || v.equals("true") || v.equals("on");
    }

    /** @return the concatenated text of a:t, w:t and similar leaves below the element */
    static String text(Element e) {
        final StringBuilder out = new StringBuilder();
        collect(e, out);
        return out.toString();
    }

    private static void collect(Node n, StringBuilder out) {
        final NodeList list = n.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            final Node c = list.item(i);
            if (c instanceof Element) {
                collect(c, out);
            } else if (c.getNodeType() == Node.TEXT_NODE) {
                out.append(c.getNodeValue());
            }
        }
    }

    static String esc(String text) {
        final StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '&') {
                out.append("&amp;");
            } else if (c == '<') {
                out.append("&lt;");
            } else if (c == '>') {
                out.append("&gt;");
            } else if (c == '"') {
                out.append("&quot;");
            } else if (c >= 0x20 || c == '\t' || c == '\n') {
                out.append(c);
            }
        }
        return out.toString();
    }

    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) {
            return Long.toString((long) v);
        }
        return String.format(Locale.US, "%.2f", v);
    }
}
