package dev.dworks.apps.anexplorer.quickshare;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;
import com.google.android.gms.nearby.sharing.Protocol;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.dworks.apps.anexplorer.protocol.EndpointInfo;

/**
 * Receives files and texts with Quick Share / Nearby Share.
 *
 * <p>Advertises through Play services' Nearby Connections under Quick Share's own service id,
 * so phones find the device the way they find each other: over Bluetooth as well as Wi-Fi, and
 * move the files over whatever link suits (Wi-Fi Direct, hotspot, the local network). Recent
 * Pixels leave Wi-Fi while Quick Share is open, so a receiver reachable only on the local
 * network is never found. Nearby Connections handles the connection and its encryption; this
 * class speaks the sharing frames on top (public Apache-2.0 protos, as in NearDrop).
 *
 * <p>Everything runs on the main thread, except file copies, and the listener is called there.
 */
public class QuickShareReceiver {

    /** What a sender offers, while it waits for the user to answer */
    public static final class Incoming {
        public final int transferId;
        /** Null when the sender hides its name, as phones do unless this device is a contact */
        public final String deviceName;
        /** What the sender shows; null if Nearby gave no token */
        public final String pin;
        public final List<String> fileNames;
        public final List<String> textTitles;

        Incoming(int transferId, String deviceName, String pin, List<String> fileNames,
                 List<String> textTitles) {
            this.transferId = transferId;
            this.deviceName = deviceName;
            this.pin = pin;
            this.fileNames = Collections.unmodifiableList(fileNames);
            this.textTitles = Collections.unmodifiableList(textTitles);
        }
    }

    /** Where received files go; asked again for each transfer */
    public interface Destination {
        /** The folder files are written to directly, which needs all-files access outside the app's own */
        File getFolder();

        /**
         * A folder under Download to save into through MediaStore instead, which needs no storage
         * access, or null to write to {@link #getFolder()}. Used on Android 10 and later.
         */
        String getDownloadsFolder();
    }

    public interface Listener {
        /** Senders can now find this device */
        void onVisible();

        /** Advertising failed, usually for a missing Nearby devices permission; nothing more will come */
        void onStartFailed(Exception error);

        /** A sender waits for the user's answer, to give with {@link #respond} */
        void onIncoming(Incoming incoming);

        /** The user accepted and the files are coming */
        void onReceiving(int transferId);

        /** Everything arrived; the count is 0 when only texts came */
        void onReceived(int transferId, String deviceName, int fileCount);

        void onTextReceived(int transferId, String deviceName, String text);

        /** A transfer the user was asked about failed, or the sender cancelled it */
        void onFailed(int transferId, String deviceName);

        /** The transfer is over, however it ended */
        void onFinished(int transferId);
    }

    private static final String TAG = "QuickShareReceive";

    private final Context context;
    private final Destination destination;
    private final Listener listener;
    /** Listener calls, Nearby callbacks and session bookkeeping. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Copies received files into place, off the main thread. */
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final ConnectionsClient connectionsClient;
    private boolean advertising;
    /** Sessions by Nearby endpoint id, and by the transfer id the app uses */
    private final Map<String, TransferSession> sessions = new HashMap<>();
    private final Map<Integer, TransferSession> transfers = new HashMap<>();
    private int transferIdCounter = 1;

    public QuickShareReceiver(Context context, Destination destination, Listener listener) {
        this.context = context.getApplicationContext();
        this.connectionsClient = Nearby.getConnectionsClient(context);
        this.destination = destination;
        this.listener = listener;
    }

