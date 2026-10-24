package dev.dworks.apps.anexplorer.cloud;

/**
 * A file or folder in a cloud drive. {@link #id} is what the service addresses it by: an item id,
 * or for Dropbox its lower-cased path.
 */
public class CloudFile {

    public final String id;
    public final String name;
    public final boolean folder;
    public long size = -1;
    public long lastModified;
    /** The service's own type, when it has one (Google Drive) */
    public String mimeType;
    /** A link to a ready-made thumbnail, when the listing gives one */
    public String thumbnailUrl;
    /** Where the file shows in the drive, when the service names files by path (Dropbox) */
    public String path;
    /** The document id of the folder it was listed in */
    public String parentDocumentId;

    public CloudFile(String id, String name, boolean folder) {
        this.id = id;
        this.name = name;
        this.folder = folder;
    }

    public boolean isDirectory() {
        return folder;
    }
}
