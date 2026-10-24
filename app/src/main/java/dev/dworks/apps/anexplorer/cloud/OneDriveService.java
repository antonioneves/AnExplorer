package dev.dworks.apps.anexplorer.cloud;

import android.content.Context;
import android.util.ArrayMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.dworks.apps.anexplorer.BuildConfig;

import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_ONEDRIVE;

/**
 * OneDrive through Microsoft Graph. The TV signs in with a device code: the user enters a short
 * code on their phone or computer, so nothing has to be typed on the TV.
 */
public class OneDriveService extends CloudService {

    private static final String LOGIN = "https://login.microsoftonline.com/common/oauth2/v2.0/";
    private static final String GRAPH = "https://graph.microsoft.com/v1.0/me/drive/";
    private static final String SCOPES = "Files.ReadWrite User.Read offline_access";
    private static final String FIELDS = "id,name,size,lastModifiedDateTime,folder,file,package";
    /** Files up to this size go up in one request, bigger ones in pieces */
    private static final long SIMPLE_UPLOAD_LIMIT = 4 * 1024 * 1024;
    private static final int CHUNK = 320 * 1024 * 32;

    public OneDriveService(Context context) {
        super(context);
    }

    public static boolean isConfigured() {
        return !BuildConfig.ONEDRIVE_CLIENT_ID.isEmpty();
    }

    /** Asks for a code to show; the answer holds user_code, verification_uri and device_code */
    public static JSONObject requestDeviceCode() throws IOException {
        final Map<String, String> params = new ArrayMap<>();
        params.put("client_id", BuildConfig.ONEDRIVE_CLIENT_ID);
        params.put("scope", SCOPES);
        return postForm(LOGIN + "devicecode", params);
    }

    /**
     * Checks once whether the user has entered the code. Until they have, this throws a
     * {@link CloudException} whose error is authorization_pending or slow_down.
     */
    public void pollDeviceCode(String deviceCode) throws IOException {
        final Map<String, String> params = new ArrayMap<>();
        params.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        params.put("client_id", BuildConfig.ONEDRIVE_CLIENT_ID);
        params.put("device_code", deviceCode);
        setTokens(postForm(LOGIN + "token", params));
    }

    @Override
    protected void refreshAccessToken() throws IOException {
        final Map<String, String> params = new ArrayMap<>();
        params.put("grant_type", "refresh_token");
        params.put("client_id", BuildConfig.ONEDRIVE_CLIENT_ID);
        params.put("refresh_token", refreshToken);
        params.put("scope", SCOPES);
        setTokens(postForm(LOGIN + "token", params));
    }

    @Override
    public String getType() {
        return TYPE_ONEDRIVE;
    }

    @Override
    public String getRootId() {
        return "root";
    }

    private static String item(String id) {
        return GRAPH + "items/" + encode(id);
    }

    /** An item addressed by its name inside a folder */
    private static String child(String parentId, String name) {
        return item(parentId) + ":/" + encode(name) + ":";
    }

    @Override
    public List<CloudFile> list(String folderId) throws IOException {
        final List<CloudFile> files = new ArrayList<>();
        String url = item(folderId) + "/children?$top=1000&$select=" + FIELDS;
        while (url != null) {
            final JSONObject page = getJson(url);
            final JSONArray items = page.optJSONArray("value");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    final CloudFile file = toFile(items.optJSONObject(i));
                    if (file != null) {
                        files.add(file);
                    }
                }
            }
            url = page.optString("@odata.nextLink", null);
        }
        return files;
    }

    @Override
    public CloudFile metadata(String id) throws IOException {
        return toFile(getJson(item(id) + "?$select=" + FIELDS));
    }

    private static CloudFile toFile(JSONObject item) {
        // OneNote notebooks and the like can't be opened as files
        if (item == null || item.has("package")) {
            return null;
        }
        final CloudFile file = new CloudFile(item.optString("id"), item.optString("name"),
                item.has("folder"));
        file.lastModified = parseTime(item.optString("lastModifiedDateTime"));
        if (!file.folder) {
            file.size = item.optLong("size", -1);
            final JSONObject facet = item.optJSONObject("file");
            if (facet != null) {
                file.mimeType = facet.optString("mimeType", null);
            }
        }
        return file;
    }

    @Override
    public InputStream download(CloudFile file) throws IOException {
        return openStream(request("GET", item(file.id) + "/content", null, null, null));
    }

    @Override
    public InputStream thumbnail(CloudFile file) throws IOException {
        return openStream(request("GET", item(file.id) + "/thumbnails/0/medium/content",
                null, null, null));
    }

    @Override
    public void createFolder(String parentId, String name) throws IOException {
        try {
            final JSONObject body = new JSONObject()
                    .put("name", name)
                    .put("folder", new JSONObject())
                    .put("@microsoft.graph.conflictBehavior", "rename");
            requestJson("POST", item(parentId) + "/children", body);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void upload(String parentId, String name, InputStream in, long size, String mimeType)
            throws IOException {
        if (size >= 0 && size <= SIMPLE_UPLOAD_LIMIT) {
            final HttpURLConnection connection = openAuthorized("PUT",
                    child(parentId, name) + "/content");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setFixedLengthStreamingMode(size);
            try (OutputStream out = connection.getOutputStream()) {
                copy(in, out, size);
            }
            check(connection);
            connection.disconnect();
            return;
        }
        if (size < 0) {
            throw new IOException("Unknown file size");
        }

        final JSONObject session;
        try {
            session = requestJson("POST", child(parentId, name) + "/createUploadSession",
                    new JSONObject().put("item", new JSONObject()
                            .put("@microsoft.graph.conflictBehavior", "replace")));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        final String uploadUrl = session.optString("uploadUrl");
        // The upload link carries its own permission, so the pieces go unsigned
        long sent = 0;
        while (sent < size) {
            final long length = Math.min(CHUNK, size - sent);
            final HttpURLConnection connection = open("PUT", uploadUrl);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(length);
            connection.setRequestProperty("Content-Range",
                    "bytes " + sent + "-" + (sent + length - 1) + "/" + size);
            try (OutputStream out = connection.getOutputStream()) {
                copy(in, out, length);
            }
            check(connection);
            connection.disconnect();
            sent += length;
        }
    }

    @Override
    public void delete(CloudFile file) throws IOException {
        request("DELETE", item(file.id), null, null, null).disconnect();
    }

    @Override
    public void fetchAccount() throws IOException {
        final JSONObject me = getJson("https://graph.microsoft.com/v1.0/me");
        userName = me.optString("displayName", null);
        userLogin = me.optString("userPrincipalName", me.optString("mail", null));
    }
}
