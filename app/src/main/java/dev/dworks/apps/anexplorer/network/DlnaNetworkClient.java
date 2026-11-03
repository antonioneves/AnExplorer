package dev.dworks.apps.anexplorer.network;

import android.text.TextUtils;
import android.util.Xml;
import android.webkit.MimeTypeMap;

import org.apache.commons.net.ftp.FTPFile;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import dev.dworks.apps.anexplorer.misc.LogUtils;

/**
 * A DLNA/UPnP media server, read only. The connection's path is the server's description
 * document, which names its ContentDirectory service. The server's folders are containers and
 * its files are items, which have no path of their own, so a path is the titles down from the
 * root, and the object ids of what was listed are kept to turn a path back into one.
 */
public class DlnaNetworkClient extends NetworkClient {

    private static final String TAG = DlnaNetworkClient.class.getSimpleName();

    public static final String SCHEME = "dlna";
    public static final String DEFAULT_DESCRIPTION = "/description.xml";

    private static final String ROOT_ID = "0";
    private static final String CONTENT_DIRECTORY = "ContentDirectory";
    /** Items asked for at a time; servers cap what they send, so the total is what ends the loop */
    private static final int PAGE = 200;
    private static final MediaType XML = MediaType.parse("text/xml; charset=\"utf-8\"");

    /** The server answered, but has no media to browse */
    public static class NoContentDirectoryException extends IOException {
        NoContentDirectoryException() {
            super("The server has no ContentDirectory service");
        }
    }

    private static class Entry {
        final String id;
        final boolean directory;
        final String url;
        final long size;
        final long modified;

        Entry(String id, boolean directory, String url, long size, long modified) {
            this.id = id;
            this.directory = directory;
            this.url = url;
            this.size = size;
            this.modified = modified;
        }
    }

    private final OkHttpClient client;
    private final HttpUrl description;
    private final Map<String, Entry> entries = new HashMap<>();
    private HttpUrl controlUrl;
    private String serviceType;
    private String workingDirectory = NetworkFile.separator;
    private boolean connected;

    public DlnaNetworkClient(String host, int port, String descriptionPath) {
        final String path = TextUtils.isEmpty(descriptionPath) || NetworkFile.separator.equals(descriptionPath)
                ? DEFAULT_DESCRIPTION : descriptionPath;
        description = HttpUrl.parse("http://" + host + ":" + port + (path.startsWith("/") ? path : "/" + path));
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public synchronized boolean logout() {
        connected = false;
        return true;
    }

    @Override
    public synchronized boolean isConnected() {
        return connected;
    }

    @Override
    public boolean isAvailable() {
        return isConnected();
    }

    @Override
    public synchronized void disconnect() {
        connected = false;
        entries.clear();
    }

    @Override
    public boolean login(String username, String password) throws IOException {
        return connectClient();
    }

    @Override
    public boolean login(String username, String password, String account) throws IOException {
        return connectClient();
    }

    /** Reads the description for the address of the ContentDirectory service */
    @Override
    public synchronized boolean connectClient() throws IOException {
        if (null == description) {
            throw new IOException("Not a valid server address");
        }
        try (Response response = client.newCall(new Request.Builder().url(description).get().build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Description failed: " + response.code());
            }
            final Description parsed = parseDescription(response.body().string(), description);
            if (null == parsed.controlUrl) {
                throw new NoContentDirectoryException();
            }
            controlUrl = parsed.controlUrl;
            serviceType = parsed.serviceType;
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
        connected = true;
        return true;
    }

    /** What a server's description says about it */
    public static class Description {
        public String name;
        HttpUrl controlUrl;
        String serviceType;

        public boolean hasContentDirectory() {
            return null != controlUrl;
        }
    }

    public static Description parseDescription(String xml, HttpUrl location)
            throws XmlPullParserException, IOException {
        final XmlPullParser parser = newParser(xml);
        final Description result = new Description();
        String base = null;
        boolean inService = false;
        String type = null;
        String control = null;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG) {
                final String tag = tag(parser);
                if ("service".equals(tag)) {
                    inService = true;
                    type = null;
                    control = null;
                } else if ("friendlyName".equals(tag) && null == result.name) {
                    result.name = parser.nextText().trim();
                } else if ("URLBase".equals(tag)) {
                    base = parser.nextText().trim();
                } else if (inService && "serviceType".equals(tag)) {
                    type = parser.nextText().trim();
                } else if (inService && "controlURL".equals(tag)) {
                    control = parser.nextText().trim();
                }
            } else if (event == XmlPullParser.END_TAG && "service".equals(tag(parser))) {
                inService = false;
                if (null != type && type.contains(CONTENT_DIRECTORY) && null != control
                        && null == result.controlUrl) {
                    final HttpUrl root = null != base && null != HttpUrl.parse(base)
                            ? HttpUrl.parse(base) : location;
                    result.controlUrl = root.resolve(control);
                    result.serviceType = type;
                }
            }
        }
        return result;
    }

