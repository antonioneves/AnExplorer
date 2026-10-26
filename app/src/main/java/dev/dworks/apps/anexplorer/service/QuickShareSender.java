package dev.dworks.apps.anexplorer.service;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;
import com.google.android.gms.nearby.sharing.Protocol;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.protocol.EndpointInfo;

/**
 * Sends files with Quick Share: finds phones whose Quick Share is ready to receive, through
 * Play services' Nearby Connections under Quick Share's own service id, and speaks the sharing
 * frames {@link QuickShareReceiveService} answers, from the other side. One transfer per
 * instance; everything runs on the main thread.
 */
public class QuickShareSender {

    public enum Status {
        SEARCHING, CONNECTING, WAITING, SENDING, SENT, DECLINED, FAILED
    }

    /** A phone or computer that is ready to receive */
    public static final class Device {
        public final String endpointId;
        /** Null when the device hides its name from non-contacts */
        public final String name;
        public final EndpointInfo.DeviceType type;

        Device(String endpointId, String name, EndpointInfo.DeviceType type) {
            this.endpointId = endpointId;
            this.name = name;
            this.type = type;
        }
    }

    /** A file to send, as a document the app can open */
    public static final class SendFile {
        public final Uri uri;
        public final String name;
        public final String mimeType;
        public final long size;

        public SendFile(Uri uri, String name, String mimeType, long size) {
            this.uri = uri;
            this.name = name;
            this.mimeType = mimeType;
            this.size = size;
        }
    }

    public interface Listener {
        void onDevicesChanged(List<Device> devices);

        /** Progress is the percentage sent, while sending */
        void onStatusChanged(Status status, int progress);
    }

    private static final String TAG = "QuickShareSend";
    private static final String SERVICE_ID = "NearbySharing";

    private final Context context;
    private final ConnectionsClient connectionsClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final List<SendFile> files;
    private final Listener listener;
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private boolean discovering;
    /** Set once the screen is gone; nothing is reported after that */
    private boolean closed;

    private Status status = Status.SEARCHING;
    private Device target;
    private String pin;
    private boolean connected;
    private boolean sentPairedResult;
    private boolean gotPairedResult;
    private boolean introduced;
    /** File payloads by id, with how much of each has gone */
    private final Map<Long, Payload> payloads = new LinkedHashMap<>();
    private final Map<Long, Long> sentBytes = new HashMap<>();
    private final Set<Long> finished = new HashSet<>();
    private final List<ParcelFileDescriptor> descriptors = new ArrayList<>();
    private long totalBytes;

    public QuickShareSender(Context context, List<SendFile> files, Listener listener) {
        this.context = context.getApplicationContext();
        this.connectionsClient = Nearby.getConnectionsClient(context);
        this.files = files;
        this.listener = listener;
    }

    public Status getStatus() {
        return status;
    }

    /** What the receiver shows while it asks to accept; null until connected */
    public String getPin() {
        return pin;
    }

    public Device getTarget() {
        return target;
    }

