package dev.dworks.apps.anexplorer.protocol;

import com.google.android.gms.nearby.sharing.Protocol;
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto;
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionRequestFrame;
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame;
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OsInfo;
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame;
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader.PayloadType;
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.security.cryptauth.lib.securegcm.D2DConnectionContext;
import com.google.security.cryptauth.lib.securegcm.HandshakeException;
import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake;
import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake.AlertException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Inbound (responder) side of the Quick Share connection handshake and transfer.
 *
 * <p>Wire sequence for the receiver (state machine):
 * <ol>
 *   <li>Read {@code OfflineFrame}{@literal >ConnectionRequestFrame}; parse device info
 *       from {@code endpoint_info}.</li>
 *   <li>Read {@code Ukey2Message} (CLIENT_INIT) and answer with SERVER_INIT.</li>
 *   <li>Read {@code Ukey2Message} (CLIENT_FINISH), verify commitment, derive keys.</li>
 *   <li>Read the sender's {@code ConnectionResponseFrame}; answer with ours (ACCEPT) +
 *       {@code OsInfo}. Every frame after this is an encrypted {@code OfflineFrame}.</li>
 *   <li>Send {@code PairedKeyEncryptionFrame} (random placeholder).</li>
 *   <li>Read {@code PairedKeyEncryptionFrame}, answer with {@code PairedKeyResultFrame} (UNABLE).</li>
 *   <li>Read {@code PairedKeyResultFrame}.</li>
 *   <li>Read {@code IntroductionFrame} with the file list.</li>
 *   <li>On consent, send a {@code ConnectionResponseFrame} (ACCEPT) sharing frame.</li>
 *   <li>Receive FILE / BYTES payload chunks and stream them to the listener.</li>
 *   <li>Once every announced payload is complete, send a disconnection frame and close.</li>
 * </ol>
 *
 * <p>Sharing frames ({@link Protocol.Frame}) never travel bare: each one is the body of a
 * BYTES payload ({@code PayloadTransferFrame}, a data chunk followed by an empty
 * {@code LAST_CHUNK}) inside an encrypted {@code OfflineFrame}. Keep-alives may arrive at
 * any point and are acknowledged.
 *
 * <p>Clean-room implementation: protocol behavior derived from the public wire
 * format (Apache-2.0 Google protos) and the public Nearby Connections / UKEY2
 * specifications. Cryptographic primitives come from Google's Apache-2.0
 * {@code ukey2} library.
 */
public final class InboundConnection {

    /** Notifies the host app of protocol events. */
    public interface Listener {
        /** Device name parsed from the connection request. */
        void onConnectionRequested(String deviceName, EndpointInfo.DeviceType deviceType);

        /**
         * Called when the introduction frame arrives with the list of files.
         * The host should prompt the user and call {@link #accept()} or {@link #reject()}.
         */
        void onTransferIntroduced(Protocol.IntroductionFrame introduction);

        /** Called for each received chunk of an announced file. */
        void onPayloadChunk(long payloadId, byte[] body, int flags, long offset);

        /** Called with the content of an announced text item (plain text, URL, ...). */
        void onTextReceived(Protocol.TextMetadata metadata, String text);

        /** Called when every announced file and text item has been received. */
        void onTransferComplete();

        /**
         * Called once when the connection ends: {@code error} is null after a completed
         * transfer or a rejection, otherwise it says why the connection failed.
         */
        void onConnectionClosed(Throwable error);
    }

    private enum State {
        AWAIT_CONNECTION_REQUEST,
        AWAIT_UKEY2_CLIENT_INIT,
        AWAIT_UKEY2_CLIENT_FINISH,
        AWAIT_CONNECTION_RESPONSE,
        SENT_PAIRED_ENCRYPTION,
        SENT_PAIRED_RESULT,
        AWAIT_INTRODUCTION,
        AWAIT_CONSENT,
        RECEIVING,
        CLOSED
    }