    @Override
    public String getWorkingDirectory() {
        return NetworkFile.separator;
    }

    @Override
    public synchronized void changeWorkingDirectory(String pathname) {
        workingDirectory = normalize(pathname);
    }

    @Override
    public InputStream getInputStream(String fileName, String directory) {
        try {
            return openInputStream(NetworkFile.join(directory, fileName));
        } catch (IOException e) {
            LogUtils.LOGE(TAG, "Error retrieving file from the media server", e);
        }
        return null;
    }

    @Override
    public InputStream openInputStream(String path) throws IOException {
        final Entry entry;
        synchronized (this) {
            entry = resolve(normalize(path));
        }
        if (entry.directory || null == entry.url) {
            throw new FileNotFoundException(path);
        }
        final Response response = client.newCall(new Request.Builder().url(entry.url).get().build()).execute();
        if (!response.isSuccessful()) {
            response.close();
            throw new IOException("GET " + path + " failed: " + response.code());
        }
        return response.body().byteStream();
    }

    @Override
    public boolean createDirectories(String directory) {
        return false;
    }

    @Override
    public boolean deleteFile(String path) {
        return false;
    }

    @Override
    public synchronized FTPFile[] listFiles() throws IOException {
        final String folder = workingDirectory;
        final Entry entry = resolve(folder);
        final List<FTPFile> files = new ArrayList<>();
        for (Map.Entry<String, Entry> child : list(folder, entry.id).entrySet()) {
            final Entry item = child.getValue();
            final FTPFile file = new FTPFile();
            file.setName(child.getKey());
            file.setType(item.directory ? FTPFile.DIRECTORY_TYPE : FTPFile.FILE_TYPE);
            file.setSize(item.directory ? 0 : item.size);
            final Calendar timestamp = Calendar.getInstance();
            timestamp.setTimeInMillis(item.modified);
            file.setTimestamp(timestamp);
            files.add(file);
        }
        return files.toArray(new FTPFile[0]);
    }

    @Override
    public boolean completePendingCommand() {
        return true;
    }

    private static String normalize(String path) {
        String result = null == path ? "" : path.trim();
        while (result.endsWith(NetworkFile.separator)) {
            result = result.substring(0, result.length() - 1);
        }
        return result.startsWith(NetworkFile.separator) ? result : NetworkFile.separator + result;
    }

    /** The object at a path, found by listing the folders down to it when it wasn't listed yet */
    private Entry resolve(String path) throws IOException {
        if (NetworkFile.separator.equals(path) || path.isEmpty()) {
            return new Entry(ROOT_ID, true, null, 0, 0);
        }
        Entry cached = entries.get(path);
        if (null != cached) {
            return cached;
        }
        String folder = "";
        Entry current = new Entry(ROOT_ID, true, null, 0, 0);
        for (String segment : path.substring(1).split(NetworkFile.separator)) {
            final String next = folder + NetworkFile.separator + segment;
            cached = entries.get(next);
            if (null == cached) {
                list(folder.isEmpty() ? NetworkFile.separator : folder, current.id);
                cached = entries.get(next);
            }
            if (null == cached) {
                throw new FileNotFoundException(path);
            }
            current = cached;
            folder = next;
        }
        return current;
    }

    /** The children of a container by the name each shows under, named apart when titles repeat */
    private Map<String, Entry> list(String folder, String containerId) throws IOException {
        final Map<String, Entry> children = new java.util.LinkedHashMap<>();
        final Set<String> names = new HashSet<>();
        final String base = NetworkFile.separator.equals(folder) ? "" : folder;
        int start = 0;
        int total = Integer.MAX_VALUE;
        while (start < total) {
            final Page page = browse(containerId, start);
            if (page.count == 0) {
                break;
            }
            total = page.total;
            start += page.count;
            for (Parsed parsed : page.items) {
                String name = parsed.name;
                for (int copy = 2; !names.add(name.toLowerCase(Locale.ROOT)); copy++) {
                    name = numbered(parsed.name, copy, parsed.directory);
                }
                final Entry entry = new Entry(parsed.id, parsed.directory, parsed.url, parsed.size, parsed.modified);
                children.put(name, entry);
                entries.put(base + NetworkFile.separator + name, entry);
            }
        }
        return children;
    }

    /** "Movie.mkv" as "Movie (2).mkv", so that the copy keeps its type */
    static String numbered(String name, int copy, boolean directory) {
        final int dot = name.lastIndexOf('.');
        if (directory || dot <= 0) {
            return name + " (" + copy + ")";
        }
        return name.substring(0, dot) + " (" + copy + ")" + name.substring(dot);
    }

    private static class Parsed {
        String id;
        String name;
        boolean directory;
        String url;
        long size;
        long modified;
    }

