package dev.dworks.apps.anexplorer.service;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.dworks.apps.anexplorer.DocumentsActivity;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.protocol.EndpointInfo;

/**
 * In-process Quick Share / Nearby Share receiver.
 *
 * <p>Advertises through Play services' Nearby Connections under Quick Share's own service id,
 * so phones find the TV the way they find each other: over Bluetooth as well as Wi-Fi, and
 * move the files over whatever link suits (Wi-Fi Direct, hotspot, the local network). Recent
 * Pixels leave Wi-Fi while Quick Share is open, so a receiver reachable only on the local
 * network is never found. Nearby Connections handles the connection and its encryption; this
 * service speaks the sharing frames on top (public Apache-2.0 protos, as in NearDrop).
 */
public class QuickShareReceiveService extends Service {

    public static final String ACTION_START = "dev.dworks.apps.anexplorer.action.START_QUICKSHARE_RECEIVE";
    public static final String ACTION_STOP = "dev.dworks.apps.anexplorer.action.STOP_QUICKSHARE_RECEIVE";
    public static final String ACTION_QUICKSHARE_STARTED = "dev.dworks.apps.anexplorer.action.QUICKSHARE_STARTED";
    public static final String ACTION_QUICKSHARE_VISIBLE = "dev.dworks.apps.anexplorer.action.QUICKSHARE_VISIBLE";
    public static final String ACTION_QUICKSHARE_STOPPED = "dev.dworks.apps.anexplorer.action.QUICKSHARE_STOPPED";

    /** A sender is waiting for consent; extras: transfer id, device name, summary. */
    public static final String ACTION_QUICKSHARE_INCOMING = "dev.dworks.apps.anexplorer.action.QUICKSHARE_INCOMING";
    /** A transfer finished; extras: device name, file count. */
    public static final String ACTION_QUICKSHARE_RECEIVED = "dev.dworks.apps.anexplorer.action.QUICKSHARE_RECEIVED";
    /** A transfer failed or was cancelled by the sender; extras: device name. */
    public static final String ACTION_QUICKSHARE_FAILED = "dev.dworks.apps.anexplorer.action.QUICKSHARE_FAILED";
    /** A text item (plain text, link, ...) arrived; extras: device name, text. */
    public static final String ACTION_QUICKSHARE_TEXT = "dev.dworks.apps.anexplorer.action.QUICKSHARE_TEXT";

    public static final String EXTRA_ACCEPT = "accept";
    public static final String EXTRA_TRANSFER_ID = "transferId";
    public static final String EXTRA_DEVICE_NAME = "deviceName";
    public static final String EXTRA_SUMMARY = "summary";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_TEXT = "text";

    /** The transfer awaiting consent, so a screen opened later can still ask (TV has no notification actions). */
    private static volatile Bundle sPendingIncoming;

    private static final String TAG = "QuickShareReceive";
    private static final String CHANNEL_ID = "quickshare_receive";
    /** Quick Share's Nearby Connections service */
    private static final String SERVICE_ID = "NearbySharing";
    private static final int NOTIFICATION_ID = 917;

    /** Notifications, broadcasts, Nearby callbacks and session bookkeeping. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Copies received files into place, off the main thread. */
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private ConnectionsClient connectionsClient;
    private boolean advertising;
    /** Sessions by Nearby endpoint id, and by the transfer id the screens and notifications use */
    private final Map<String, TransferSession> sessions = new HashMap<>();
    private final Map<Integer, TransferSession> transfers = new HashMap<>();
    private int transferIdCounter = 1;

