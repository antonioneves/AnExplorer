package dev.dworks.apps.anexplorer.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.android.gms.nearby.sharing.Protocol;
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto;
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionRequestFrame;
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame;
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame;
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader.PayloadType;
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame;
import com.google.protobuf.ByteString;
import com.google.security.cryptauth.lib.securegcm.D2DConnectionContext;
import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end test of {@link InboundConnection} against a simulated Quick Share
 * initiator (the sender phone) speaking the public wire protocol: plaintext
 * connection frames and UKEY2, then encrypted {@code OfflineFrame}s where every
 * sharing frame travels as a BYTES payload.
 */
public class InboundConnectionTest {

    private static final long FILE_PAYLOAD_ID = 1001;
    private static final long TEXT_PAYLOAD_ID = 1002;

    /** Simulates the sender phone speaking the initiator side of the protocol. */
    private static final class SimulatedSender {
        private final InputStream in;
        private final OutputStream out;
        private Ukey2Handshake handshake;
        private D2DConnectionContext context;
        private long nextBytesPayloadId = 500;

        SimulatedSender(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        /** Connection request, UKEY2, and the plaintext connection response exchange. */
        void connect() throws Exception {
            handshake = Ukey2Handshake.forInitiator(Ukey2Handshake.HandshakeCipher.P256_SHA512);

            OfflineFrame request = OfflineFrame.newBuilder()
                    .setVersion(OfflineFrame.Version.V1)
                    .setV1(V1Frame.newBuilder()
                            .setType(V1Frame.FrameType.CONNECTION_REQUEST)
                            .setConnectionRequest(ConnectionRequestFrame.newBuilder()
                                    .setEndpointId("ABCD")
                                    .setEndpointName("Pixel 9")
                                    .setEndpointInfo(ByteString.copyFrom(
                                            EndpointInfo.serialize("Pixel 9", EndpointInfo.DeviceType.PHONE)))
                                    .addMediums(ConnectionRequestFrame.Medium.WIFI_LAN)))
                    .build();
            Framing.writeFrame(out, request.toByteArray());

            Framing.writeFrame(out, handshake.getNextHandshakeMessage());
            byte[] serverInit = Framing.readFrame(in);
            assertNotNull("Expected SERVER_INIT", serverInit);
            handshake.parseHandshakeMessage(serverInit);
            Framing.writeFrame(out, handshake.getNextHandshakeMessage());
            handshake.getVerificationString(6);
            handshake.verifyHandshake();
            context = handshake.toConnectionContext();

            // The sender accepts first, then waits for the receiver's acceptance.
            Framing.writeFrame(out, connectionResponse().toByteArray());
            byte[] responseRaw = Framing.readFrame(in);
            assertNotNull("Expected connection_response", responseRaw);
            OfflineFrame response = OfflineFrame.parseFrom(responseRaw);
            assertTrue(response.getV1().hasConnectionResponse());
            assertEquals(OfflineWireFormatsProto.ConnectionResponseFrame.ResponseStatus.ACCEPT,
                    response.getV1().getConnectionResponse().getResponse());
        }

        /** Paired-key dance, then the introduction for one file and one text item. */
        void pairAndIntroduce(byte[] fileContent, String text) throws Exception {
            Protocol.Frame theirEncryption = readSharingFrame();
            assertEquals(Protocol.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION, theirEncryption.getV1().getType());
            sendSharingFrame(Protocol.V1Frame.newBuilder()
                    .setType(Protocol.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION)
                    .setPairedKeyEncryption(Protocol.PairedKeyEncryptionFrame.newBuilder()
                            .setSecretIdHash(ByteString.copyFrom(new byte[6]))
                            .setSignedData(ByteString.copyFrom(new byte[72]))));

            Protocol.Frame theirResult = readSharingFrame();
            assertEquals(Protocol.V1Frame.FrameType.PAIRED_KEY_RESULT, theirResult.getV1().getType());
            sendSharingFrame(Protocol.V1Frame.newBuilder()
                    .setType(Protocol.V1Frame.FrameType.PAIRED_KEY_RESULT)
                    .setPairedKeyResult(Protocol.PairedKeyResultFrame.newBuilder()
                            .setStatus(Protocol.PairedKeyResultFrame.Status.UNABLE)));

            sendSharingFrame(Protocol.V1Frame.newBuilder()
                    .setType(Protocol.V1Frame.FrameType.INTRODUCTION)
                    .setIntroduction(Protocol.IntroductionFrame.newBuilder()
                            .addFileMetadata(Protocol.FileMetadata.newBuilder()
                                    .setName("photo.jpg")
                                    .setMimeType("image/jpeg")
                                    .setPayloadId(FILE_PAYLOAD_ID)
                                    .setSize(fileContent.length))
                            .addTextMetadata(Protocol.TextMetadata.newBuilder()
                                    .setTextTitle("link")
                                    .setType(Protocol.TextMetadata.Type.URL)
                                    .setPayloadId(TEXT_PAYLOAD_ID)
                                    .setSize(text.length()))));
        }

        /** Sends the file in two chunks plus the last-chunk marker, and the text item. */
        void sendPayloads(byte[] fileContent, String text) throws Exception {
            int half = fileContent.length / 2;
            sendChunk(FILE_PAYLOAD_ID, PayloadType.FILE, fileContent.length, 0,
                    java.util.Arrays.copyOfRange(fileContent, 0, half), false);
            sendKeepAlive();
            sendChunk(FILE_PAYLOAD_ID, PayloadType.FILE, fileContent.length, half,
                    java.util.Arrays.copyOfRange(fileContent, half, fileContent.length), false);
            sendChunk(FILE_PAYLOAD_ID, PayloadType.FILE, fileContent.length, fileContent.length,
                    new byte[0], true);
            byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
            sendChunk(TEXT_PAYLOAD_ID, PayloadType.BYTES, textBytes.length, 0, textBytes, false);
            sendChunk(TEXT_PAYLOAD_ID, PayloadType.BYTES, textBytes.length, textBytes.length,
                    new byte[0], true);
        }

        void sendKeepAlive() throws Exception {
            sendOfflineFrame(V1Frame.newBuilder()
                    .setType(V1Frame.FrameType.KEEP_ALIVE)
                    .setKeepAlive(OfflineWireFormatsProto.KeepAliveFrame.newBuilder().setAck(false)));
        }

        void sendSharingFrame(Protocol.V1Frame.Builder v1) throws Exception {
            byte[] body = Protocol.Frame.newBuilder()
                    .setVersion(Protocol.Frame.Version.V1)
                    .setV1(v1)
                    .build()
                    .toByteArray();
            long id = nextBytesPayloadId++;
            sendChunk(id, PayloadType.BYTES, body.length, 0, body, false);
            sendChunk(id, PayloadType.BYTES, body.length, body.length, new byte[0], true);
        }

        private void sendChunk(long id, PayloadType type, long total, long offset, byte[] body,
                boolean last) throws Exception {
            sendOfflineFrame(V1Frame.newBuilder()
                    .setType(V1Frame.FrameType.PAYLOAD_TRANSFER)
                    .setPayloadTransfer(PayloadTransferFrame.newBuilder()
                            .setPacketType(PayloadTransferFrame.PacketType.DATA)
                            .setPayloadHeader(PayloadTransferFrame.PayloadHeader.newBuilder()
                                    .setId(id)
                                    .setType(type)
                                    .setTotalSize(total))
                            .setPayloadChunk(PayloadTransferFrame.PayloadChunk.newBuilder()
                                    .setOffset(offset)
                                    .setFlags(last ? 1 : 0)
                                    .setBody(ByteString.copyFrom(body)))));
        }

        private void sendOfflineFrame(V1Frame.Builder v1) throws Exception {
            byte[] frame = OfflineFrame.newBuilder()
                    .setVersion(OfflineFrame.Version.V1)
                    .setV1(v1)
                    .build()
                    .toByteArray();
            Framing.writeFrame(out, context.encodeMessageToPeer(frame));
        }

        OfflineFrame readOfflineFrame() throws Exception {
            byte[] raw = Framing.readFrame(in);
            assertNotNull("Expected encrypted frame", raw);
            return OfflineFrame.parseFrom(context.decodeMessageFromPeer(raw));
        }

        /** Reads offline frames until a complete BYTES payload holding a sharing frame arrives. */
        Protocol.Frame readSharingFrame() throws Exception {
            Map<Long, ByteArrayOutputStream> buffers = new HashMap<>();
            while (true) {
                OfflineFrame frame = readOfflineFrame();
                assertEquals("Sharing frames must be wrapped in payload transfers",
                        V1Frame.FrameType.PAYLOAD_TRANSFER, frame.getV1().getType());
                PayloadTransferFrame transfer = frame.getV1().getPayloadTransfer();
                assertEquals(PayloadType.BYTES, transfer.getPayloadHeader().getType());
                long id = transfer.getPayloadHeader().getId();
                ByteArrayOutputStream buffer = buffers.get(id);
                if (buffer == null) {
                    buffer = new ByteArrayOutputStream();
                    buffers.put(id, buffer);
                }
                transfer.getPayloadChunk().getBody().writeTo(buffer);
                if ((transfer.getPayloadChunk().getFlags() & 1) != 0) {
                    return Protocol.Frame.parseFrom(buffer.toByteArray());
                }
            }
        }

        private static OfflineFrame connectionResponse() {
            return OfflineFrame.newBuilder()
                    .setVersion(OfflineFrame.Version.V1)
                    .setV1(V1Frame.newBuilder()
                            .setType(V1Frame.FrameType.CONNECTION_RESPONSE)
                            .setConnectionResponse(
                                    OfflineWireFormatsProto.ConnectionResponseFrame.newBuilder()
                                            .setStatus(0)
                                            .setResponse(OfflineWireFormatsProto.ConnectionResponseFrame
                                                    .ResponseStatus.ACCEPT)))
                    .build();
        }
    }