    private static final int LAST_CHUNK = 0x1;
    private static final int MAX_BYTES_PAYLOAD = 1024 * 1024;

    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final Object writeLock = new Object();
    private final SecureRandom random = new SecureRandom();

    private volatile State state = State.AWAIT_CONNECTION_REQUEST;
    private Ukey2Handshake handshake;
    private D2DConnectionContext connectionContext;
    private boolean closedReported;

    /** BYTES payloads being reassembled, by payload id. */
    private final Map<Long, ByteArrayOutputStream> bytesPayloads = new HashMap<>();
    /** Announced files and texts not yet fully received. */
    private final Set<Long> pendingFiles = new HashSet<>();
    private final Map<Long, Protocol.TextMetadata> pendingTexts = new HashMap<>();

    public InboundConnection(InputStream in, OutputStream out, Listener listener) {
        this.in = in;
        this.out = out;
        this.listener = listener;
    }

    /**
     * Runs the full inbound protocol loop until the peer disconnects, a transfer
     * completes, or an unrecoverable error occurs.
     */
    public void run() {
        Throwable error = null;
        try {
            while (state != State.CLOSED) {
                byte[] raw = Framing.readFrame(in);
                if (raw == null) {
                    if (state != State.CLOSED) {
                        throw new IOException("Connection closed by peer");
                    }
                    break;
                }
                dispatch(raw);
            }
        } catch (Exception e) {
            // Once we closed on purpose (completed or rejected), the host tearing the
            // socket down is expected and not an error.
            if (state != State.CLOSED) {
                error = e;
            }
        }
        state = State.CLOSED;
        reportClosed(error);
    }

    private void dispatch(byte[] raw) throws Exception {
        switch (state) {
            case AWAIT_CONNECTION_REQUEST:
                handleConnectionRequest(raw);
                break;
            case AWAIT_UKEY2_CLIENT_INIT:
                handleUkey2ClientInit(raw);
                break;
            case AWAIT_UKEY2_CLIENT_FINISH:
                handleUkey2ClientFinish(raw);
                break;
            case AWAIT_CONNECTION_RESPONSE:
                handleConnectionResponse(raw);
                break;
            case CLOSED:
                break;
            default:
                handleEncryptedFrame(raw);
                break;
        }
    }

    private void handleConnectionRequest(byte[] raw) throws InvalidProtocolBufferException {
        OfflineFrame frame = OfflineFrame.parseFrom(raw);
        if (!frame.hasV1() || !frame.getV1().hasConnectionRequest()) {
            throw new ProtocolViolation("Expected connection_request frame");
        }
        ConnectionRequestFrame request = frame.getV1().getConnectionRequest();
        if (!request.hasEndpointInfo()) {
            throw new ProtocolViolation("connection_request missing endpoint_info");
        }
        EndpointInfo info = EndpointInfo.parse(request.getEndpointInfo().toByteArray());
        state = State.AWAIT_UKEY2_CLIENT_INIT;
        listener.onConnectionRequested(info.getDeviceName(), info.getDeviceType());
    }

    private void handleUkey2ClientInit(byte[] raw) throws HandshakeException, AlertException, IOException {
        handshake = Ukey2Handshake.forResponder(Ukey2Handshake.HandshakeCipher.P256_SHA512);
        handshake.parseHandshakeMessage(raw);
        writeRaw(handshake.getNextHandshakeMessage());
        state = State.AWAIT_UKEY2_CLIENT_FINISH;
    }

    private void handleUkey2ClientFinish(byte[] raw)
            throws HandshakeException, AlertException, SignatureException {
        handshake.parseHandshakeMessage(raw);
        // Quick Share does not use the UKEY2 out-of-band auth-string comparison
        // (the paired-key frame dance replaces it), but the ukey2 library only
        // marks the handshake finished after getVerificationString + verifyHandshake.
        handshake.getVerificationString(6);
        handshake.verifyHandshake();
        connectionContext = handshake.toConnectionContext();
        state = State.AWAIT_CONNECTION_RESPONSE;
    }

