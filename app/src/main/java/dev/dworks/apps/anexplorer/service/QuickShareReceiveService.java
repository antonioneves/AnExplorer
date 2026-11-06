package dev.dworks.apps.anexplorer.service;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.List;
import java.util.Locale;

import dev.dworks.apps.anexplorer.DocumentsActivity;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.quickshare.QuickShareReceiver;
import dev.dworks.apps.anexplorer.setting.AppSettings;

/**
 * Keeps the TV visible to Quick Share / Nearby Share senders while the user wants it, with
 * {@link QuickShareReceiver} doing the receiving. This service asks the user about each
 * transfer, through a notification and a broadcast the open screens show, and reports results.
 */
public class QuickShareReceiveService extends Service implements QuickShareReceiver.Listener {

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
    public static final String EXTRA_PIN = "pin";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_TEXT = "text";

    /** The transfer awaiting consent, so a screen opened later can still ask (TV has no notification actions). */
    private static volatile Bundle sPendingIncoming;

    private static final String CHANNEL_ID = "quickshare_receive";
    private static final int NOTIFICATION_ID = 917;
    /** MediaStore's folder under Download, for the default destination */
    private static final String DOWNLOADS_FOLDER = "Quick Share";

    private QuickShareReceiver receiver;

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
        receiver = new QuickShareReceiver(this, destination, this);
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
            Bundle pending = sPendingIncoming;
            if (pending != null && pending.getInt(EXTRA_TRANSFER_ID) == transferId) {
                sPendingIncoming = null;
            }
            cancelIncomingNotification(transferId);
            receiver.respond(transferId, intent.getBooleanExtra(EXTRA_ACCEPT, false));
            return START_STICKY;
        }
        receiver.start();
        return START_STICKY;
    }

    private final QuickShareReceiver.Destination destination = new QuickShareReceiver.Destination() {
        @Override
        public File getFolder() {
            return AppSettings.getQuickShareFolder(QuickShareReceiveService.this);
        }

        @Override
        public String getDownloadsFolder() {
            return AppSettings.isDefaultQuickShareFolder(QuickShareReceiveService.this) ? DOWNLOADS_FOLDER : null;
        }
    };

    @Override
    public void onVisible() {
        updateForegroundNotification(getString(R.string.quickshare_receive_title),
                getString(R.string.quickshare_receive_visible));
        broadcast(new Intent(ACTION_QUICKSHARE_VISIBLE));
    }

    @Override
    public void onStartFailed(Exception error) {
        CrashReportingManager.logException(error);
        stopSelf();
    }

    @Override
    public void onIncoming(QuickShareReceiver.Incoming incoming) {
        String deviceName = deviceName(incoming.deviceName);
        String fileSummary = summarize(incoming);
        String notificationText = incoming.pin == null ? fileSummary
                : fileSummary + "\n" + getString(R.string.quickshare_receive_pin, incoming.pin);
        Intent acceptIntent = new Intent(this, QuickShareReceiveService.class);
        acceptIntent.setAction(ACTION_START);
        acceptIntent.putExtra(EXTRA_ACCEPT, true);
        acceptIntent.putExtra(EXTRA_TRANSFER_ID, incoming.transferId);
        PendingIntent acceptPi = PendingIntent.getService(this, incoming.transferId * 2,
                acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent rejectIntent = new Intent(this, QuickShareReceiveService.class);
        rejectIntent.setAction(ACTION_START);
        rejectIntent.putExtra(EXTRA_ACCEPT, false);
        rejectIntent.putExtra(EXTRA_TRANSFER_ID, incoming.transferId);
        PendingIntent rejectPi = PendingIntent.getService(this, incoming.transferId * 2 + 1,
                rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(String.format(Locale.US,
                        getString(R.string.quickshare_receive_incoming), deviceName))
                .setContentText(notificationText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(notificationText))
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
        nm.notify(NOTIFICATION_ID + incoming.transferId, notification);

        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_TRANSFER_ID, incoming.transferId);
        bundle.putString(EXTRA_DEVICE_NAME, deviceName);
        bundle.putString(EXTRA_SUMMARY, fileSummary);
        bundle.putString(EXTRA_PIN, incoming.pin);
        sPendingIncoming = bundle;
        broadcast(new Intent(ACTION_QUICKSHARE_INCOMING).putExtras(bundle));
    }

    @Override
    public void onReceiving(int transferId) {
        updateForegroundNotification(getString(R.string.quickshare_receive_title),
                getString(R.string.quickshare_receive_receiving));
    }

    @Override
    public void onReceived(int transferId, String deviceName, int fileCount) {
        if (fileCount > 0) {
            String name = deviceName(deviceName);
            String text = getString(R.string.quickshare_receive_saved, fileCount,
                    AppSettings.getFolderLabel(this, AppSettings.getQuickShareFolder(this)));
            showResultNotification(transferId,
                    String.format(Locale.US, getString(R.string.quickshare_receive_from), name), text);
            broadcast(new Intent(ACTION_QUICKSHARE_RECEIVED)
                    .putExtra(EXTRA_DEVICE_NAME, name)
                    .putExtra(EXTRA_COUNT, fileCount));
        }
        updateForegroundNotification(getString(R.string.quickshare_receive_title),
                getString(R.string.quickshare_receive_visible));
    }

    @Override
    public void onTextReceived(int transferId, String deviceName, String text) {
        String name = deviceName(deviceName);
        showResultNotification(transferId,
                String.format(Locale.US, getString(R.string.quickshare_receive_from), name), text);
        broadcast(new Intent(ACTION_QUICKSHARE_TEXT)
                .putExtra(EXTRA_DEVICE_NAME, name)
                .putExtra(EXTRA_TEXT, text));
    }

    @Override
    public void onFailed(int transferId, String deviceName) {
        String name = deviceName(deviceName);
        showResultNotification(transferId,
                String.format(Locale.US, getString(R.string.quickshare_receive_from), name),
                getString(R.string.quickshare_receive_failed));
        broadcast(new Intent(ACTION_QUICKSHARE_FAILED).putExtra(EXTRA_DEVICE_NAME, name));
    }

    @Override
    public void onFinished(int transferId) {
        Bundle pending = sPendingIncoming;
        if (pending != null && pending.getInt(EXTRA_TRANSFER_ID) == transferId) {
            sPendingIncoming = null;
            cancelIncomingNotification(transferId);
        }
    }

    /** Phones hide their name unless the TV is one of their contacts */
    private String deviceName(String name) {
        return name != null ? name : getString(R.string.quickshare_receive_unknown_device);
    }

    private String summarize(QuickShareReceiver.Incoming incoming) {
        int count = incoming.fileNames.size() + incoming.textTitles.size();
        if (count == 1 && incoming.fileNames.size() == 1) {
            return incoming.fileNames.get(0);
        }
        if (count == 1) {
            return incoming.textTitles.get(0);
        }
        return String.format(Locale.US, getString(R.string.quickshare_receive_nfiles), count);
    }

    private void broadcast(Intent intent) {
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
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
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_server)
                .setOngoing(true)
                .setContentIntent(openDocumentsPendingIntent())
                .addAction(R.drawable.ic_action_stop, getString(R.string.quickshare_receive_stop), stopPi)
                .build();
    }

    private void updateForegroundNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildForegroundNotification(title, text));
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

    @Override
    public void onDestroy() {
        receiver.stop();
        sPendingIncoming = null;
        broadcast(new Intent(ACTION_QUICKSHARE_STOPPED));
        super.onDestroy();
    }
}