    public void startDiscovery() {
        if (discovering) {
            return;
        }
        discovering = true;
        connectionsClient.startDiscovery(SERVICE_ID, discoveryCallback,
                        new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Discovery failed", e);
                    CrashReportingManager.logException(e);
                    discovering = false;
                    setStatus(Status.FAILED);
                });
    }

    private void stopDiscovery() {
        if (discovering) {
            connectionsClient.stopDiscovery();
            discovering = false;
        }
    }

    private final EndpointDiscoveryCallback discoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            String name = null;
            byte[] raw = info.getEndpointInfo();
            // Bits 1-3 of the first byte; phones hide their name, even from everyone
            EndpointInfo.DeviceType type = raw != null && raw.length > 0
                    ? EndpointInfo.DeviceType.fromValue((raw[0] >> 1) & 0x07) : EndpointInfo.DeviceType.UNKNOWN;
            try {
                name = EndpointInfo.parse(raw).getDeviceName();
            } catch (IllegalArgumentException ignored) {
                // No name in it; only the sender's own contacts can decrypt who it is
            }
            if (closed) {
                return;
            }
            Log.i(TAG, "Found " + name + " (" + type + ")");
            devices.put(endpointId, new Device(endpointId,
                    name == null || name.trim().isEmpty() ? null : name, type));
            listener.onDevicesChanged(new ArrayList<>(devices.values()));
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            if (devices.remove(endpointId) != null && !closed) {
                listener.onDevicesChanged(new ArrayList<>(devices.values()));
            }
        }
    };

    /** Offers the files to a device; it asks its user, then they are sent */
    public void send(Device device) {
        if (target != null) {
            return;
        }
        target = device;
        stopDiscovery();
        setStatus(Status.CONNECTING);
        byte[] endpointInfo = EndpointInfo.serialize(QuickShareReceiveService.getDeviceName(context),
                EndpointInfo.DeviceType.LAPTOP);
        connectionsClient.requestConnection(endpointInfo, device.endpointId, lifecycleCallback)
                .addOnFailureListener(this::fail);
    }

    private final ConnectionLifecycleCallback lifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            pin = QuickShareReceiveService.pinCode(info.getRawAuthenticationToken());
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                    .addOnFailureListener(QuickShareSender.this::fail);
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (!result.getStatus().isSuccess()) {
                fail(new IOException("Connection failed: " + result.getStatus()));
                return;
            }
            connected = true;
            Log.i(TAG, "Connected to " + target.name + ", PIN " + pin);
            // Shown from now on, while the receiver asks its user
            setStatus(Status.CONNECTING);
            // We have no certificates to offer; receivers carry on without them
            sendFrame(Protocol.V1Frame.newBuilder()
                    .setType(Protocol.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION)
                    .setPairedKeyEncryption(Protocol.PairedKeyEncryptionFrame.newBuilder()
                            .setSecretIdHash(ByteString.copyFrom(randomBytes(6)))
                            .setSignedData(ByteString.copyFrom(randomBytes(72)))));
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            connected = false;
            Log.i(TAG, "Disconnected in state " + status);
            if (status != Status.SENT && status != Status.DECLINED && status != Status.FAILED) {
                fail(new IOException("Receiver went away"));
            }
            closeDescriptors();
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() != Payload.Type.BYTES || payload.asBytes() == null) {
                return;
            }
            try {
                onFrame(Protocol.Frame.parseFrom(payload.asBytes()));
            } catch (IOException e) {
                Log.w(TAG, "Unreadable frame", e);
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
            long payloadId = update.getPayloadId();
            if (!payloads.containsKey(payloadId) || status != Status.SENDING) {
                return;
            }
            int state = update.getStatus();
            if (state == PayloadTransferUpdate.Status.FAILURE || state == PayloadTransferUpdate.Status.CANCELED) {
                fail(new IOException("File transfer " + (state == PayloadTransferUpdate.Status.CANCELED
                        ? "cancelled" : "failed")));
                return;
            }
            sentBytes.put(payloadId, update.getBytesTransferred());
            if (state == PayloadTransferUpdate.Status.SUCCESS) {
                finished.add(payloadId);
            }
            long sent = 0;
            for (long bytes : sentBytes.values()) {
                sent += bytes;
            }
            if (finished.size() == payloads.size()) {
                Log.i(TAG, "Sent " + payloads.size() + " files to " + target.name);
                setStatus(Status.SENT);
                // The receiver hangs up once it has everything; don't wait on it forever
                mainHandler.postDelayed(QuickShareSender.this::disconnect, 3000);
            } else {
                int progress = totalBytes > 0 ? (int) Math.min(99, sent * 100 / totalBytes) : 0;
                if (!closed) {
                    listener.onStatusChanged(status, progress);
                }
            }
        }
    };

    private void onFrame(Protocol.Frame frame) {
        if (!frame.hasV1()) {
            return;
        }
        Protocol.V1Frame v1 = frame.getV1();
        switch (v1.getType()) {
            case PAIRED_KEY_ENCRYPTION:
                sentPairedResult = true;
                sendFrame(Protocol.V1Frame.newBuilder()
                        .setType(Protocol.V1Frame.FrameType.PAIRED_KEY_RESULT)
                        .setPairedKeyResult(Protocol.PairedKeyResultFrame.newBuilder()
                                .setStatus(Protocol.PairedKeyResultFrame.Status.UNABLE)));
                introduceIfReady();
                break;
            case PAIRED_KEY_RESULT:
                gotPairedResult = true;
                introduceIfReady();
                break;
            case RESPONSE:
                Protocol.ConnectionResponseFrame.Status answer = v1.getConnectionResponse().getStatus();
                Log.i(TAG, target.name + " answered " + answer);
                if (answer == Protocol.ConnectionResponseFrame.Status.ACCEPT) {
                    sendFiles();
                } else if (answer == Protocol.ConnectionResponseFrame.Status.REJECT) {
                    setStatus(Status.DECLINED);
                    disconnect();
                } else {
                    fail(new IOException("Receiver answered " + answer));
                }
                break;
            case CANCEL:
                fail(new IOException("Transfer cancelled by receiver"));
                break;
            default:
                break;
        }
    }

    /** Lists the files once both sides are past the paired key frames */
    private void introduceIfReady() {
        if (!sentPairedResult || !gotPairedResult || introduced) {
            return;
        }
        introduced = true;
        Protocol.IntroductionFrame.Builder introduction = Protocol.IntroductionFrame.newBuilder();
        try {
            for (SendFile file : files) {
                ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(file.uri, "r");
                if (pfd == null) {
                    throw new IOException("Can't open " + file.uri);
                }
                descriptors.add(pfd);
                Payload payload = Payload.fromFile(pfd);
                long size = pfd.getStatSize() >= 0 ? pfd.getStatSize() : file.size;
                payloads.put(payload.getId(), payload);
                totalBytes += Math.max(size, 0);
                introduction.addFileMetadata(Protocol.FileMetadata.newBuilder()
                        .setName(file.name)
                        .setType(fileType(file.mimeType))
                        .setPayloadId(payload.getId())
                        .setSize(size)
                        .setMimeType(file.mimeType)
                        .setId(random.nextLong()));
            }
        } catch (IOException | SecurityException e) {
            fail(e);
            return;
        }
        sendFrame(Protocol.V1Frame.newBuilder()
                .setType(Protocol.V1Frame.FrameType.INTRODUCTION)
                .setIntroduction(introduction));
        setStatus(Status.WAITING);
    }

    private void sendFiles() {
        setStatus(Status.SENDING);
        for (Payload payload : payloads.values()) {
            connectionsClient.sendPayload(target.endpointId, payload).addOnFailureListener(this::fail);
        }
    }

    private static Protocol.FileMetadata.Type fileType(String mimeType) {
        if (mimeType == null) {
            return Protocol.FileMetadata.Type.UNKNOWN;
        } else if (mimeType.startsWith("image/")) {
            return Protocol.FileMetadata.Type.IMAGE;
        } else if (mimeType.startsWith("video/")) {
            return Protocol.FileMetadata.Type.VIDEO;
        } else if (mimeType.startsWith("audio/")) {
            return Protocol.FileMetadata.Type.AUDIO;
        } else if (mimeType.equals("application/vnd.android.package-archive")) {
            return Protocol.FileMetadata.Type.ANDROID_APP;
        }
        return Protocol.FileMetadata.Type.UNKNOWN;
    }

    /** Stops looking, and tells the receiver if a transfer was under way */
    public void cancel() {
        closed = true;
        stopDiscovery();
        mainHandler.removeCallbacksAndMessages(null);
        if (target == null) {
            return;
        }
        if (connected && (status == Status.WAITING || status == Status.SENDING || status == Status.CONNECTING)) {
            for (long payloadId : payloads.keySet()) {
                connectionsClient.cancelPayload(payloadId);
            }
            sendFrame(Protocol.V1Frame.newBuilder().setType(Protocol.V1Frame.FrameType.CANCEL));
            status = Status.FAILED;
            // Give the cancel frame a moment to leave
            mainHandler.postDelayed(this::disconnect, 500);
        } else {
            disconnect();
        }
    }

    private void disconnect() {
        if (target != null) {
            connectionsClient.disconnectFromEndpoint(target.endpointId);
        }
        connected = false;
        closeDescriptors();
    }

    private void fail(Exception error) {
        if (status == Status.SENT || status == Status.DECLINED || status == Status.FAILED) {
            return;
        }
        Log.w(TAG, "Sending failed", error);
        setStatus(Status.FAILED);
        disconnect();
    }

    private void closeDescriptors() {
        for (ParcelFileDescriptor pfd : descriptors) {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        }
        descriptors.clear();
    }

    private void setStatus(Status status) {
        this.status = status;
        if (!closed) {
            listener.onStatusChanged(status, 0);
        }
    }

    private void sendFrame(Protocol.V1Frame.Builder v1) {
        byte[] body = Protocol.Frame.newBuilder()
                .setVersion(Protocol.Frame.Version.V1)
                .setV1(v1)
                .build()
                .toByteArray();
        connectionsClient.sendPayload(target.endpointId, Payload.fromBytes(body))
                .addOnFailureListener(this::fail);
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