    private void handleConnectionResponse(byte[] raw) throws Exception {
        OfflineFrame frame = OfflineFrame.parseFrom(raw);
        if (frame.hasV1() && frame.getV1().getType() == V1Frame.FrameType.KEEP_ALIVE) {
            return;
        }
        if (!frame.hasV1() || !frame.getV1().hasConnectionResponse()) {
            throw new ProtocolViolation("Expected connection_response frame");
        }
        if (frame.getV1().getConnectionResponse().getResponse()
                != OfflineWireFormatsProto.ConnectionResponseFrame.ResponseStatus.ACCEPT) {
            throw new ProtocolViolation("Sender rejected the connection");
        }
        // The sender accepted; accept on our side too. This is the last plaintext frame.
        OfflineFrame response = OfflineFrame.newBuilder()
                .setVersion(OfflineFrame.Version.V1)
                .setV1(V1Frame.newBuilder()
                        .setType(V1Frame.FrameType.CONNECTION_RESPONSE)
                        .setConnectionResponse(
                                OfflineWireFormatsProto.ConnectionResponseFrame.newBuilder()
                                        .setStatus(0)
                                        .setResponse(
                                                OfflineWireFormatsProto.ConnectionResponseFrame
                                                        .ResponseStatus.ACCEPT)
                                        .setOsInfo(OsInfo.newBuilder()
                                                .setType(OsInfo.OsType.ANDROID))))
                .build();
        writeRaw(response.toByteArray());

        // Begin the paired-key handshake dance; we have no certificates to offer.
        state = State.SENT_PAIRED_ENCRYPTION;
        sendSharingFrame(Protocol.V1Frame.newBuilder()
                .setType(Protocol.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION)
                .setPairedKeyEncryption(Protocol.PairedKeyEncryptionFrame.newBuilder()
                        .setSecretIdHash(ByteString.copyFrom(randomBytes(6)))
                        .setSignedData(ByteString.copyFrom(randomBytes(72)))));
    }

    private void handleEncryptedFrame(byte[] raw) throws Exception {
        OfflineFrame frame = OfflineFrame.parseFrom(decrypt(raw));
        if (!frame.hasV1()) {
            return;
        }
        switch (frame.getV1().getType()) {
            case KEEP_ALIVE:
                if (!frame.getV1().getKeepAlive().getAck()) {
                    sendKeepAlive(true, frame.getV1().getKeepAlive().getSeqNum());
                }
                break;
            case DISCONNECTION:
                if (state == State.RECEIVING && isTransferDone()) {
                    finish();
                } else {
                    throw new IOException("Sender disconnected");
                }
                break;
            case PAYLOAD_TRANSFER:
                handlePayloadTransfer(frame.getV1().getPayloadTransfer());
                break;
            default:
                // Bandwidth upgrades and the like are optional; stay on this socket.
                break;
        }
    }

