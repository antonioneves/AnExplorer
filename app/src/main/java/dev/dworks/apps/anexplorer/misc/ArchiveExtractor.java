package dev.dworks.apps.anexplorer.misc;

import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;

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
     * The files and folders of the archive, as zip entries (folders end in a slash) so that
     * {@link dev.dworks.apps.anexplorer.archive.DocumentArchive} can browse them like a zip.
     */
    public static List<ZipEntry> listEntries(File archiveFile) throws IOException {
        final List<ZipEntry> entries = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        long archive = 0;
        try {
            archive = openReader(archiveFile);
            long entry;
            while ((entry = Archive.readNextHeader(archive)) != 0) {
                final String name = browseName(entry);
                if (name == null || !seen.add(name)) {
                    continue;
                }
                final ZipEntry zipEntry = new ZipEntry(name);
                if (ArchiveEntry.sizeIsSet(entry)) {
                    zipEntry.setSize(ArchiveEntry.size(entry));
                }
                if (ArchiveEntry.mtimeIsSet(entry)) {
                    zipEntry.setTime(ArchiveEntry.mtime(entry) * 1000);
                }
                entries.add(zipEntry);
            }
            return entries;
        } catch (ArchiveException e) {
            throw new IOException(e);
        } finally {
            closeReader(archive);
        }
    }

    /**
     * Unpacks one file of the archive into the cache and opens it. The archive can only be read
     * from the start, so this scans up to the entry; the copy is deleted as soon as it's open.
     */
    public static InputStream openEntry(File archiveFile, String entryName, File cacheDir) throws IOException {
        long archive = 0;
        File temp = null;
        try {
            archive = openReader(archiveFile);
            long entry;
            while ((entry = Archive.readNextHeader(archive)) != 0) {
                if (!entryName.equals(browseName(entry))) {
                    continue;
                }
                if (ArchiveEntry.isEncrypted(entry)) {
                    throw new IOException("Encrypted archives aren't supported");
                }
                temp = File.createTempFile("archive-entry", null, cacheDir);
                try (ParcelFileDescriptor out = ParcelFileDescriptor.open(temp,
                        ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE)) {
                    Archive.readDataIntoFd(archive, out.getFd());
                }
                return new FileInputStream(temp);
            }
            throw new IOException("No such entry: " + entryName);
        } catch (ArchiveException e) {
            throw new IOException(e);
        } finally {
            closeReader(archive);
            if (temp != null) {
                // Unlinked, the open stream still reads it and the space comes back when it closes
                temp.delete();
            }
        }
    }

    /** The entry's path without a leading "./" or "/", with a trailing slash for a folder; null to skip it */
    private static String browseName(long entry) {
        String name = ArchiveEntry.pathnameUtf8(entry);
        if (name == null) {
            return null;
        }
        while (name.startsWith("./") || name.startsWith("/")) {
            name = name.substring(name.startsWith("./") ? 2 : 1);
        }
        if (name.isEmpty() || name.equals(".") || ("/" + name + "/").contains("/../")) {
            return null;
        }
        final int type = ArchiveEntry.filetype(entry) & ArchiveEntry.AE_IFMT;
        if (type == ArchiveEntry.AE_IFDIR) {
            return name.endsWith("/") ? name : name + "/";
        }
        // 7z entries without Unix attributes carry no type at all, and are plain files
        return type == ArchiveEntry.AE_IFREG || type == 0 ? name : null;
    }

    private static long openReader(File archiveFile) throws ArchiveException {
        final long archive = Archive.readNew();
        try {
            Archive.readSupportFilterAll(archive);
            Archive.readSupportFormatAll(archive);
            Archive.readOpenFileName(archive,
                    archiveFile.getPath().getBytes(StandardCharsets.UTF_8), BLOCK_SIZE);
            return archive;
        } catch (ArchiveException e) {
            closeReader(archive);
            throw e;
        }
    }

    private static void closeReader(long archive) {
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
