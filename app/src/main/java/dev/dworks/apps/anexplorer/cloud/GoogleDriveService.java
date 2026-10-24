package dev.dworks.apps.anexplorer.cloud;

import android.accounts.Account;
import android.content.Context;
import android.text.TextUtils;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import android.util.ArrayMap;

import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_GDRIVE;

/**
 * Google Drive API v3. Access comes from the Google account already on the TV, through Play
 * services, so there is no refresh token: a new access token is asked for when one runs out.
 */
public class GoogleDriveService extends CloudService {

    public static final String SCOPE = "https://www.googleapis.com/auth/drive";
    private static final String API = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String FIELDS = "id,name,mimeType,size,modifiedTime,thumbnailLink";
    private static final String FOLDER = "application/vnd.google-apps.folder";
    private static final String GOOGLE_APPS = "application/vnd.google-apps.";
    /** Access tokens last an hour; ask for a new one a little before */
    private static final long TOKEN_LIFETIME = 50 * 60 * 1000;

    public GoogleDriveService(Context context) {
        super(context);
    }

    public static AuthorizationRequest authorizationRequest(String accountName) {
        final AuthorizationRequest.Builder builder = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)));
        if (!TextUtils.isEmpty(accountName)) {
            builder.setAccount(new Account(accountName, "com.google"));
        }
        return builder.build();
    }

    /** Takes the access token granted on the sign-in screen */
    public void setAuthorization(AuthorizationResult result) {
        setAccessToken(result.getAccessToken(), System.currentTimeMillis() + TOKEN_LIFETIME);
    }

    @Override
    protected boolean canRefresh() {
        return !TextUtils.isEmpty(userLogin);
    }

    @Override
    protected void refreshAccessToken() throws IOException {
        if (TextUtils.isEmpty(userLogin)) {
            throw new IOException("Not signed in");
        }
        final AuthorizationResult result;
        try {
            result = Tasks.await(Identity.getAuthorizationClient(context)
                    .authorize(authorizationRequest(userLogin)));
        } catch (ExecutionException | InterruptedException e) {
            throw new IOException(e);
        }
        if (result.hasResolution() || TextUtils.isEmpty(result.getAccessToken())) {
            throw new IOException("Google Drive needs you to sign in again");
        }
        setAuthorization(result);
    }

    @Override
    public String getType() {
        return TYPE_GDRIVE;
    }

    @Override
    public String getRootId() {
        return "root";
    }

    @Override
    public List<CloudFile> list(String folderId) throws IOException {
        final List<CloudFile> files = new ArrayList<>();
        final String query = encode("'" + folderId.replace("'", "\\'") + "' in parents and trashed = false");
        String pageToken = null;
        do {
            String url = API + "?q=" + query + "&pageSize=1000"
                    + "&supportsAllDrives=true&includeItemsFromAllDrives=true"
                    + "&fields=" + encode("nextPageToken,files(" + FIELDS + ")");
            if (pageToken != null) {
                url += "&pageToken=" + encode(pageToken);
            }
            final JSONObject page = getJson(url);
            final JSONArray items = page.optJSONArray("files");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    final CloudFile file = toFile(items.optJSONObject(i));
                    if (file != null) {
                        files.add(file);
                    }
                }
            }
            pageToken = page.optString("nextPageToken", null);
        } while (!TextUtils.isEmpty(pageToken));
        return files;
    }

    @Override
    public CloudFile metadata(String id) throws IOException {
        return toFile(getJson(API + "/" + encode(id) + "?supportsAllDrives=true&fields="
                + encode(FIELDS)));
    }

    /** Docs, Sheets, Slides and Drawings have no file of their own, so they come as PDFs */
    private static boolean isExported(String mimeType) {
        return mimeType.equals(GOOGLE_APPS + "document")
                || mimeType.equals(GOOGLE_APPS + "spreadsheet")
                || mimeType.equals(GOOGLE_APPS + "presentation")
                || mimeType.equals(GOOGLE_APPS + "drawing");
    }

    private static CloudFile toFile(JSONObject item) {
        if (item == null) {
            return null;
        }
        final String mimeType = item.optString("mimeType");
        final boolean folder = FOLDER.equals(mimeType);
        String name = item.optString("name");
        if (!folder && mimeType.startsWith(GOOGLE_APPS)) {
            // Forms, sites, shortcuts and the like can't be opened
            if (!isExported(mimeType)) {
                return null;
            }
            name += ".pdf";
        }
        final CloudFile file = new CloudFile(item.optString("id"), name, folder);
        file.mimeType = mimeType;
        file.lastModified = parseTime(item.optString("modifiedTime"));
        file.size = folder ? -1 : item.optLong("size", -1);
        final String thumbnail = item.optString("thumbnailLink", null);
        if (!TextUtils.isEmpty(thumbnail)) {
            file.thumbnailUrl = thumbnail.replaceAll("=s\\d+$", "=s256");
        }
        return file;
    }

    @Override
    public InputStream download(CloudFile file) throws IOException {
        final String url = file.mimeType != null && isExported(file.mimeType)
                ? API + "/" + encode(file.id) + "/export?mimeType=application/pdf"
                : API + "/" + encode(file.id) + "?alt=media&supportsAllDrives=true";
        return openStream(request("GET", url, null, null, null));
    }

    @Override
    public InputStream thumbnail(CloudFile file) throws IOException {
        String url = file.thumbnailUrl;
        if (url == null) {
            final CloudFile fresh = metadata(file.id);
            url = fresh != null ? fresh.thumbnailUrl : null;
        }
        if (url == null) {
            return null;
        }
        return openStream(request("GET", url, null, null, null));
    }

    @Override
    public void createFolder(String parentId, String name) throws IOException {
        try {
            requestJson("POST", API + "?supportsAllDrives=true", new JSONObject()
                    .put("name", name)
                    .put("mimeType", FOLDER)
                    .put("parents", new JSONArray().put(parentId)));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void upload(String parentId, String name, InputStream in, long size, String mimeType)
            throws IOException {
        final byte[] metadata;
        try {
            metadata = new JSONObject().put("name", name)
                    .put("parents", new JSONArray().put(parentId))
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        final Map<String, String> headers = new ArrayMap<>();
        if (!TextUtils.isEmpty(mimeType)) {
            headers.put("X-Upload-Content-Type", mimeType);
        }
        if (size >= 0) {
            headers.put("X-Upload-Content-Length", Long.toString(size));
        }
        final HttpURLConnection start = request("POST",
                UPLOAD + "?uploadType=resumable&supportsAllDrives=true", headers,
                "application/json; charset=UTF-8", metadata);
        final String session = start.getHeaderField("Location");
        start.disconnect();
        if (session == null) {
            throw new IOException("Google Drive did not start the upload");
        }

        final HttpURLConnection connection = openAuthorized("PUT", session);
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
        // Into the bin, where it can still be got back, as the Drive app does
        final Map<String, String> headers = new ArrayMap<>();
        headers.put("X-HTTP-Method-Override", "PATCH");
        request("POST", API + "/" + encode(file.id) + "?supportsAllDrives=true", headers,
                "application/json", "{\"trashed\":true}".getBytes(StandardCharsets.UTF_8))
                .disconnect();
    }

    @Override
    public void fetchAccount() throws IOException {
        final JSONObject about = getJson("https://www.googleapis.com/drive/v3/about?fields="
                + encode("user(displayName,emailAddress)"));
        final JSONObject user = about.optJSONObject("user");
        if (user != null) {
            userName = user.optString("displayName", null);
            userLogin = user.optString("emailAddress", null);
        }
    }
}