    /** Records listener callbacks for assertions. */
    private static final class RecordingListener implements InboundConnection.Listener {
        final AtomicReference<String> deviceName = new AtomicReference<>();
        final AtomicReference<Protocol.IntroductionFrame> introduction = new AtomicReference<>();
        final ByteArrayOutputStream fileContent = new ByteArrayOutputStream();
        final AtomicReference<String> text = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final CountDownLatch introduced = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void onConnectionRequested(String name, EndpointInfo.DeviceType deviceType) {
            deviceName.set(name);
        }

        @Override
        public void onTransferIntroduced(Protocol.IntroductionFrame intro) {
            introduction.set(intro);
            introduced.countDown();
        }

        @Override
        public synchronized void onPayloadChunk(long payloadId, byte[] body, int flags, long offset) {
            assertEquals(FILE_PAYLOAD_ID, payloadId);
            assertEquals("chunks arrive in order", fileContent.size(), offset);
            fileContent.write(body, 0, body.length);
        }

        @Override
        public void onTextReceived(Protocol.TextMetadata metadata, String value) {
            assertEquals(TEXT_PAYLOAD_ID, metadata.getPayloadId());
            text.set(value);
        }

        @Override
        public void onTransferComplete() {
            completed.countDown();
        }

        @Override
        public void onConnectionClosed(Throwable e) {
            error.set(e);
            closed.countDown();
        }
    }

