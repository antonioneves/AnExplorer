package dev.dworks.apps.anexplorer.viewer;

/** A subtitle the host found online, as the player lists it to choose from */
public class Subtitle {
    public final String release;
    public final String language;
    public final boolean hearingImpaired;

    public Subtitle(String release, String language, boolean hearingImpaired) {
        this.release = release;
        this.language = language;
        this.hearingImpaired = hearingImpaired;
    }
}
