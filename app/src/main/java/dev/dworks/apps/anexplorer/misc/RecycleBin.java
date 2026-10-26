package dev.dworks.apps.anexplorer.misc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import dev.dworks.apps.anexplorer.libcore.io.IoUtils;

/**
 * The recycle bin: a deleted file moves into a hidden folder at the top of its own drive, so
 * deleting stays a rename rather than a copy, and a small info file beside it records where it
 * came from and when. Items are deleted for good after {@link #KEEP_MILLIS}.
 */
public class RecycleBin {

    public static final String DIR_NAME = ".AnExplorerTrash";
    private static final String DIR_FILES = "files";
    private static final String DIR_INFO = "info";
    private static final String INFO_SUFFIX = ".trashinfo";
    private static final String KEY_PATH = "Path";
    private static final String KEY_DATE = "DeletionDate";

    public static final long KEEP_MILLIS = 30L * 24 * 60 * 60 * 1000;

    private RecycleBin() {
    }

    /** The folder holding the deleted items of the drive mounted at volume */
    public static File getFilesDir(File volume) {
        return new File(new File(volume, DIR_NAME), DIR_FILES);
    }

    private static File getInfoDir(File volume) {
        return new File(new File(volume, DIR_NAME), DIR_INFO);
    }

    private static File getInfoFile(File item) {
        return new File(new File(item.getParentFile().getParentFile(), DIR_INFO), item.getName() + INFO_SUFFIX);
    }

    /** Whether the file is in a recycle bin, directly or inside a deleted folder */
    public static boolean isInBin(File file) {
        return file.getAbsolutePath().contains("/" + DIR_NAME + "/");
    }

    /** Whether the file is a deleted item itself, rather than something inside a deleted folder */
    public static boolean isBinItem(File file) {
        final File parent = file.getParentFile();
        return null != parent && DIR_FILES.equals(parent.getName())
                && null != parent.getParentFile() && DIR_NAME.equals(parent.getParentFile().getName());
    }

    public static boolean isEmpty(File volume) {
        final String[] names = getFilesDir(volume).list();
        return null == names || names.length == 0;
    }

    public static boolean moveToBin(File volume, File file) {
        final File files = getFilesDir(volume);
        final File info = getInfoDir(volume);
        if (!files.isDirectory() && !files.mkdirs()) {
            return false;
        }
        if (!info.isDirectory() && !info.mkdirs()) {
            return false;
        }
        // Keeps the media scanner out of the bin on older Android versions
        final File noMedia = new File(files.getParentFile(), ".nomedia");
        try {
            noMedia.createNewFile();
        } catch (IOException ignored) {
        }

        final File target = uniqueFile(files, file.getName());
        final Properties props = new Properties();
        props.setProperty(KEY_PATH, file.getAbsolutePath());
        props.setProperty(KEY_DATE, Long.toString(System.currentTimeMillis()));
        final File infoFile = getInfoFile(target);
        if (!writeInfo(infoFile, props)) {
            return false;
        }
        if (!file.renameTo(target)) {
            infoFile.delete();
            return false;
        }
        return true;
    }

    /** Where the item was before it was deleted, or null when that's unknown */
    public static String getOriginalPath(File item) {
        final Properties props = readInfo(getInfoFile(item));
        return null != props ? props.getProperty(KEY_PATH) : null;
    }

    public static long getDeletionDate(File item) {
        final Properties props = readInfo(getInfoFile(item));
        try {
            return null != props ? Long.parseLong(props.getProperty(KEY_DATE)) : item.lastModified();
        } catch (NumberFormatException e) {
            return item.lastModified();
        }
    }

    /**
     * Puts a deleted item back where it was, renamed if something has since taken its name.
     * Returns the restored file, or null when that failed.
     */
    public static File restore(File item) {
        final String path = getOriginalPath(item);
        if (null == path) {
            return null;
        }
        final File original = new File(path);
        final File parent = original.getParentFile();
        if (null == parent || (!parent.isDirectory() && !parent.mkdirs())) {
            return null;
        }
        final File target = uniqueFile(parent, original.getName());
        if (!item.renameTo(target)) {
            return null;
        }
        getInfoFile(item).delete();
        return target;
    }

    /** Deletes an item in the bin for good */
    public static boolean delete(File file) {
        if (file.isDirectory()) {
            FileUtils.deleteContents(file);
        }
        final boolean deleted = file.delete();
        if (deleted && isBinItem(file)) {
            getInfoFile(file).delete();
        }
        return deleted;
    }

    /** Deletes for good the items that were deleted before maxAge ago; 0 empties the bin */
    public static void purge(File volume, long maxAge) {
        final File[] items = getFilesDir(volume).listFiles();
        if (null == items) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (File item : items) {
            if (maxAge == 0 || now - getDeletionDate(item) > maxAge) {
                delete(item);
            }
        }
        // Info files whose item is gone, e.g. deleted from outside the app
        final File[] infos = getInfoDir(volume).listFiles();
        if (null != infos) {
            final File files = getFilesDir(volume);
            for (File info : infos) {
                final String name = info.getName();
                if (name.endsWith(INFO_SUFFIX)
                        && !new File(files, name.substring(0, name.length() - INFO_SUFFIX.length())).exists()) {
                    info.delete();
                }
            }
        }
    }

    /** name, or "name (2).ext", "name (3).ext"... when that's taken in parent */
    private static File uniqueFile(File parent, String name) {
        File file = new File(parent, name);
        if (!file.exists()) {
            return file;
        }
        final int dot = name.lastIndexOf('.');
        final String base = dot > 0 ? name.substring(0, dot) : name;
        final String ext = dot > 0 ? name.substring(dot) : "";
        int n = 2;
        do {
            file = new File(parent, base + " (" + n++ + ")" + ext);
        } while (file.exists());
        return file;
    }

    private static boolean writeInfo(File file, Properties props) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            props.store(out, null);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            IoUtils.closeQuietly(out);
        }
    }

    private static Properties readInfo(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            final Properties props = new Properties();
            props.load(in);
            return props;
        } catch (IOException e) {
            return null;
        } finally {
            IoUtils.closeQuietly(in);
        }
    }
}