    private static final class Pipes {
        final PipedOutputStream senderToReceiver = new PipedOutputStream();
        final PipedInputStream receiverInput;
        final PipedOutputStream receiverToSender = new PipedOutputStream();
        final PipedInputStream senderInput;

        Pipes() throws Exception {
            receiverInput = new PipedInputStream(senderToReceiver, 1 << 20);
            senderInput = new PipedInputStream(receiverToSender, 1 << 20);
        }
    }

    private static Thread startReceiver(InboundConnection connection) {
        Thread thread = new Thread(connection::run, "receiver");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Test
    public void receivesFileAndTextAfterAccept() throws Exception {
        Pipes pipes = new Pipes();
        RecordingListener listener = new RecordingListener();
        InboundConnection connection = new InboundConnection(
                pipes.receiverInput, pipes.receiverToSender, listener);
        Thread receiver = startReceiver(connection);

        byte[] file = new byte[10_000];
        new java.util.Random(7).nextBytes(file);
        String text = "https://example.com/shared";

        SimulatedSender sender = new SimulatedSender(pipes.senderInput, pipes.senderToReceiver);
        sender.connect();
        assertEquals("Pixel 9", listener.deviceName.get());

        sender.pairAndIntroduce(file, text);
        assertTrue("introduced", listener.introduced.await(5, TimeUnit.SECONDS));
        assertEquals("photo.jpg", listener.introduction.get().getFileMetadata(0).getName());
        assertEquals(1, listener.introduction.get().getTextMetadataCount());

        connection.accept();
        Protocol.Frame response = sender.readSharingFrame();
        assertEquals(Protocol.V1Frame.FrameType.RESPONSE, response.getV1().getType());
        assertEquals(Protocol.ConnectionResponseFrame.Status.ACCEPT,
                response.getV1().getConnectionResponse().getStatus());

        sender.sendPayloads(file, text);

        OfflineFrame keepAliveAck = sender.readOfflineFrame();
        assertEquals(V1Frame.FrameType.KEEP_ALIVE, keepAliveAck.getV1().getType());
        assertTrue("keep-alive is acknowledged", keepAliveAck.getV1().getKeepAlive().getAck());

        assertTrue("completed", listener.completed.await(5, TimeUnit.SECONDS));
        OfflineFrame disconnection = sender.readOfflineFrame();
        assertEquals(V1Frame.FrameType.DISCONNECTION, disconnection.getV1().getType());

        assertTrue("closed", listener.closed.await(5, TimeUnit.SECONDS));
        assertNull("clean close after a completed transfer", listener.error.get());
        assertArrayEquals(file, listener.fileContent.toByteArray());
        assertEquals(text, listener.text.get());

        receiver.join(5000);
        assertFalse("receiver exits after completion", receiver.isAlive());
    }

    @Test
    public void rejectSendsRejectAndDisconnects() throws Exception {
        Pipes pipes = new Pipes();
        RecordingListener listener = new RecordingListener();
        InboundConnection connection = new InboundConnection(
                pipes.receiverInput, pipes.receiverToSender, listener);
        startReceiver(connection);

        SimulatedSender sender = new SimulatedSender(pipes.senderInput, pipes.senderToReceiver);
        sender.connect();
        sender.pairAndIntroduce(new byte[16], "hi");
        assertTrue("introduced", listener.introduced.await(5, TimeUnit.SECONDS));

        connection.reject();
        Protocol.Frame response = sender.readSharingFrame();
        assertEquals(Protocol.ConnectionResponseFrame.Status.REJECT,
                response.getV1().getConnectionResponse().getStatus());
        assertEquals(V1Frame.FrameType.DISCONNECTION, sender.readOfflineFrame().getV1().getType());

        // The host closes the socket after rejecting; that is not reported as an error.
        pipes.senderToReceiver.close();
        assertTrue("closed", listener.closed.await(5, TimeUnit.SECONDS));
        assertNull(listener.error.get());
    }

    @Test
    public void rejectsWhenHandshakeFails() throws Exception {
        Pipes pipes = new Pipes();
        RecordingListener listener = new RecordingListener();
        InboundConnection connection = new InboundConnection(
                pipes.receiverInput, pipes.receiverToSender, listener);
        Thread receiver = startReceiver(connection);

        // Garbage frame: not a valid connection request.
        Framing.writeFrame(pipes.senderToReceiver, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        assertTrue("receiver should close on garbage", listener.closed.await(5, TimeUnit.SECONDS));
        assertNotNull("error should be reported", listener.error.get());
        receiver.join(5000);
    }

    @Test
    public void endpointInfoSerializationRoundTrips() {
        byte[] blob = EndpointInfo.serialize("My TV", EndpointInfo.DeviceType.UNKNOWN);
        assertEquals("device type bits", 0, (blob[0] & 0x07) >> 1);
        assertEquals("name length byte", 5, blob[17] & 0xff);
        assertEquals("name", "My TV", new String(blob, 18, 5, StandardCharsets.UTF_8));

        EndpointInfo parsed = EndpointInfo.parse(blob);
        assertEquals("My TV", parsed.getDeviceName());
        assertEquals(EndpointInfo.DeviceType.UNKNOWN, parsed.getDeviceType());

        byte[] phoneBlob = EndpointInfo.serialize("Pixel 9", EndpointInfo.DeviceType.PHONE);
        assertEquals("phone device type bits", 1, (phoneBlob[0] & 0x07) >> 1);
        assertEquals("phone", EndpointInfo.DeviceType.PHONE, EndpointInfo.parse(phoneBlob).getDeviceType());
    }
}
