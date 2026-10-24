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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.dworks.apps.anexplorer.BuildConfig;

import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_DROPBOX;

/** Dropbox API v2. Files are named by their lower-cased path; the top folder is "". */
public class DropboxService extends CloudService {

    private static final String API = "https://api.dropboxapi.com/2/";
    private static final String CONTENT = "https://content.dropboxapi.com/2/";
    private static final String TOKEN_URL = "https://api.dropboxapi.com/oauth2/token";

    public DropboxService(Context context) {
        super(context);
    }

    public static boolean isConfigured() {
        return !BuildConfig.DROPBOX_CLIENT_ID.isEmpty();
    }

    /** The page the user signs in on, using PKCE so the app needs no secret */
    public static String getAuthorizeUrl(String codeChallenge, String state) {
        return Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
                .appendQueryParameter("client_id", BuildConfig.DROPBOX_CLIENT_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("token_access_type", "offline")
                .appendQueryParameter("code_challenge", codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("state", state)
                .build().toString();
    }

    public void exchangeCode(String code, String codeVerifier) throws IOException {
        final Map<String, String> params = new ArrayMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("client_id", BuildConfig.DROPBOX_CLIENT_ID);
        params.put("code_verifier", codeVerifier);
        params.put("redirect_uri", REDIRECT_URI);
        setTokens(postForm(TOKEN_URL, params));
    }

    @Override
    protected void refreshAccessToken() throws IOException {
        final Map<String, String> params = new ArrayMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        params.put("client_id", BuildConfig.DROPBOX_CLIENT_ID);
        setTokens(postForm(TOKEN_URL, params));
    }

    @Override
    public String getType() {
        return TYPE_DROPBOX;
    }

    @Override
    public String getRootId() {
        return "";
    }

    private JSONObject call(String endpoint, JSONObject args) throws IOException {
        return requestJson("POST", API + endpoint, args);
    }

    @Override
    public List<CloudFile> list(String folderId) throws IOException {
        final List<CloudFile> files = new ArrayList<>();
        JSONObject page = call("files/list_folder", args("path", folderId).put2("limit", 2000));
        while (true) {
            final JSONArray entries = page.optJSONArray("entries");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    final CloudFile file = toFile(entries.optJSONObject(i));
                    if (file != null) {
                        files.add(file);
                    }
                }
            }
            if (!page.optBoolean("has_more")) {
                break;
            }
            page = call("files/list_folder/continue", args("cursor", page.optString("cursor")));
        }
        return files;
    }

    @Override
    public CloudFile metadata(String id) throws IOException {
        if (id.isEmpty()) {
            return new CloudFile("", "", true);
        }
        return toFile(call("files/get_metadata", args("path", id)));
    }

    private static CloudFile toFile(JSONObject entry) {
        if (entry == null) {
            return null;
        }
        final String tag = entry.optString(".tag");
        if (!"file".equals(tag) && !"folder".equals(tag)) {
            return null;
        }
        final CloudFile file = new CloudFile(entry.optString("path_lower"),
                entry.optString("name"), "folder".equals(tag));
        file.path = entry.optString("path_display");
        if (!file.folder) {
            file.size = entry.optLong("size", -1);
            file.lastModified = parseTime(entry.optString("server_modified"));
        }
        return file;
    }

    private HttpURLConnection content(String endpoint, Args args) throws IOException {
        final Map<String, String> headers = new ArrayMap<>();
        headers.put("Dropbox-API-Arg", args.toHeader());
        return request("POST", CONTENT + endpoint, headers, null, null);
    }

    @Override
    public InputStream download(CloudFile file) throws IOException {
        return content("files/download", args("path", file.id)).getInputStream();
    }

    @Override
    public InputStream thumbnail(CloudFile file) throws IOException {
        final Args args = new Args();
        args.put2("resource", new Args().put2(".tag", "path").put2("path", file.id))
                .put2("format", "jpeg").put2("size", "w256h256");
        return content("files/get_thumbnail_v2", args).getInputStream();
    }

    @Override
    public void createFolder(String parentId, String name) throws IOException {
        call("files/create_folder_v2", args("path", parentId + "/" + name).put2("autorename", true));
    }

    @Override
    public void upload(String parentId, String name, InputStream in, long size, String mimeType)
            throws IOException {
        final Args args = args("path", parentId + "/" + name)
                .put2("mode", "overwrite").put2("mute", true);
        final HttpURLConnection connection = openAuthorized("POST", CONTENT + "files/upload");
        connection.setRequestProperty("Dropbox-API-Arg", args.toHeader());
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setDoOutput(true);
        if (size >= 0) {
            connection.setFixedLengthStreamingMode(size);
        } else {
            connection.setChunkedStreamingMode(64 * 1024);
        }
        try (OutputStream out = connection.getOutputStream()) {
            copy(in, out, size);
        }
        check(connection);
        connection.disconnect();
    }

    @Override
    public void delete(CloudFile file) throws IOException {
        call("files/delete_v2", args("path", file.id));
    }

    @Override
    public void fetchAccount() throws IOException {
        final HttpURLConnection connection = request("POST", API + "users/get_current_account",
                null, null, null);
        final JSONObject account = readJson(connection);
        final JSONObject name = account.optJSONObject("name");
        userName = name != null ? name.optString("display_name") : null;
        userLogin = account.optString("email");
    }

    private static Args args(String key, Object value) {
        return new Args().put2(key, value);
    }

    /** Call arguments; the ones sent in a header must be plain ASCII */
    private static class Args extends JSONObject {
        Args put2(String key, Object value) {
            try {
                put(key, value);
            } catch (JSONException ignored) {
            }
            return this;
        }

        String toHeader() {
            final String json = toString();
            final StringBuilder ascii = new StringBuilder(json.length());
            for (int i = 0; i < json.length(); i++) {
                final char c = json.charAt(i);
                if (c < 0x7f) {
                    ascii.append(c);
                } else {
                    ascii.append(String.format("\\u%04x", (int) c));
                }
            }
            return ascii.toString();
        }
    }
}