    private void handlePayloadTransfer(PayloadTransferFrame transfer) throws Exception {
        if (transfer.getPacketType() == PayloadTransferFrame.PacketType.CONTROL) {
            if (transfer.getControlMessage().getEvent()
                    == PayloadTransferFrame.ControlMessage.EventType.PAYLOAD_CANCELED) {
                throw new ProtocolViolation("Payload canceled by peer");
            }
            return;
        }
        if (transfer.getPacketType() != PayloadTransferFrame.PacketType.DATA) {
            return;
        }
        long payloadId = transfer.getPayloadHeader().getId();
        PayloadTransferFrame.PayloadChunk chunk = transfer.getPayloadChunk();
        boolean last = (chunk.getFlags() & LAST_CHUNK) != 0;

        if (transfer.getPayloadHeader().getType() == PayloadType.FILE) {
            if (state != State.RECEIVING || !pendingFiles.contains(payloadId)) {
                throw new ProtocolViolation("Unexpected file payload " + payloadId);
            }
            listener.onPayloadChunk(payloadId, chunk.getBody().toByteArray(),
                    chunk.getFlags(), chunk.getOffset());
            if (last) {
                pendingFiles.remove(payloadId);
                completeIfDone();
            }
            return;
        }

        // BYTES: reassemble, then it's either an announced text item or a sharing frame.
        ByteArrayOutputStream buffer = bytesPayloads.get(payloadId);
        if (buffer == null) {
            buffer = new ByteArrayOutputStream();
            bytesPayloads.put(payloadId, buffer);
        }
        if (buffer.size() + chunk.getBody().size() > MAX_BYTES_PAYLOAD) {
            throw new ProtocolViolation("Bytes payload too large");
        }
        chunk.getBody().writeTo(buffer);
        if (!last) {
            return;
        }
        bytesPayloads.remove(payloadId);
        byte[] body = buffer.toByteArray();

        Protocol.TextMetadata text = pendingTexts.remove(payloadId);
        if (text != null) {
            if (state != State.RECEIVING) {
                throw new ProtocolViolation("Text payload before consent");
            }
            listener.onTextReceived(text, new String(body, StandardCharsets.UTF_8));
            completeIfDone();
            return;
        }
        handleSharingFrame(Protocol.Frame.parseFrom(body));
    }