    public static void start(Context context) {
        Intent intent = new Intent(context, QuickShareReceiveService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, QuickShareReceiveService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    /** Answers the consent request of an incoming transfer. */
    public static void respond(Context context, int transferId, boolean accept) {
        Intent intent = new Intent(context, QuickShareReceiveService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_ACCEPT, accept);
        intent.putExtra(EXTRA_TRANSFER_ID, transferId);
        context.startService(intent);
    }

    /** The transfer waiting for consent (transfer id, device name, summary), or null. */
    public static Bundle getPendingIncoming() {
        return sPendingIncoming;
    }

    public static boolean isRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningServiceInfo> runningServices = manager.getRunningServices(Integer.MAX_VALUE);
        String className = QuickShareReceiveService.class.getName();
        for (ActivityManager.RunningServiceInfo service : runningServices) {
            if (className.equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        connectionsClient = Nearby.getConnectionsClient(this);
        startForeground(NOTIFICATION_ID, buildForegroundNotification(
                getString(R.string.quickshare_receive_title),
                getString(R.string.quickshare_receive_waiting)));
        broadcast(new Intent(ACTION_QUICKSHARE_STARTED));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra(EXTRA_ACCEPT)) {
            int transferId = intent.getIntExtra(EXTRA_TRANSFER_ID, -1);
            boolean accept = intent.getBooleanExtra(EXTRA_ACCEPT, false);
            TransferSession session = transfers.get(transferId);
            Bundle pending = sPendingIncoming;
            if (pending != null && pending.getInt(EXTRA_TRANSFER_ID) == transferId) {
                sPendingIncoming = null;
            }
            cancelIncomingNotification(transferId);
            if (session != null) {
                Log.i(TAG, "User " + (accept ? "accepted" : "declined") + " transfer " + transferId);
                if (accept) {
                    session.accept();
                } else {
                    session.reject();
                }
            }
            return START_STICKY;
        }
        startAdvertising();
        return START_STICKY;
    }

    private void startAdvertising() {
        if (advertising) {
            return;
        }
        advertising = true;
        byte[] endpointInfo = EndpointInfo.serialize(getDeviceName(), EndpointInfo.DeviceType.LAPTOP);
        connectionsClient.startAdvertising(endpointInfo, SERVICE_ID, lifecycleCallback,
                        new AdvertisingOptions.Builder()
                                .setStrategy(Strategy.P2P_POINT_TO_POINT)
                                .build())
                .addOnSuccessListener(unused -> {
                    updateForegroundNotification(getString(R.string.quickshare_receive_title),
                            getString(R.string.quickshare_receive_visible));
                    broadcast(new Intent(ACTION_QUICKSHARE_VISIBLE));
                })
                .addOnFailureListener(e -> {
                    // Usually a missing Nearby devices permission
                    Log.w(TAG, "Advertising failed", e);
                    CrashReportingManager.logException(e);
                    advertising = false;
                    stopSelf();
                });
    }

    private final ConnectionLifecycleCallback lifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            TransferSession session = new TransferSession(QuickShareReceiveService.this,
                    transferIdCounter++, endpointId, senderName(info.getEndpointInfo()));
            sessions.put(endpointId, session);
            transfers.put(session.transferId, session);
            // Consent comes later, once the sender says what it is sending
            connectionsClient.acceptConnection(endpointId, session.payloadCallback)
                    .addOnFailureListener(e -> session.fail(e));
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

    /** The sender's name from its endpoint info; phones hide it unless the TV is a contact */
    private String senderName(byte[] endpointInfo) {
        try {
            String name = EndpointInfo.parse(endpointInfo).getDeviceName();
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return getString(R.string.quickshare_receive_unknown_device);
    }

    private void broadcast(Intent intent) {
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private String getDeviceName() {
        return getDeviceName(this);
    }

    /** The name senders see: the one the user gave the device (e.g. "Living room TV"). */
    public static String getDeviceName(Context context) {
        String name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
        if (name == null || name.isEmpty()) {
            name = Build.MODEL;
        }
        return name == null ? "Android TV" : name;
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.quickshare_receive_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.quickshare_receive_channel_desc));
        nm.createNotificationChannel(channel);
    }

    private Notification buildForegroundNotification(String title, String text) {
        Intent stopIntent = new Intent(this, QuickShareReceiveService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent openIntent = new Intent(this, DocumentsActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_server)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_action_stop, getString(R.string.quickshare_receive_stop), stopPi)
                .build();
    }

    private void updateForegroundNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildForegroundNotification(title, text));
    }

