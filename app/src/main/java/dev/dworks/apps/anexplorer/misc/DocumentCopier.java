package dev.dworks.apps.anexplorer.misc;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import dev.dworks.apps.anexplorer.libcore.io.IoUtils;
import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.model.DocumentsContract;

/**
 * Copies documents by reading and writing them, for a copy from one storage to another
 * (a server to the TV, say), which neither storage can do by itself.
 */
public class DocumentCopier {

    private static final String TAG = DocumentCopier.class.getSimpleName();

    private final ContentResolver resolver;

    public DocumentCopier(ContentResolver resolver) {
        this.resolver = resolver;
    }

    /** Whether a provider can copy the document itself, which is quicker */
    public static boolean copiesItself(DocumentInfo doc, DocumentInfo folder, boolean move) {
        return doc.authority.equals(folder.authority)
                && (move ? doc.isMoveSupported() : doc.isCopySupported());
    }

    /** @return whether everything was copied, and for a move, taken away from where it was */
    public boolean copy(DocumentInfo doc, Uri folder, boolean move) {
        if (!copy(doc, folder)) {
            return false;
        }
        // Only once the copy is known to be whole, so nothing is lost when a copy fails
        return !move || DocumentsContract.deleteDocument(resolver, doc.derivedUri);
    }

    private boolean copy(DocumentInfo doc, Uri folder) {
        final Uri made = DocumentsContract.createDocument(resolver, folder, doc.mimeType,
                doc.displayName);
        if (null == made) {
            Log.w(TAG, "Couldn't make " + doc.displayName);
            return false;
        }
        if (doc.isDirectory()) {
            boolean copied = true;
            for (DocumentInfo child : children(doc)) {
                copied &= copy(child, made);
            }
            return copied;
        }
        try (InputStream in = resolver.openInputStream(doc.derivedUri);
             ParcelFileDescriptor target = resolver.openFileDescriptor(made, "w")) {
            if (null == in || null == target) {
                throw new IOException("Can't open " + doc.displayName);
            }
            final FileOutputStream out = new FileOutputStream(target.getFileDescriptor());
            IoUtils.copy(in, out);
            out.flush();
            awaitWritten(target);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Failed to copy " + doc.displayName + ": " + e);
            // Not left half written
            DocumentsContract.deleteDocument(resolver, made);
            return false;
        }
    }

    /**
     * Waits until a server has all of the file. Network storage hands out one end of a socket,
     * closing its own end once the upload is done or failed; a local file is written already.
     */
    private static void awaitWritten(ParcelFileDescriptor target) throws IOException {
        try {
            Os.shutdown(target.getFileDescriptor(), OsConstants.SHUT_WR);
        } catch (ErrnoException e) {
            return;
        }
        final FileInputStream answer = new FileInputStream(target.getFileDescriptor());
        final byte[] buffer = new byte[64];
        while (answer.read(buffer) != -1) {
            // Nothing is sent back; this only waits for the other end to close
        }
        target.checkError();
    }

    private List<DocumentInfo> children(DocumentInfo folder) {
        final List<DocumentInfo> children = new ArrayList<>();
        final Uri uri = DocumentsContract.buildChildDocumentsUri(folder.authority, folder.documentId);
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            while (null != cursor && cursor.moveToNext()) {
                children.add(DocumentInfo.fromCursor(cursor, folder.authority));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't list " + folder.displayName + ": " + e);
        }
        return children;
    }
}