    private void handleSharingFrame(Protocol.Frame frame) throws Exception {
        if (!frame.hasV1()) {
            return;
        }
        Protocol.V1Frame v1 = frame.getV1();
        if (v1.getType() == Protocol.V1Frame.FrameType.CANCEL) {
            throw new ProtocolViolation("Transfer cancelled by sender");
        }
        switch (state) {
            case SENT_PAIRED_ENCRYPTION:
                if (v1.getType() == Protocol.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION) {
                    state = State.SENT_PAIRED_RESULT;
                    sendSharingFrame(Protocol.V1Frame.newBuilder()
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
                    state = State.AWAIT_CONSENT;
                    listener.onTransferIntroduced(introduction);
                }
                break;
            default:
                break;
        }
    }

    private boolean isTransferDone() {
        return pendingFiles.isEmpty() && pendingTexts.isEmpty();
    }

    private void completeIfDone() throws Exception {
        if (state == State.RECEIVING && isTransferDone()) {
            sendOfflineFrame(V1Frame.newBuilder()
                    .setType(V1Frame.FrameType.DISCONNECTION)
                    .setDisconnection(OfflineWireFormatsProto.DisconnectionFrame.newBuilder()));
            finish();
        }
    }

    private void finish() {
        state = State.CLOSED;
        listener.onTransferComplete();
    }

    /**
     * The user accepted the offered transfer: send the accept response and enter
     * the receiving state.
     */
    public void accept() {
        if (state != State.AWAIT_CONSENT) {
            return;
        }
        try {
            state = State.RECEIVING;
            sendSharingFrame(Protocol.V1Frame.newBuilder()
                    .setType(Protocol.V1Frame.FrameType.RESPONSE)
                    .setConnectionResponse(Protocol.ConnectionResponseFrame.newBuilder()
                            .setStatus(Protocol.ConnectionResponseFrame.Status.ACCEPT)));
        } catch (Exception e) {
            state = State.CLOSED;
            reportClosed(e);
        }
    }

    /**
     * The user rejected the offered transfer: send a reject response and close.
     */
    public void reject() {
        if (state != State.AWAIT_CONSENT) {
            return;
        }
        try {
            sendSharingFrame(Protocol.V1Frame.newBuilder()
                    .setType(Protocol.V1Frame.FrameType.RESPONSE)
                    .setConnectionResponse(Protocol.ConnectionResponseFrame.newBuilder()
                            .setStatus(Protocol.ConnectionResponseFrame.Status.REJECT)));
            sendOfflineFrame(V1Frame.newBuilder()
                    .setType(V1Frame.FrameType.DISCONNECTION)
                    .setDisconnection(OfflineWireFormatsProto.DisconnectionFrame.newBuilder()));
        } catch (Exception ignored) {
            // The sender may already be gone; we are closing either way.
        } finally {
            state = State.CLOSED;
        }
    }

    /** Sends a keep-alive frame on the (encrypted) channel. */
    public void sendKeepAlive(boolean ack, int seqNum) {
        if (connectionContext == null) {
            return;
        }
        try {
            sendOfflineFrame(V1Frame.newBuilder()
                    .setType(V1Frame.FrameType.KEEP_ALIVE)
                    .setKeepAlive(OfflineWireFormatsProto.KeepAliveFrame.newBuilder()
                            .setAck(ack)
                            .setSeqNum(seqNum)));
        } catch (Exception ignored) {
        }
    }

    /** Wraps a sharing frame in a BYTES payload: one data chunk, then an empty last chunk. */
    private void sendSharingFrame(Protocol.V1Frame.Builder v1) throws Exception {
        byte[] body = Protocol.Frame.newBuilder()
                .setVersion(Protocol.Frame.Version.V1)
                .setV1(v1)
                .build()
                .toByteArray();
        PayloadTransferFrame.PayloadHeader header = PayloadTransferFrame.PayloadHeader.newBuilder()
                .setId(random.nextLong())
                .setType(PayloadType.BYTES)
                .setTotalSize(body.length)
                .setIsSensitive(false)
                .build();
        synchronized (writeLock) {
            sendOfflineFrame(V1Frame.newBuilder()
                    .setType(V1Frame.FrameType.PAYLOAD_TRANSFER)
                    .setPayloadTransfer(PayloadTransferFrame.newBuilder()
                            .setPacketType(PayloadTransferFrame.PacketType.DATA)
                            .setPayloadHeader(header)
                            .setPayloadChunk(PayloadTransferFrame.PayloadChunk.newBuilder()
                                    .setOffset(0)
                                    .setFlags(0)
                                    .setBody(ByteString.copyFrom(body)))));
            sendOfflineFrame(V1Frame.newBuilder()
                    .setType(V1Frame.FrameType.PAYLOAD_TRANSFER)
                    .setPayloadTransfer(PayloadTransferFrame.newBuilder()
                            .setPacketType(PayloadTransferFrame.PacketType.DATA)
                            .setPayloadHeader(header)
                            .setPayloadChunk(PayloadTransferFrame.PayloadChunk.newBuilder()
                                    .setOffset(body.length)
                                    .setFlags(LAST_CHUNK))));
        }
    }

    private void sendOfflineFrame(V1Frame.Builder v1) throws Exception {
        byte[] frame = OfflineFrame.newBuilder()
                .setVersion(OfflineFrame.Version.V1)
                .setV1(v1)
                .build()
                .toByteArray();
        synchronized (writeLock) {
            // Encoding advances the D2D sequence number, so it must stay in send order.
            Framing.writeFrame(out, connectionContext.encodeMessageToPeer(frame));
        }
    }

    private void writeRaw(byte[] frame) throws IOException {
        synchronized (writeLock) {
            Framing.writeFrame(out, frame);
        }
    }

    private byte[] decrypt(byte[] raw) throws SignatureException {
        return connectionContext.decodeMessageFromPeer(raw);
    }

    private synchronized void reportClosed(Throwable error) {
        if (closedReported) {
            return;
        }
        closedReported = true;
        listener.onConnectionClosed(error);
    }

    /** Signals a protocol-level error (peer sent something we cannot understand). */
    public static final class ProtocolViolation extends RuntimeException {
        public ProtocolViolation(String message) {
            super(message);
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
