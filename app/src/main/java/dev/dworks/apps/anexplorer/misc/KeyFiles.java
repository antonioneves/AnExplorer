package dev.dworks.apps.anexplorer.misc;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.dworks.apps.anexplorer.setting.AppSettings;

/** Private key files for SFTP, in the places a user would put one on a TV */
public class KeyFiles {

    // Keys are a few kilobytes at most
    private static final long MAX_SIZE = 32 * 1024;
    private static final String[] HEADERS = {"-----BEGIN ", "PuTTY-User-Key-File-"};

    /** The key files in Download, Documents, internal storage itself and the Quick Share folder */
    public static List<File> find(Context context) {
        final Set<File> folders = new LinkedHashSet<>();
        folders.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        folders.add(AppSettings.getQuickShareFolder(context));
        folders.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
        folders.add(Environment.getExternalStorageDirectory());
        final List<File> keys = new ArrayList<>();
        for (File folder : folders) {
            final File[] files = folder.listFiles();
            if (null == files) {
                continue;
            }
            for (File file : files) {
                if (file.isFile() && file.length() <= MAX_SIZE && isKey(file)) {
                    keys.add(file);
                }
            }
        }
        return keys;
    }

    /** The file's text, or null if it can't be read */
    public static String read(File file) {
        if (file.length() > MAX_SIZE) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            final byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                final int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(bytes, 0, read, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Whether the file starts like a private key; a public key (.pub) doesn't */
    private static boolean isKey(File file) {
        final byte[] start = new byte[32];
        int read;
        try (InputStream in = new FileInputStream(file)) {
            read = in.read(start);
        } catch (IOException e) {
            return false;
        }
        if (read <= 0) {
            return false;
        }
        final String text = new String(start, 0, read, StandardCharsets.US_ASCII);
        for (String header : HEADERS) {
            if (text.startsWith(header)) {
                return !text.startsWith("-----BEGIN PUBLIC") && !text.startsWith("-----BEGIN CERTIFICATE");
            }
        }
        return false;
    }
}
