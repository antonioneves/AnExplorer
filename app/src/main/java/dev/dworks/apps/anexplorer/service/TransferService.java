package dev.dworks.apps.anexplorer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import dev.dworks.apps.anexplorer.DocumentsActivity;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.DocumentCopier;
import dev.dworks.apps.anexplorer.misc.FileUtils;
import dev.dworks.apps.anexplorer.misc.TransferControl;
import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.model.DocumentsContract;
import dev.dworks.apps.anexplorer.setting.AppSettings;

/**
 * Copies and moves files in the background, so a long transfer from a server carries on when
 * the app is left, and shows its progress in a notification that can pause, resume or cancel it.
 * Transfers wait in line and run one at a time.
 */
public class TransferService extends Service {

    private static final String TAG = TransferService.class.getSimpleName();

    private static final String ACTION_START = "dev.dworks.apps.anexplorer.action.START_TRANSFER";
    private static final String ACTION_PAUSE = "dev.dworks.apps.anexplorer.action.PAUSE_TRANSFER";
    private static final String ACTION_RESUME = "dev.dworks.apps.anexplorer.action.RESUME_TRANSFER";
    private static final String ACTION_CANCEL = "dev.dworks.apps.anexplorer.action.CANCEL_TRANSFER";
    /** Sent to the app when a transfer has ended, for it to refresh its folder */
    public static final String ACTION_TRANSFER_DONE = "dev.dworks.apps.anexplorer.action.TRANSFER_DONE";
    public static final String EXTRA_FAILED = "failed";
    public static final String EXTRA_CANCELLED = "cancelled";
    /** Sent about twice a second while a transfer runs, with how much is done of how much */
    public static final String ACTION_TRANSFER_PROGRESS = "dev.dworks.apps.anexplorer.action.TRANSFER_PROGRESS";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_TOTAL = "total";
    /** How many more transfers are waiting for this one to end */
    public static final String EXTRA_QUEUED = "queued";

    private static final String EXTRA_DOCS = "docs";
    private static final String EXTRA_TARGET = "target";
    private static final String EXTRA_MOVE = "move";

    private static final String CHANNEL_ID = "transfers";
    private static final int NOTIFICATION_ID = 919;
    private static final int RESULT_NOTIFICATION_ID = 920;
    private static final long REFRESH_MS = 500;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger waiting = new AtomicInteger();
    /** What each transfer in line is, as shown in the notification; the one running has left it */
    private final CopyOnWriteArrayList<String> queue = new CopyOnWriteArrayList<>();
    private volatile TransferControl running;
    private volatile int total;
    private boolean moving;

    public static void start(Context context, ArrayList<DocumentInfo> docs, DocumentInfo target,
                             boolean move) {
        final Intent intent = new Intent(context, TransferService.class).setAction(ACTION_START)
                .putParcelableArrayListExtra(EXTRA_DOCS, docs)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_MOVE, move);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** Pause, resume or cancel what is running, for the buttons in the app */
    public static void pause(Context context) {
        context.startService(new Intent(context, TransferService.class).setAction(ACTION_PAUSE));
    }

    public static void resume(Context context) {
        context.startService(new Intent(context, TransferService.class).setAction(ACTION_RESUME));
    }

    public static void cancel(Context context) {
        context.startService(new Intent(context, TransferService.class).setAction(ACTION_CANCEL));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = null == intent ? null : intent.getAction();
        final TransferControl control = running;
        if (ACTION_PAUSE.equals(action) && null != control) {
            control.pause();
            update();
        } else if (ACTION_RESUME.equals(action) && null != control) {
            control.resume();
            update();
        } else if (ACTION_CANCEL.equals(action) && null != control) {
            control.cancel();
        } else if (ACTION_START.equals(action)) {
            final ArrayList<DocumentInfo> docs = intent.getParcelableArrayListExtra(EXTRA_DOCS);
            final DocumentInfo target = intent.getParcelableExtra(EXTRA_TARGET);
            final boolean move = intent.getBooleanExtra(EXTRA_MOVE, false);
            if (null == docs || null == target) {
                stopIfIdle();
                return START_NOT_STICKY;
            }
            createChannel();
            show(buildNotification(getString(R.string.transfer_starting), null, false, 0, 0, 0));
            waiting.incrementAndGet();
            final String label = getString(R.string.transfer_queue_item,
                    docs.size() == 1 ? docs.get(0).displayName : FileUtils.formatFileCount(docs.size()),
                    target.displayName);
            queue.add(label);
            executor.execute(() -> run(docs, target, move, label));
        } else {
            stopIfIdle();
        }
        return START_NOT_STICKY;
    }