    /** Makes this device visible to senders */
    public void start() {
        if (advertising) {
            return;
        }
        advertising = true;
        byte[] endpointInfo = EndpointInfo.serialize(QuickShare.getDeviceName(context),
                EndpointInfo.DeviceType.LAPTOP);
        connectionsClient.startAdvertising(endpointInfo, QuickShare.SERVICE_ID, lifecycleCallback,
                        new AdvertisingOptions.Builder()
                                .setStrategy(Strategy.P2P_POINT_TO_POINT)
                                .build())
                .addOnSuccessListener(unused -> listener.onVisible())
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Advertising failed", e);
                    advertising = false;
                    listener.onStartFailed(e);
                });
    }

    /** Hides this device and drops any transfer under way; the receiver can't be started again */
    public void stop() {
        if (advertising) {
            connectionsClient.stopAdvertising();
            advertising = false;
        }
        for (TransferSession session : new ArrayList<>(sessions.values())) {
            session.onDisconnected();
        }
        connectionsClient.stopAllEndpoints();
        fileExecutor.shutdown();
    }

    /** Answers a transfer reported by {@link Listener#onIncoming} */
    public void respond(int transferId, boolean accept) {
        TransferSession session = transfers.get(transferId);
        if (session == null) {
            return;
        }
        Log.i(TAG, "User " + (accept ? "accepted" : "declined") + " transfer " + transferId);
        if (accept) {
            session.accept();
        } else {
            session.reject();
        }
    }

    private final ConnectionLifecycleCallback lifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            TransferSession session = new TransferSession(transferIdCounter++, endpointId,
                    senderName(info.getEndpointInfo()), QuickShare.pinCode(info.getRawAuthenticationToken()));
            sessions.put(endpointId, session);
            transfers.put(session.transferId, session);
            // Consent comes later, once the sender says what it is sending
            connectionsClient.acceptConnection(endpointId, session.payloadCallback)
                    .addOnFailureListener(session::fail);
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            TransferSession session = sessions.get(endpointId);
            if (session == null) {
                return;
            }
            if (result.getStatus().isSuccess()) {
                session.onConnected();
            } else {
                session.fail(new IOException("Connection failed: " + result.getStatus()));
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            TransferSession session = sessions.get(endpointId);
            if (session != null) {
                session.onDisconnected();
            }
        }
    };

    /** The sender's name from its endpoint info; phones hide it unless this device is a contact */
    private static String senderName(byte[] endpointInfo) {
        try {
            String name = EndpointInfo.parse(endpointInfo).getDeviceName();
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private static File makeUniqueFile(File dir, String name) {
        File file = new File(dir, name);
        if (!file.exists()) {
            return file;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 100; i++) {
            File candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return file;
    }

    private void onSessionFinished(TransferSession session) {
        sessions.remove(session.endpointId);
        transfers.remove(session.transferId);
        listener.onFinished(session.transferId);
    }

    private enum State {
        CONNECTING, SENT_PAIRED_ENCRYPTION, SENT_PAIRED_RESULT, AWAIT_INTRODUCTION,
        AWAIT_CONSENT, RECEIVING, DONE
    }

    /**
     * One incoming transfer: the sharing handshake (paired key frames, which we can't verify and
     * answer with UNABLE as other open receivers do), the introduction listing what is coming,
     * the user's answer, then the files and texts. Runs on the main thread, except file copies.
     */
    private final class TransferSession {
        private final int transferId;
        private final String endpointId;
        private final String deviceName;
        private final String pin;
        private final SecureRandom random = new SecureRandom();
        private State state = State.CONNECTING;
        private Protocol.IntroductionFrame pendingIntroduction;
        /** Announced files and texts not yet fully received, by payload id */
        private final Set<Long> pendingFiles = new HashSet<>();
        private final Map<Long, Protocol.TextMetadata> pendingTexts = new HashMap<>();
        /** File payloads as they arrive; their content is ready once the transfer succeeds */
        private final Map<Long, Payload> filePayloads = new HashMap<>();
        private final Map<Long, File> destinations = new HashMap<>();
        private final Map<Long, Uri> uris = new HashMap<>();
        /** MediaStore entries not yet complete; deleted if the transfer fails. */
        private final List<Uri> pendingUris = new ArrayList<>();
        private int receivedFiles;
        private boolean rejected;

        TransferSession(int transferId, String endpointId, String deviceName, String pin) {
            this.transferId = transferId;
            this.endpointId = endpointId;
            this.deviceName = deviceName;
            this.pin = pin;
        }

        final PayloadCallback payloadCallback = new PayloadCallback() {
            @Override
            public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
                if (payload.getType() == Payload.Type.FILE) {
                    if (state != State.RECEIVING || !pendingFiles.contains(payload.getId())) {
                        connectionsClient.cancelPayload(payload.getId());
                        return;
                    }
                    filePayloads.put(payload.getId(), payload);
                } else if (payload.getType() == Payload.Type.BYTES) {
                    onBytes(payload.getId(), payload.asBytes());
                }
            }

            @Override
            public void onPayloadTransferUpdate(@NonNull String endpointId,
                                                @NonNull PayloadTransferUpdate update) {
                long payloadId = update.getPayloadId();
                if (!pendingFiles.contains(payloadId)) {
                    return;
                }
                int status = update.getStatus();
                if (status == PayloadTransferUpdate.Status.SUCCESS) {
                    Payload payload = filePayloads.remove(payloadId);
                    if (payload != null) {
                        fileExecutor.execute(() -> {
                            boolean saved = saveFile(payloadId, payload);
                            mainHandler.post(() -> onFileSaved(payloadId, saved));
                        });
                    }
                } else if (status == PayloadTransferUpdate.Status.FAILURE
                        || status == PayloadTransferUpdate.Status.CANCELED) {
                    fail(new IOException("File transfer " + (status == PayloadTransferUpdate.Status.CANCELED
                            ? "cancelled" : "failed")));
                }
            }
        };

        void onConnected() {
            Log.i(TAG, "Connected to " + deviceName + ", PIN " + pin);
            // We have no certificates to offer; the sender carries on without them
            state = State.SENT_PAIRED_ENCRYPTION;
            sendFrame(Protocol.V1Frame.newBuilder()
                    .setType(Protocol.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION)
                    .setPairedKeyEncryption(Protocol.PairedKeyEncryptionFrame.newBuilder()
                            .setSecretIdHash(ByteString.copyFrom(randomBytes(6)))
                            .setSignedData(ByteString.copyFrom(randomBytes(72)))));
        }

        private void onBytes(long payloadId, byte[] body) {
            if (body == null) {
                return;
            }
            Protocol.TextMetadata text = pendingTexts.remove(payloadId);
            if (text != null) {
                Log.i(TAG, "Text from " + deviceName + (state == State.RECEIVING ? "" : " before consent"));
                if (state == State.RECEIVING) {
                    String content = new String(body, StandardCharsets.UTF_8);
                    mainHandler.post(() -> listener.onTextReceived(transferId, deviceName, content));
                    completeIfDone();
                }
                return;
            }
            try {
                onFrame(Protocol.Frame.parseFrom(body));
            } catch (IOException e) {
                Log.w(TAG, "Unreadable frame from " + deviceName, e);
            }
        }

        private void onFrame(Protocol.Frame frame) {
            if (!frame.hasV1()) {
                return;
            }
            Protocol.V1Frame v1 = frame.getV1();
            if (v1.getType() == Protocol.V1Frame.FrameType.CANCEL) {
                fail(new IOException("Transfer cancelled by sender"));
                return;
            }
            switch (state) {
                case SENT_PAIRED_ENCRYPTION:
                    if (v1.getType() == Protocol.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION) {
                        state = State.SENT_PAIRED_RESULT;
                        sendFrame(Protocol.V1Frame.newBuilder()
                                .setType(Protocol.V1Frame.FrameType.PAIRED_KEY_RESULT)
                                .setPairedKeyResult(Protocol.PairedKeyResultFrame.newBuilder()
                                        .setStatus(Protocol.PairedKeyResultFrame.Status.UNABLE)));
                    }
                    break;
                case SENT_PAIRED_RESULT:
                    if (v1.getType() == Protocol.V1Frame.FrameType.PAIRED_KEY_RESULT) {
                        state = State.AWAIT_INTRODUCTION;
                    }
                    break;
                case AWAIT_INTRODUCTION:
                    if (v1.getType() == Protocol.V1Frame.FrameType.INTRODUCTION) {
                        Protocol.IntroductionFrame introduction = v1.getIntroduction();
                        List<String> fileNames = new ArrayList<>();
                        for (Protocol.FileMetadata file : introduction.getFileMetadataList()) {
                            pendingFiles.add(file.getPayloadId());
                            fileNames.add(file.getName());
                        }
                        List<String> textTitles = new ArrayList<>();
                        for (Protocol.TextMetadata text : introduction.getTextMetadataList()) {
                            pendingTexts.put(text.getPayloadId(), text);
                            textTitles.add(text.getTextTitle());
                        }
                        pendingIntroduction = introduction;
                        Log.i(TAG, deviceName + " offers " + introduction.getFileMetadataCount()
                                + " files and " + introduction.getTextMetadataCount() + " texts");
                        state = State.AWAIT_CONSENT;
                        listener.onIncoming(new Incoming(transferId, deviceName, pin, fileNames, textTitles));
                    }
                    break;
                default:
                    break;
            }
        }

        void accept() {
            if (state != State.AWAIT_CONSENT) {
                return;
            }
            state = State.RECEIVING;
            // MediaStore entries are made off the main thread, before the answer goes out
            fileExecutor.execute(() -> {
                prepareDestinations();
                mainHandler.post(() -> {
                    if (state != State.RECEIVING) {
                        return;
                    }
                    sendFrame(Protocol.V1Frame.newBuilder()
                            .setType(Protocol.V1Frame.FrameType.RESPONSE)
                            .setConnectionResponse(Protocol.ConnectionResponseFrame.newBuilder()
                                    .setStatus(Protocol.ConnectionResponseFrame.Status.ACCEPT)));
                    listener.onReceiving(transferId);
                    // Nothing announced means nothing to wait for
                    completeIfDone();
                });
            });
        }

        void reject() {
            if (state != State.AWAIT_CONSENT) {
                return;
            }
            rejected = true;
            sendFrame(Protocol.V1Frame.newBuilder()
                    .setType(Protocol.V1Frame.FrameType.RESPONSE)
                    .setConnectionResponse(Protocol.ConnectionResponseFrame.newBuilder()
                            .setStatus(Protocol.ConnectionResponseFrame.Status.REJECT)));
            disconnectSoon();
        }

        private void onFileSaved(long payloadId, boolean saved) {
            if (state == State.DONE) {
                return;
            }
            if (!saved) {
                fail(new IOException("Couldn't save a received file"));
                return;
            }
            pendingFiles.remove(payloadId);
            receivedFiles++;
            completeIfDone();
        }

        private void completeIfDone() {
            if (state != State.RECEIVING || !pendingFiles.isEmpty() || !pendingTexts.isEmpty()) {
                return;
            }
            state = State.DONE;
            Log.i(TAG, "Received " + receivedFiles + " files from " + deviceName);
            listener.onReceived(transferId, deviceName, receivedFiles);
            disconnectSoon();
            onSessionFinished(this);
        }

        /** Gives the last frames time to reach the sender before hanging up */
        private void disconnectSoon() {
            state = State.DONE;
            mainHandler.postDelayed(() -> connectionsClient.disconnectFromEndpoint(endpointId), 1000);
            onSessionFinished(this);
        }

        void fail(Exception error) {
            if (state == State.DONE) {
                return;
            }
            Log.w(TAG, "Transfer from " + deviceName + " failed", error);
            boolean announced = state == State.RECEIVING || state == State.AWAIT_CONSENT;
            state = State.DONE;
            connectionsClient.disconnectFromEndpoint(endpointId);
            fileExecutor.execute(this::deletePendingEntries);
            if (announced) {
                listener.onFailed(transferId, deviceName);
            }
            onSessionFinished(this);
        }

        /** The sender went away, or the receiver stopped */
        void onDisconnected() {
            if (state == State.DONE) {
                return;
            }
            Log.i(TAG, deviceName + " disconnected in state " + state);
            boolean wasReceiving = state == State.RECEIVING || state == State.AWAIT_CONSENT;
            state = State.DONE;
            fileExecutor.execute(this::deletePendingEntries);
            if (wasReceiving && !rejected) {
                listener.onFailed(transferId, deviceName);
            }
            onSessionFinished(this);
        }

        private void sendFrame(Protocol.V1Frame.Builder v1) {
            connectionsClient.sendPayload(endpointId, QuickShare.framePayload(v1))
                    .addOnFailureListener(this::fail);
        }

        private void prepareDestinations() {
            Protocol.IntroductionFrame introduction = pendingIntroduction;
            if (introduction == null) {
                return;
            }
            for (Protocol.FileMetadata meta : introduction.getFileMetadataList()) {
                long payloadId = meta.getPayloadId();
                String name = meta.getName();
                if (name == null || name.isEmpty() || name.indexOf('/') >= 0) {
                    name = "payload_" + payloadId;
                }
                Uri uri = createMediaStoreEntry(name, meta.getMimeType());
                if (uri != null) {
                    uris.put(payloadId, uri);
                } else {
                    File dir = destination.getFolder();
                    dir.mkdirs();
                    destinations.put(payloadId, makeUniqueFile(dir, name));
                }
            }
        }

        private Uri createMediaStoreEntry(String name, String mimeType) {
            // MediaStore only places downloads under Download; a folder picked elsewhere is
            // written directly, which all-files access allows
            String downloadsFolder = destination.getDownloadsFolder();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || downloadsFolder == null) {
                return null;
            }
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE,
                    mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + downloadsFolder);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            try {
                Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return null;
                }
                synchronized (pendingUris) {
                    pendingUris.add(uri);
                }
                return uri;
            } catch (Exception e) {
                Log.w(TAG, "Couldn't add " + name + " to Downloads", e);
                return null;
            }
        }

        /** Moves a received file from where Nearby put it to its place in the destination */
        private boolean saveFile(long payloadId, Payload payload) {
            Payload.File file = payload.asFile();
            if (file == null) {
                return false;
            }
            Uri sourceUri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? file.asUri() : null;
            File sourceFile = sourceUri == null ? file.asJavaFile() : null;
            try (InputStream in = sourceUri != null
                    ? context.getContentResolver().openInputStream(sourceUri)
                    : sourceFile != null ? new FileInputStream(sourceFile)
                    : new ParcelFileDescriptor.AutoCloseInputStream(file.asParcelFileDescriptor());
                 OutputStream out = openWriter(payloadId)) {
                if (in == null || out == null) {
                    return false;
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "Couldn't save a file from " + deviceName, e);
                return false;
            } finally {
                // Nearby's own copy is no longer needed
                try {
                    if (sourceUri != null) {
                        context.getContentResolver().delete(sourceUri, null, null);
                    } else if (sourceFile != null) {
                        sourceFile.delete();
                    }
                } catch (Exception ignored) {
                }
            }
            finalizeFile(payloadId);
            return true;
        }

        private OutputStream openWriter(long payloadId) throws IOException {
            Uri uri = uris.get(payloadId);
            if (uri != null) {
                return context.getContentResolver().openOutputStream(uri);
            }
            File dest = destinations.get(payloadId);
            return dest == null ? null : new FileOutputStream(dest);
        }

        private void finalizeFile(long payloadId) {
            Uri uri = uris.get(payloadId);
            if (uri == null) {
                // Written directly, so media apps learn of it from the scanner
                File dest = destinations.get(payloadId);
                if (dest != null) {
                    MediaScannerConnection.scanFile(context, new String[]{dest.getAbsolutePath()}, null, null);
                }
                return;
            }
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try {
                context.getContentResolver().update(uri, values, null, null);
                synchronized (pendingUris) {
                    pendingUris.remove(uri);
                }
            } catch (Exception e) {
                Log.w(TAG, "Couldn't finish " + uri, e);
            }
        }

        private void deletePendingEntries() {
            synchronized (pendingUris) {
                for (Uri uri : pendingUris) {
                    try {
                        context.getContentResolver().delete(uri, null, null);
                    } catch (Exception ignored) {
                    }
                }
                pendingUris.clear();
            }
        }

        private byte[] randomBytes(int length) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            return bytes;
        }
    }
}