    private static class Page {
        final List<Parsed> items = new ArrayList<>();
        int count;
        int total;
    }

    private Page browse(String containerId, int start) throws IOException {
        final String body = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:Browse xmlns:u=\"" + serviceType + "\">"
                + "<ObjectID>" + escape(containerId) + "</ObjectID>"
                + "<BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter>"
                + "<StartingIndex>" + start + "</StartingIndex>"
                + "<RequestedCount>" + PAGE + "</RequestedCount><SortCriteria></SortCriteria>"
                + "</u:Browse></s:Body></s:Envelope>";
        try (Response response = client.newCall(new Request.Builder().url(controlUrl)
                .header("SOAPACTION", "\"" + serviceType + "#Browse\"")
                .post(RequestBody.create(XML, body)).build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Browse failed: " + response.code());
            }
            return parseBrowse(response.body().string());
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
    }

    private static Page parseBrowse(String xml) throws XmlPullParserException, IOException {
        final Page page = new Page();
        final XmlPullParser parser = newParser(xml);
        String didl = null;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event != XmlPullParser.START_TAG) {
                continue;
            }
            final String tag = tag(parser);
            if ("Result".equals(tag)) {
                didl = parser.nextText();
            } else if ("NumberReturned".equals(tag)) {
                page.count = parseInt(parser.nextText());
            } else if ("TotalMatches".equals(tag)) {
                page.total = parseInt(parser.nextText());
            }
        }
        if (null != didl) {
            parseDidl(didl, page);
        }
        // A server that gives no total is taken to have sent everything
        if (page.total <= 0) {
            page.total = page.count;
        }
        return page;
    }

    private static void parseDidl(String didl, Page page) throws XmlPullParserException, IOException {
        final XmlPullParser parser = newParser(didl);
        Parsed current = null;
        String title = null;
        long bestSize = -1;
        String mime = null;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG) {
                final String tag = tag(parser);
                if ("container".equals(tag) || "item".equals(tag)) {
                    current = new Parsed();
                    current.id = parser.getAttributeValue(null, "id");
                    current.directory = "container".equals(tag);
                    title = null;
                    bestSize = -1;
                    mime = null;
                } else if (null != current && "title".equals(tag)) {
                    title = parser.nextText().trim();
                } else if (null != current && "date".equals(tag)) {
                    current.modified = parseDate(parser.nextText().trim());
                } else if (null != current && !current.directory && "res".equals(tag)) {
                    final String info = parser.getAttributeValue(null, "protocolInfo");
                    final long size = parseLong(parser.getAttributeValue(null, "size"));
                    final String url = parser.nextText().trim();
                    // Of the several resources an item can have, the biggest is the file itself
                    if (!url.isEmpty() && size > bestSize) {
                        bestSize = size;
                        current.url = url;
                        current.size = Math.max(size, 0);
                        mime = null != info && info.split(":").length > 2 ? info.split(":")[2] : null;
                    }
                }
            } else if (event == XmlPullParser.END_TAG && null != current
                    && ("container".equals(tag(parser)) || "item".equals(tag(parser)))) {
                if (null != current.id && (current.directory || null != current.url)) {
                    final String clean = (TextUtils.isEmpty(title) ? current.id : title)
                            .replace(NetworkFile.separatorChar, '-');
                    current.name = current.directory ? clean : withExtension(clean, current.url, mime);
                    page.items.add(current);
                }
                current = null;
            }
        }
    }

    /** A title has no extension, and the file manager takes the type of a file from its name */
    static String withExtension(String title, String url, String mime) {
        String extension = null;
        final HttpUrl parsed = HttpUrl.parse(url);
        if (null != parsed) {
            final String last = parsed.pathSegments().get(parsed.pathSegments().size() - 1);
            final int dot = last.lastIndexOf('.');
            if (dot >= 0 && last.length() - dot <= 6) {
                extension = last.substring(dot + 1);
            }
        }
        if (null == extension && null != mime) {
            extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        }
        if (null == extension || title.toLowerCase(Locale.ROOT).endsWith("." + extension.toLowerCase(Locale.ROOT))) {
            return title;
        }
        return title + "." + extension;
    }

    /** The element's name without its prefix, so "dc:title" is "title" */
    private static String tag(XmlPullParser parser) {
        final String name = parser.getName();
        return name.substring(name.indexOf(':') + 1);
    }

    private static XmlPullParser newParser(String xml) throws XmlPullParserException {
        final XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new StringReader(xml));
        return parser;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String text) {
        try {
            return null == text ? -1 : Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parseDate(String text) {
        for (String pattern : new String[]{"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"}) {
            try {
                final Date date = new SimpleDateFormat(pattern, Locale.US).parse(text);
                if (null != date) {
                    return date.getTime();
                }
            } catch (ParseException ignored) {
            }
        }
        return 0;
    }
}
