package dev.dworks.apps.anexplorer.cloud;

import android.content.Context;
import android.net.Uri;
import android.util.ArrayMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.dworks.apps.anexplorer.setting.AppSettings;

import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_BOX;

/**
 * Box API 2.0. Box addresses files and folders separately, so ids carry their kind:
 * "folder_0" is the top folder, "file_123" a file.
 */
public class BoxService extends CloudService {

    private static final String API = "https://api.box.com/2.0/";
    private static final String UPLOAD = "https://upload.box.com/api/2.0/files/";
    private static final String TOKEN_URL = "https://api.box.com/oauth2/token";
    private static final String FIELDS = "id,type,name,size,modified_at";
    private static final String FOLDER = "folder_";
    private static final String FILE = "file_";

    public BoxService(Context context) {
        super(context);
    }

    public static boolean isConfigured(Context context) {
        return !clientId(context).isEmpty() && !clientSecret(context).isEmpty();
    }

    private static String clientId(Context context) {
        return AppSettings.getCloudKey(context, AppSettings.KEY_BOX_CLIENT_ID);
    }

    private static String clientSecret(Context context) {
        return AppSettings.getCloudKey(context, AppSettings.KEY_BOX_CLIENT_KEY);
    }

    public static String getAuthorizeUrl(Context context, String state) {
        return Uri.parse("https://account.box.com/api/oauth2/authorize").buildUpon()
                .appendQueryParameter("client_id", clientId(context))
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("state", state)
                .build().toString();
    }

    public void exchangeCode(String code) throws IOException {
        final Map<String, String> params = new ArrayMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("client_id", clientId(context));
        params.put("client_secret", clientSecret(context));
        params.put("redirect_uri", REDIRECT_URI);
        setTokens(postForm(TOKEN_URL, params));
    }

    @Override
    protected void refreshAccessToken() throws IOException {
        // Box hands out a new refresh token each time, and the old one stops working
        final Map<String, String> params = new ArrayMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        params.put("client_id", clientId(context));
        params.put("client_secret", clientSecret(context));
        setTokens(postForm(TOKEN_URL, params));
    }

    @Override
    public String getType() {
        return TYPE_BOX;
    }

    @Override
    public String getRootId() {
        return FOLDER + "0";
    }

    private static String boxId(String id) {
        return id.substring(id.indexOf('_') + 1);
    }

    private static boolean isFolder(String id) {
        return id.startsWith(FOLDER);
    }

    @Override
    public List<CloudFile> list(String folderId) throws IOException {
        final List<CloudFile> files = new ArrayList<>();
        int offset = 0;
        while (true) {
            final JSONObject page = getJson(API + "folders/" + boxId(folderId)
                    + "/items?limit=1000&offset=" + offset + "&fields=" + FIELDS);
            final JSONArray entries = page.optJSONArray("entries");
            final int count = entries != null ? entries.length() : 0;
            for (int i = 0; i < count; i++) {
                final CloudFile file = toFile(entries.optJSONObject(i));
                if (file != null) {
                    files.add(file);
                }
            }
            offset += count;
            if (count == 0 || offset >= page.optInt("total_count")) {
                break;
            }
        }
        return files;
    }

    @Override
    public CloudFile metadata(String id) throws IOException {
        final String kind = isFolder(id) ? "folders/" : "files/";
        return toFile(getJson(API + kind + boxId(id) + "?fields=" + FIELDS));
    }

    private static CloudFile toFile(JSONObject entry) {
        if (entry == null) {
            return null;
        }
        final String type = entry.optString("type");
        final boolean folder = "folder".equals(type);
        // Web links are bookmarks, not files
        if (!folder && !"file".equals(type)) {
            return null;
        }
        final CloudFile file = new CloudFile((folder ? FOLDER : FILE) + entry.optString("id"),
                entry.optString("name"), folder);
        file.lastModified = parseTime(entry.optString("modified_at"));
        if (!folder) {
            file.size = entry.optLong("size", -1);
        }
        return file;
    }

    @Override
    public InputStream download(CloudFile file) throws IOException {
        return openStream(request("GET", API + "files/" + boxId(file.id) + "/content",
                null, null, null));
    }

    @Override
    public InputStream thumbnail(CloudFile file) throws IOException {
        final HttpURLConnection connection = request("GET", API + "files/" + boxId(file.id)
                + "/thumbnail.jpg?min_height=256&min_width=256", null, null, null);
        // Box answers 202 or a redirect while it is still making the thumbnail
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            return null;
        }
        return connection.getInputStream();
    }

    @Override
    public void createFolder(String parentId, String name) throws IOException {
        try {
            requestJson("POST", API + "folders", new JSONObject()
                    .put("name", name)
                    .put("parent", new JSONObject().put("id", boxId(parentId))));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void upload(String parentId, String name, InputStream in, long size, String mimeType)
            throws IOException {
        if (size < 0) {
            throw new IOException("Unknown file size");
        }
        // A file of the same name gets a new version instead
        String existing = null;
        for (CloudFile file : list(parentId)) {
            if (!file.folder && file.name.equalsIgnoreCase(name)) {
                existing = boxId(file.id);
                break;
            }
        }
        final String attributes;
        try {
            attributes = new JSONObject().put("name", name)
                    .put("parent", new JSONObject().put("id", boxId(parentId))).toString();
        } catch (JSONException e) {
            throw new IOException(e);
        }

        final String boundary = "----anexplorer" + UUID.randomUUID();
        final byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"attributes\"\r\n\r\n"
                + attributes + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"upload\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        final byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        final HttpURLConnection connection = openAuthorized("POST",
                UPLOAD + (existing != null ? existing + "/content" : "content"));
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setFixedLengthStreamingMode(head.length + size + tail.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(head);
            copy(in, out, size);
            out.write(tail);
        }
        check(connection);
        connection.disconnect();
    }

    @Override
    public void delete(CloudFile file) throws IOException {
        final String url = isFolder(file.id)
                ? API + "folders/" + boxId(file.id) + "?recursive=true"
                : API + "files/" + boxId(file.id);
        request("DELETE", url, null, null, null).disconnect();
    }

    @Override
    public void fetchAccount() throws IOException {
        final JSONObject me = getJson(API + "users/me");
        userName = me.optString("name", null);
        userLogin = me.optString("login", null);
    }
}
