package dev.dworks.apps.anexplorer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ActivityManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;

import androidx.core.app.NotificationCompat;

import com.google.android.gms.nearby.sharing.Protocol;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.dworks.apps.anexplorer.DocumentsActivity;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.protocol.EndpointInfo;
import dev.dworks.apps.anexplorer.protocol.InboundConnection;

/**
 * In-process Quick Share / Nearby Share receiver. Advertises over mDNS in the
 * Nearby Connections format ({@code _FC9F5ED42C8A._tcp.}) and accepts the
 * encrypted Quick Share connection over TCP, running {@link InboundConnection}.
 *
 * <p>Clean-room: protocol behavior derived from the public wire format
 * (Apache-2.0 Google protos), Google's Apache-2.0 {@code ukey2} library, and
 * the public-domain NearDrop implementation.
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

    private static final String CHANNEL_ID = "quickshare_receive";
    private static final String SERVICE_TYPE = "_FC9F5ED42C8A._tcp.";
    private static final byte[] SERVICE_ID_HASH = new byte[]{(byte) 0xFC, (byte) 0x9F, (byte) 0x5E};
    private static final String ENDPOINT_ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int NOTIFICATION_ID = 917;

    private HandlerThread serverThread;
    /** Runs the blocking accept loop; nothing else can be queued on it. */
    private Handler serverHandler;
    /** Notifications, broadcasts and session bookkeeping. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Consent answers write to the socket, which isn't allowed on the main thread. */
    private final ExecutorService consentExecutor = Executors.newSingleThreadExecutor();
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private volatile ServerSocket serverSocket;
    private String endpointId;
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
        serverThread = new HandlerThread("quickshare-receive", Process.THREAD_PRIORITY_BACKGROUND);
        serverThread.start();
        serverHandler = new Handler(serverThread.getLooper());
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        endpointId = generateEndpointId();
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
            TransferSession session;
            synchronized (transfers) {
                session = transfers.get(transferId);
            }
            Bundle pending = sPendingIncoming;
            if (pending != null && pending.getInt(EXTRA_TRANSFER_ID) == transferId) {
                sPendingIncoming = null;
            }
            cancelIncomingNotification(transferId);
            if (session != null) {
                final TransferSession target = session;
                consentExecutor.execute(accept ? target::accept : target::reject);
            }
            return START_STICKY;
        }
        startServer();
        return START_STICKY;
    }

    private void startServer() {
        serverHandler.post(() -> {
            if (serverSocket != null && !serverSocket.isClosed()) {
                return;
            }
            try {
                serverSocket = new ServerSocket(0);
                int port = serverSocket.getLocalPort();
                registerMdns(port);
                acceptLoop();
            } catch (IOException e) {
                CrashReportingManager.logException(e, true);
                stopSelf();
            }
        });
    }

    private void acceptLoop() {
        while (serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                int transferId;
                synchronized (transfers) {
                    transferId = transferIdCounter++;
                }
                TransferSession session = new TransferSession(this, transferId, socket);
                synchronized (transfers) {
                    transfers.put(transferId, session);
                }
                session.start();
            } catch (IOException e) {
                break;
            }
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void registerMdns(int port) {
        String serviceName = makeServiceName(endpointId);
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(serviceName);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);
        byte[] endpointInfo = EndpointInfo.serialize(getDeviceName(), EndpointInfo.DeviceType.LAPTOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            serviceInfo.setAttribute("n",
                    Base64.encode(endpointInfo, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
        }

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                mainHandler.post(() -> updateForegroundNotification(
                        getString(R.string.quickshare_receive_title),
                        getString(R.string.quickshare_receive_visible)));
                broadcast(new Intent(ACTION_QUICKSHARE_VISIBLE));
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                CrashReportingManager.logException(new RuntimeException("mDNS registration failed: " + errorCode));
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
            }
        };
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (IllegalArgumentException e) {
            CrashReportingManager.logException(e);
        }
    }

    private void broadcast(Intent intent) {
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private static String makeServiceName(String endpointId) {
        byte[] nameBytes = new byte[10];
        nameBytes[0] = 0x23;
        byte[] endpointBytes = endpointId.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(endpointBytes, 0, nameBytes, 1, 4);
        System.arraycopy(SERVICE_ID_HASH, 0, nameBytes, 5, 3);
        return Base64.encodeToString(nameBytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
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

    private static String generateEndpointId() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(ENDPOINT_ALPHABET.charAt(random.nextInt(ENDPOINT_ALPHABET.length())));
        }
        return sb.toString();
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

    private void onSessionFinished(int transferId) {
        synchronized (transfers) {
            transfers.remove(transferId);
        }
        Bundle pending = sPendingIncoming;
        if (pending != null && pending.getInt(EXTRA_TRANSFER_ID) == transferId) {
            sPendingIncoming = null;
        }
    }

    private void onTransferReceived(TransferSession session, String deviceName, int fileCount) {
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

    private void onTextReceived(TransferSession session, String deviceName, String text) {
        showResultNotification(session.transferId,
                String.format(Locale.US, getString(R.string.quickshare_receive_from), deviceName), text);
        broadcast(new Intent(ACTION_QUICKSHARE_TEXT)
                .putExtra(EXTRA_DEVICE_NAME, deviceName)
                .putExtra(EXTRA_TEXT, text));
    }

    private void onTransferFailed(TransferSession session, String deviceName) {
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

    @Override
    public void onDestroy() {
        // Closing the socket unblocks accept() and ends the loop on serverThread
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (IOException ignored) {
        }
        if (registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (IllegalArgumentException ignored) {
            }
            registrationListener = null;
        }
        serverThread.quitSafely();
        consentExecutor.shutdown();
        sPendingIncoming = null;
        broadcast(new Intent(ACTION_QUICKSHARE_STOPPED));
        super.onDestroy();
    }

    /** A single inbound Quick Share transfer session. */
    private static final class TransferSession {
        private final QuickShareReceiveService service;
        private final int transferId;
        private final Socket socket;
        private Thread thread;
        private InboundConnection connection;
        private final Map<Long, OutputStream> writers = new HashMap<>();
        private final Map<Long, File> destinations = new HashMap<>();
        private final Map<Long, Uri> uris = new HashMap<>();
        /** MediaStore entries not yet complete; deleted if the transfer fails. */
        private final List<Uri> pendingUris = new ArrayList<>();
        private volatile String deviceName = "";
        private volatile boolean rejected;
        private int receivedFiles;
        private Protocol.IntroductionFrame pendingIntroduction;

        TransferSession(QuickShareReceiveService service, int transferId, Socket socket) {
            this.service = service;
            this.transferId = transferId;
            this.socket = socket;
        }

        void start() {
            thread = new Thread(this::run, "quickshare-transfer-" + transferId);
            thread.start();
        }

        private void run() {
            try {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                connection = new InboundConnection(in, out, new InboundConnection.Listener() {
                    @Override
                    public void onConnectionRequested(String name, EndpointInfo.DeviceType deviceType) {
                        deviceName = name == null ? "" : name;
                    }

                    @Override
                    public void onTransferIntroduced(Protocol.IntroductionFrame introduction) {
                        pendingIntroduction = introduction;
                        service.mainHandler.post(() -> service.showIncomingNotification(
                                TransferSession.this, deviceName, summarize(introduction)));
                    }

                    @Override
                    public void onPayloadChunk(long payloadId, byte[] body, int flags, long offset) {
                        writeChunk(payloadId, body, flags);
                    }

                    @Override
                    public void onTextReceived(Protocol.TextMetadata metadata, String text) {
                        service.mainHandler.post(() -> service.onTextReceived(
                                TransferSession.this, deviceName, text));
                    }

                    @Override
                    public void onTransferComplete() {
                        closeFiles();
                        service.mainHandler.post(() -> service.onTransferReceived(
                                TransferSession.this, deviceName, receivedFiles));
                    }

                    @Override
                    public void onConnectionClosed(Throwable error) {
                        finish(error);
                    }
                });
                connection.run();
            } catch (IOException e) {
                finish(e);
            }
        }

        private void finish(Throwable error) {
            closeFiles();
            closeSocket();
            if (error != null && !rejected) {
                deletePendingEntries();
                service.mainHandler.post(() -> service.onTransferFailed(TransferSession.this, deviceName));
            }
            service.mainHandler.post(() -> service.onSessionFinished(transferId));
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

        private synchronized void writeChunk(long payloadId, byte[] body, int flags) {
            try {
                OutputStream out = writers.get(payloadId);
                if (out == null) {
                    out = openWriter(payloadId);
                    if (out == null) {
                        return;
                    }
                    writers.put(payloadId, out);
                }
                out.write(body);
                if ((flags & 1) == 1) {
                    out.close();
                    writers.remove(payloadId);
                    finalizeFile(payloadId);
                    receivedFiles++;
                }
            } catch (IOException e) {
                CrashReportingManager.logException(e);
            }
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

        private synchronized void closeFiles() {
            for (OutputStream out : writers.values()) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            writers.clear();
        }

        private void closeSocket() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        void accept() {
            prepareDestinations();
            InboundConnection conn = connection;
            if (conn != null) {
                conn.accept();
            }
        }

        void reject() {
            rejected = true;
            InboundConnection conn = connection;
            if (conn != null) {
                conn.reject();
            }
            // The receive loop is blocked reading; closing the socket ends it.
            closeSocket();
        }
    }
}
