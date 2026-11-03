package dev.dworks.apps.anexplorer.misc;

import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;
import android.system.OsConstants;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.InputStream;

import dev.dworks.apps.anexplorer.network.NetworkClient;

/**
 * Serves a file on a server as a seekable file descriptor, so a player can jump around in a video
 * without it being copied first. A jump forward a short way reads on; any other jump opens the file
 * again at the new position.
 */
@RequiresApi(26)
public class NetworkStreamCallback extends ProxyFileDescriptorCallback {

    /** Reading on is cheaper than a new connection up to about this far */
    private static final long READ_ON_MAX = 256 * 1024;

    private final NetworkClient client;
    private final String path;
    private final long size;
    private final Runnable onClose;
    private InputStream stream;
    private long position;

    public NetworkStreamCallback(NetworkClient client, String path, long size, Runnable onClose) {
        this.client = client;
        this.path = path;
        this.size = size;
        this.onClose = onClose;
    }

    @Override
    public long onGetSize() {
        return size;
    }

    @Override
    public int onRead(long offset, int length, byte[] data) throws ErrnoException {
        if (offset >= size) {
            return 0;
        }
        try {
            moveTo(offset);
            int total = 0;
            while (total < length) {
                final int count = stream.read(data, total, length - total);
                if (count < 0) {
                    break;
                }
                total += count;
            }
            position += total;
            return total;
        } catch (IOException e) {
            closeStream();
            throw new ErrnoException("read", OsConstants.EIO);
        }
    }

    private void moveTo(long offset) throws IOException {
        if (stream != null && offset != position) {
            if (offset > position && offset - position <= READ_ON_MAX) {
                NetworkClient.skipFully(stream, offset - position);
                position = offset;
            } else {
                closeStream();
            }
        }
        if (stream == null) {
            stream = client.openInputStream(path, offset);
            if (stream == null) {
                throw new IOException("Can't read " + path);
            }
            position = offset;
        }
    }

    private void closeStream() {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
            stream = null;
        }
    }

    @Override
    public void onRelease() {
        closeStream();
        onClose.run();
    }
}
