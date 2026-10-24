package dev.dworks.apps.anexplorer.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Length-prefixed frame I/O used by the Quick Share / Nearby Connections socket
 * protocol. Every message on the wire is prefixed with a 4-byte big-endian
 * length (in bytes) followed by the raw serialized payload.
 *
 * <p>This is a clean-room implementation derived from the public wire format
 * (Apache-2.0 Google protos). No third-party code is copied here.
 */
public final class Framing {

    private Framing() {
    }

    /**
     * Reads a single length-prefixed frame from the stream.
     *
     * @return the raw frame bytes, or {@code null} when the stream ends cleanly
     *     between frames (peer disconnected).
     * @throws IOException if the stream fails or a frame is malformed.
     */
    public static byte[] readFrame(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int length;
        try {
            length = dis.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (length < 0 || length > 64 * 1024 * 1024) {
            throw new IOException("Invalid frame length: " + length);
        }
        byte[] data = new byte[length];
        dis.readFully(data);
        return data;
    }

    /**
     * Writes a single length-prefixed frame to the stream.
     */
    public static void writeFrame(OutputStream out, byte[] frame) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(frame.length);
        dos.write(frame);
        dos.flush();
    }
}
