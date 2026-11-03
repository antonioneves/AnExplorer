package dev.dworks.apps.anexplorer.cursor;

import android.database.MatrixCursor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.dworks.apps.anexplorer.BaseActivity.State;
import dev.dworks.apps.anexplorer.model.DocumentsContract.Document;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SortingCursorWrapperTest {

    private static final String[] COLUMNS = {
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};

    private static MatrixCursor cursor() {
        final MatrixCursor cursor = new MatrixCursor(COLUMNS);
        cursor.addRow(new Object[]{"b.txt", "text/plain", 10L, 100L});
        cursor.addRow(new Object[]{"Docs", Document.MIME_TYPE_DIR, 0L, 300L});
        cursor.addRow(new Object[]{"a.txt", "text/plain", 30L, 200L});
        return cursor;
    }

    private static List<String> names(SortingCursorWrapper wrapper) {
        final List<String> names = new ArrayList<>();
        while (wrapper.moveToNext()) {
            names.add(wrapper.getString(0));
        }
        return names;
    }

    @Test
    public void nameSortPutsFoldersFirst() {
        final SortingCursorWrapper sorted =
                new SortingCursorWrapper(cursor(), State.SORT_ORDER_DISPLAY_NAME);
        assertEquals(Arrays.asList("Docs", "a.txt", "b.txt"), names(sorted));
    }

    @Test
    public void lastModifiedSortPutsNewestFirst() {
        final SortingCursorWrapper sorted =
                new SortingCursorWrapper(cursor(), State.SORT_ORDER_LAST_MODIFIED);
        assertEquals(Arrays.asList("Docs", "a.txt", "b.txt"), names(sorted));
    }

    @Test
    public void sizeSortPutsLargestFirst() {
        final SortingCursorWrapper sorted =
                new SortingCursorWrapper(cursor(), State.SORT_ORDER_SIZE);
        assertEquals(Arrays.asList("a.txt", "b.txt", "Docs"), names(sorted));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownSortOrderIsRejected() {
        new SortingCursorWrapper(cursor(), -1);
    }
}
