package dev.dworks.apps.anexplorer.fragment;

import java.util.ArrayList;

import dev.dworks.apps.anexplorer.model.DocumentInfo;

/**
 * Episodes checked in a show folder's carousels. The file list keeps its own selection, so this
 * only says which of the two is in play ({@link #isActive()}).
 */
class EpisodeSelection {
    private final ArrayList<DocumentInfo> mSelected = new ArrayList<>();
    private boolean mActive;

    boolean isActive() {
        return mActive;
    }

    /** The live list, which the carousels read to draw their checkmarks */
    ArrayList<DocumentInfo> list() {
        return mSelected;
    }

    ArrayList<DocumentInfo> copy() {
        return new ArrayList<>(mSelected);
    }

    int size() {
        return mSelected.size();
    }

    boolean isEmpty() {
        return mSelected.isEmpty();
    }

    /** Starts over with just this episode */
    void start(DocumentInfo episode) {
        mActive = true;
        mSelected.clear();
        mSelected.add(episode);
    }

    void end() {
        mActive = false;
        mSelected.clear();
    }

    void add(DocumentInfo episode) {
        if (!contains(episode)) {
            mSelected.add(episode);
        }
    }

    boolean contains(DocumentInfo episode) {
        for (DocumentInfo selected : mSelected) {
            if (selected.documentId.equals(episode.documentId)) {
                return true;
            }
        }
        return false;
    }

    void remove(DocumentInfo episode) {
        for (int i = mSelected.size() - 1; i >= 0; i--) {
            if (mSelected.get(i).documentId.equals(episode.documentId)) {
                mSelected.remove(i);
            }
        }
    }
}