    private void show(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void run(ArrayList<DocumentInfo> docs, DocumentInfo target, boolean move, String label) {
        queue.remove(label);
        final TransferControl control = new TransferControl();
        running = control;
        moving = move;
        total = docs.size();
        for (DocumentInfo doc : docs) {
            if (!doc.isDirectory()) {
                control.addTotal(doc.size);
            }
        }
        final ContentResolver resolver = getContentResolver();
        final DocumentCopier copier = new DocumentCopier(resolver, control);
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                update();
                handler.postDelayed(this, REFRESH_MS);
            }
        };
        handler.post(refresh);

        boolean failed = false;
        int finished = 0;
        for (DocumentInfo doc : docs) {
            if (control.isCancelled()) {
                failed = true;
                break;
            }
            try {
                if (!DocumentCopier.copiesItself(doc, target, move)
                        || DocumentCopier.streamsForProgress(doc, move)) {
                    // Another storage, or a local copy that is streamed to show its progress
                    failed |= !copier.copy(doc, target.derivedUri, move);
                } else if (move) {
                    failed |= DocumentsContract.moveDocument(resolver, doc.derivedUri, null,
                            target.derivedUri) == null;
                } else {
                    failed |= DocumentsContract.copyDocument(resolver, doc.derivedUri,
                            target.derivedUri) == null;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to transfer " + doc, e);
                failed = true;
                CrashReportingManager.logException(e);
            }
            finished++;
            done = finished;
        }
        final boolean cancelled = control.isCancelled();
        final boolean hadTrouble = failed;
        handler.removeCallbacks(refresh);
        handler.post(() -> {
            handler.removeCallbacks(refresh);
            running = null;
            done = 0;
            sendBroadcast(new Intent(ACTION_TRANSFER_DONE).setPackage(getPackageName())
                    .putExtra(EXTRA_FAILED, hadTrouble).putExtra(EXTRA_CANCELLED, cancelled));
            notifyResult(hadTrouble, cancelled, move);
            if (waiting.decrementAndGet() == 0) {
                stopForeground(true);
                stopSelf();
            }
        });
    }

    private volatile int done;

    private void update() {
        final TransferControl control = running;
        if (null == control) {
            return;
        }
        final String name = control.getCurrent();
        final String text = control.isPaused()
                ? getString(R.string.transfer_paused)
                : getString(moving ? R.string.transfer_moving : R.string.transfer_copying,
                        name.isEmpty() ? "…" : name);
        final String bytes = android.text.format.Formatter.formatShortFileSize(this, control.getDone());
        // Folders are listed as they are copied, so the total can grow; never show more than whole
        final long all = Math.max(control.getTotal(), control.getDone());
        final StringBuilder detail = new StringBuilder(
                getString(R.string.transfer_progress, done, total, bytes));
        if (!queue.isEmpty()) {
            detail.append("\n").append(getString(R.string.transfer_waiting));
            for (String waitingLabel : queue) {
                detail.append("\n").append(waitingLabel);
            }
        }
        final Notification notification = buildNotification(text, detail.toString(), control.isPaused(), 1,
                control.getDone(), all);
        sendBroadcast(new Intent(ACTION_TRANSFER_PROGRESS).setPackage(getPackageName())
                .putExtra(EXTRA_DONE, control.getDone()).putExtra(EXTRA_TOTAL, all)
                .putExtra(EXTRA_QUEUED, queue.size()));
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,
                notification);
    }

    private Notification buildNotification(String title, String detail, boolean paused, int actions,
                                          long doneBytes, long totalBytes) {
        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, DocumentsActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_server)
                .setContentTitle(title)
                .setContentText(detail == null ? null : detail.split("\n", 2)[0])
                .setStyle(new NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(AppSettings.getPrimaryColor())
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(totalBytes > 0 ? 1000 : 0,
                        totalBytes > 0 ? (int) (doneBytes * 1000 / totalBytes) : 0, totalBytes <= 0);
        if (actions > 0) {
            builder.addAction(0, getString(paused ? R.string.transfer_resume : R.string.transfer_pause),
                    action(paused ? ACTION_RESUME : ACTION_PAUSE));
            builder.addAction(0, getString(R.string.transfer_cancel), action(ACTION_CANCEL));
        }
        return builder.build();
    }

    private PendingIntent action(String action) {
        return PendingIntent.getService(this, action.hashCode(),
                new Intent(this, TransferService.class).setAction(action), PendingIntent.FLAG_IMMUTABLE);
    }

    private void notifyResult(boolean failed, boolean cancelled, boolean move) {
        final String title = cancelled ? getString(R.string.transfer_cancelled)
                : failed ? getString(R.string.transfer_failed)
                : getString(move ? R.string.transfer_moved : R.string.transfer_copied);
        final Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_server)
                .setContentTitle(title)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, DocumentsActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .setColor(AppSettings.getPrimaryColor())
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(RESULT_NOTIFICATION_ID, notification);
    }

    private void stopIfIdle() {
        if (waiting.get() == 0) {
            stopSelf();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