    private void showIncomingNotification(TransferSession session, String deviceName, String fileSummary) {
        Intent acceptIntent = new Intent(this, QuickShareReceiveService.class);
        acceptIntent.setAction(ACTION_START);
        acceptIntent.putExtra(EXTRA_ACCEPT, true);
        acceptIntent.putExtra(EXTRA_TRANSFER_ID, session.transferId);
        PendingIntent acceptPi = PendingIntent.getService(this, session.transferId * 2,
                acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent rejectIntent = new Intent(this, QuickShareReceiveService.class);
        rejectIntent.setAction(ACTION_START);
        rejectIntent.putExtra(EXTRA_ACCEPT, false);
        rejectIntent.putExtra(EXTRA_TRANSFER_ID, session.transferId);
        PendingIntent rejectPi = PendingIntent.getService(this, session.transferId * 2 + 1,
                rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(String.format(Locale.US,
                        getString(R.string.quickshare_receive_incoming), deviceName))
                .setContentText(fileSummary)
                .setSmallIcon(R.drawable.ic_stat_server)
                .setContentIntent(openDocumentsPendingIntent())
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(false)
                .setAutoCancel(false)
                .addAction(0, getString(R.string.quickshare_receive_accept), acceptPi)
                .addAction(0, getString(R.string.quickshare_receive_decline), rejectPi)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID + session.transferId, notification);

        Bundle incoming = new Bundle();
        incoming.putInt(EXTRA_TRANSFER_ID, session.transferId);
        incoming.putString(EXTRA_DEVICE_NAME, deviceName);
        incoming.putString(EXTRA_SUMMARY, fileSummary);
        sPendingIncoming = incoming;
        broadcast(new Intent(ACTION_QUICKSHARE_INCOMING).putExtras(incoming));
    }

    private void showResultNotification(int transferId, String title, String text) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_stat_server)
                .setContentIntent(openDocumentsPendingIntent())
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID + transferId, notification);
    }

    private PendingIntent openDocumentsPendingIntent() {
        Intent intent = new Intent(this, DocumentsActivity.class);
        return PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void cancelIncomingNotification(int transferId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTIFICATION_ID + transferId);
    }

    private void onTransferReceived(TransferSession session, int fileCount) {
        String deviceName = session.deviceName;
        if (fileCount == 0) {
            return;
        }
        String text = String.format(Locale.US, getString(R.string.quickshare_receive_saved), fileCount);
        showResultNotification(session.transferId,
                String.format(Locale.US, getString(R.string.quickshare_receive_from), deviceName), text);
        broadcast(new Intent(ACTION_QUICKSHARE_RECEIVED)
                .putExtra(EXTRA_DEVICE_NAME, deviceName)
                .putExtra(EXTRA_COUNT, fileCount));
    }

    private void onTextReceived(TransferSession session, String text) {
        String deviceName = session.deviceName;
        showResultNotification(session.transferId,
                String.format(Locale.US, getString(R.string.quickshare_receive_from), deviceName), text);
        broadcast(new Intent(ACTION_QUICKSHARE_TEXT)
                .putExtra(EXTRA_DEVICE_NAME, deviceName)
                .putExtra(EXTRA_TEXT, text));
    }

    private void onTransferFailed(TransferSession session) {
        String deviceName = session.deviceName;
        showResultNotification(session.transferId,
                String.format(Locale.US, getString(R.string.quickshare_receive_from), deviceName),
                getString(R.string.quickshare_receive_failed));
        broadcast(new Intent(ACTION_QUICKSHARE_FAILED).putExtra(EXTRA_DEVICE_NAME, deviceName));
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
        Bundle pending = sPendingIncoming;
        if (pending != null && pending.getInt(EXTRA_TRANSFER_ID) == session.transferId) {
            sPendingIncoming = null;
            cancelIncomingNotification(session.transferId);
        }
    }

    @Override
    public void onDestroy() {
        if (advertising) {
            connectionsClient.stopAdvertising();
            advertising = false;
        }
        for (TransferSession session : new ArrayList<>(sessions.values())) {
            session.onDisconnected();
        }
        connectionsClient.stopAllEndpoints();
        fileExecutor.shutdown();
        sPendingIncoming = null;
        broadcast(new Intent(ACTION_QUICKSHARE_STOPPED));
        super.onDestroy();
    }

    /**
     * One incoming transfer: the sharing handshake (paired key frames, which we can't verify and
     * answer with UNABLE as other open receivers do), the introduction listing what is coming,
     * the user's answer, then the files and texts. Runs on the main thread, except file copies.
     */
    private static final class TransferSession {
        private enum State {
            CONNECTING, SENT_PAIRED_ENCRYPTION, SENT_PAIRED_RESULT, AWAIT_INTRODUCTION,
            AWAIT_CONSENT, RECEIVING, DONE
        }

        private final QuickShareReceiveService service;
        private final int transferId;
        private final String endpointId;
        private final String deviceName;
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

        TransferSession(QuickShareReceiveService service, int transferId, String endpointId,
                        String deviceName) {
            this.service = service;
            this.transferId = transferId;
            this.endpointId = endpointId;
            this.deviceName = deviceName;
        }

        final PayloadCallback payloadCallback = new PayloadCallback() {
            @Override
            public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
                if (payload.getType() == Payload.Type.FILE) {
                    if (state != State.RECEIVING || !pendingFiles.contains(payload.getId())) {
                        service.connectionsClient.cancelPayload(payload.getId());
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
                        service.fileExecutor.execute(() -> {
                            boolean saved = saveFile(payloadId, payload);
                            service.mainHandler.post(() -> onFileSaved(payloadId, saved));
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
            Log.i(TAG, "Connected to " + deviceName);
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
                    service.mainHandler.post(() -> service.onTextReceived(this, content));
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
                        for (Protocol.FileMetadata file : introduction.getFileMetadataList()) {
                            pendingFiles.add(file.getPayloadId());
                        }
                        for (Protocol.TextMetadata text : introduction.getTextMetadataList()) {
                            pendingTexts.put(text.getPayloadId(), text);
                        }
                        pendingIntroduction = introduction;
                        Log.i(TAG, deviceName + " offers " + introduction.getFileMetadataCount()
                                + " files and " + introduction.getTextMetadataCount() + " texts");
                        state = State.AWAIT_CONSENT;
                        service.showIncomingNotification(this, deviceName, summarize(introduction));
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
            service.fileExecutor.execute(() -> {
                prepareDestinations();
                service.mainHandler.post(() -> {
                    if (state != State.RECEIVING) {
                        return;
                    }
                    sendFrame(Protocol.V1Frame.newBuilder()
                            .setType(Protocol.V1Frame.FrameType.RESPONSE)
                            .setConnectionResponse(Protocol.ConnectionResponseFrame.newBuilder()
                                    .setStatus(Protocol.ConnectionResponseFrame.Status.ACCEPT)));
                    service.updateForegroundNotification(service.getString(R.string.quickshare_receive_title),
                            service.getString(R.string.quickshare_receive_receiving));
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
            service.onTransferReceived(this, receivedFiles);
            service.updateForegroundNotification(service.getString(R.string.quickshare_receive_title),
                    service.getString(R.string.quickshare_receive_visible));
            disconnectSoon();
            service.onSessionFinished(this);
        }

        /** Gives the last frames time to reach the sender before hanging up */
        private void disconnectSoon() {
            state = State.DONE;
            service.mainHandler.postDelayed(() ->
                    service.connectionsClient.disconnectFromEndpoint(endpointId), 1000);
            service.onSessionFinished(this);
        }

        void fail(Exception error) {
            if (state == State.DONE) {
                return;
            }
            Log.w(TAG, "Transfer from " + deviceName + " failed", error);
            boolean announced = state == State.RECEIVING || state == State.AWAIT_CONSENT;
            state = State.DONE;
            service.connectionsClient.disconnectFromEndpoint(endpointId);
            service.fileExecutor.execute(this::deletePendingEntries);
            if (announced) {
                service.onTransferFailed(this);
            }
            service.onSessionFinished(this);
        }

        /** The sender went away, or the receiver stopped */
        void onDisconnected() {
            if (state == State.DONE) {
                return;
            }
            Log.i(TAG, deviceName + " disconnected in state " + state);
            boolean wasReceiving = state == State.RECEIVING || state == State.AWAIT_CONSENT;
            state = State.DONE;
            service.fileExecutor.execute(this::deletePendingEntries);
            if (wasReceiving && !rejected) {
                service.onTransferFailed(this);
            }
            service.onSessionFinished(this);
        }

        private void sendFrame(Protocol.V1Frame.Builder v1) {
            byte[] body = Protocol.Frame.newBuilder()
                    .setVersion(Protocol.Frame.Version.V1)
                    .setV1(v1)
                    .build()
                    .toByteArray();
            service.connectionsClient.sendPayload(endpointId, Payload.fromBytes(body))
                    .addOnFailureListener(this::fail);
        }

        private String summarize(Protocol.IntroductionFrame introduction) {
            int count = introduction.getFileMetadataCount() + introduction.getTextMetadataCount();
            if (count == 1 && introduction.getFileMetadataCount() == 1) {
                return introduction.getFileMetadata(0).getName();
            }
            if (count == 1) {
                return introduction.getTextMetadata(0).getTextTitle();
            }
            return String.format(Locale.US, service.getString(R.string.quickshare_receive_nfiles), count);
        }

        private File getDestinationDir() {
            return new File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                    "Quick Share");
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
                    File dir = getDestinationDir();
                    dir.mkdirs();
                    destinations.put(payloadId, makeUniqueFile(dir, name));
                }
            }
        }

        private Uri createMediaStoreEntry(String name, String mimeType) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return null;
            }
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE,
                    mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/Quick Share");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            try {
                Uri uri = service.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return null;
                }
                synchronized (pendingUris) {
                    pendingUris.add(uri);
                }
                return uri;
            } catch (Exception e) {
                CrashReportingManager.logException(e);
                return null;
            }
        }

        /** Moves a received file from where Nearby put it to its place in Download/Quick Share */
        private boolean saveFile(long payloadId, Payload payload) {
            Payload.File file = payload.asFile();
            if (file == null) {
                return false;
            }
            Uri sourceUri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? file.asUri() : null;
            File sourceFile = sourceUri == null ? file.asJavaFile() : null;
            try (InputStream in = sourceUri != null
                    ? service.getContentResolver().openInputStream(sourceUri)
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
                CrashReportingManager.logException(e);
                return false;
            } finally {
                // Nearby's own copy is no longer needed
                try {
                    if (sourceUri != null) {
                        service.getContentResolver().delete(sourceUri, null, null);
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
                return service.getContentResolver().openOutputStream(uri);
            }
            File dest = destinations.get(payloadId);
            return dest == null ? null : new FileOutputStream(dest);
        }

        private void finalizeFile(long payloadId) {
            Uri uri = uris.get(payloadId);
            if (uri == null) {
                return;
            }
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try {
                service.getContentResolver().update(uri, values, null, null);
                synchronized (pendingUris) {
                    pendingUris.remove(uri);
                }
            } catch (Exception e) {
                CrashReportingManager.logException(e);
            }
        }

        private void deletePendingEntries() {
            synchronized (pendingUris) {
                for (Uri uri : pendingUris) {
                    try {
                        service.getContentResolver().delete(uri, null, null);
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
