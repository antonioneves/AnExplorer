package dev.dworks.apps.anexplorer.misc;

import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import me.zhanghai.android.libarchive.Archive;
import me.zhanghai.android.libarchive.ArchiveEntry;
import me.zhanghai.android.libarchive.ArchiveException;

/**
 * Extracts the archives java.util.zip can't read (7z, RAR, tar and its compressed forms) with
 * libarchive, and zips that are encrypted. Other zips stay on {@link FileUtils#uncompress}.
 */
public final class ArchiveExtractor {

    private static final int BLOCK_SIZE = 64 * 1024;

    /** The archive is encrypted and either no passphrase was given or it was wrong */
    public static class PasswordRequiredException extends IOException {
        public PasswordRequiredException(String message) {
            super(message);
        }
    }

    private ArchiveExtractor() {
    }

    /** Extensions handled here, lower case; a trailing ".tar.gz" style name is matched by its last part */
    public static boolean isSupported(String fileName) {
        switch (FileUtils.getExtFromFilename(fileName).toLowerCase()) {
            case "7z":
            case "rar":
            case "tar":
            case "tgz":
            case "tbz2":
            case "txz":
            case "gz":
            case "bz2":
            case "xz":
                return true;
            default:
                return false;
        }
    }

    /** "a.tar.gz" extracts to a folder called "a", not "a.tar" */
    public static String folderName(String fileName) {
        String name = FileUtils.getNameFromFilename(fileName);
        if (name.toLowerCase().endsWith(".tar")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    /**
     * @param passphrase for an encrypted archive, or null
     * A folder this call made is removed again if it fails, so no half-extracted copy is left.
     */
    public static void extract(File archiveFile, File destFolder, String passphrase) throws IOException {
        final boolean created = !destFolder.exists();
        try {
            extractInto(archiveFile, destFolder, passphrase);
        } catch (IOException | RuntimeException e) {
            if (created) {
                FileUtils.deleteFile(destFolder);
            }
            throw e;
        }
    }

    private static void extractInto(File archiveFile, File destFolder, String passphrase) throws IOException {
        destFolder.mkdirs();
        final String root = destFolder.getCanonicalPath() + File.separator;
        long archive = 0;
        try {
            archive = Archive.readNew();
            Archive.readSupportFilterAll(archive);
            Archive.readSupportFormatAll(archive);
            if (passphrase != null) {
                Archive.readAddPassphrase(archive, passphrase.getBytes(StandardCharsets.UTF_8));
            }
            Archive.readOpenFileName(archive,
                    archiveFile.getPath().getBytes(StandardCharsets.UTF_8), BLOCK_SIZE);
            long entry;
            while ((entry = Archive.readNextHeader(archive)) != 0) {
                final String name = ArchiveEntry.pathnameUtf8(entry);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                final File dest = new File(destFolder, name);
                // An entry named like "../x" must not write outside the folder being extracted to
                final String path = dest.getCanonicalPath();
                if (!path.startsWith(root) && !(path + File.separator).equals(root)) {
                    throw new IOException("Archive entry outside the destination: " + name);
                }
                if (ArchiveEntry.isEncrypted(entry) && passphrase == null) {
                    if (isSupported(archiveFile.getName())) {
                        throw new IOException("Encrypted archives of this type aren't supported");
                    }
                    throw new PasswordRequiredException(name);
                }
                final int type = ArchiveEntry.filetype(entry) & ArchiveEntry.AE_IFMT;
                if (type == ArchiveEntry.AE_IFDIR) {
                    dest.mkdirs();
                } else if (type == ArchiveEntry.AE_IFREG || type == 0) {
                    // 7z entries without Unix attributes carry no type at all, and are plain files
                    dest.getParentFile().mkdirs();
                    try (ParcelFileDescriptor out = ParcelFileDescriptor.open(dest,
                            ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                                    | ParcelFileDescriptor.MODE_TRUNCATE)) {
                        Archive.readDataIntoFd(archive, out.getFd());
                    }
                }
                // Links, devices and the like aren't extracted
            }
        } catch (ArchiveException e) {
            // libarchive reports a missing or wrong passphrase only through its message
            final String message = String.valueOf(e.getMessage()).toLowerCase();
            // 7z and RAR encryption can't be read at all, so a passphrase wouldn't help there
            if (!message.contains("not supported") && (message.contains("passphrase")
                    || message.contains("password") || message.contains("encrypt"))) {
                throw new PasswordRequiredException(e.getMessage());
            }
            throw new IOException(e);
        } finally {
            if (archive != 0) {
                try {
                    Archive.readClose(archive);
                } catch (ArchiveException ignored) {
                }
                try {
                    Archive.readFree(archive);
                } catch (ArchiveException ignored) {
                }
            }
        }
    }
}
