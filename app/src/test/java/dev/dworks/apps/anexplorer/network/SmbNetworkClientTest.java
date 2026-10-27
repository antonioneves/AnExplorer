package dev.dworks.apps.anexplorer.network;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SmbNetworkClientTest {

    @Test
    public void shareIsTheFirstFolder() {
        assertNull(SmbNetworkClient.shareOf(""));
        assertEquals("Media", SmbNetworkClient.shareOf("Media"));
        assertEquals("Media", SmbNetworkClient.shareOf("Media/Movies/a.mkv"));
    }

    @Test
    public void pathInShareUsesBackslashes() {
        assertEquals("", SmbNetworkClient.pathInShare("Media"));
        assertEquals("Movies\\a.mkv", SmbNetworkClient.pathInShare("Media/Movies/a.mkv"));
    }

    @Test
    public void trimSlashesKeepsInnerOnes() {
        assertEquals("", SmbNetworkClient.trimSlashes(null));
        assertEquals("", SmbNetworkClient.trimSlashes("///"));
        assertEquals("a/b", SmbNetworkClient.trimSlashes("/a/b/"));
    }

    @Test
    public void onlyVisibleFolderSharesAreListed() {
        assertTrue(SmbNetworkClient.isFolderShare(0, "Media"));
        assertFalse(SmbNetworkClient.isFolderShare(0, "C$"));
        assertFalse(SmbNetworkClient.isFolderShare(0x80000000, "ADMIN"));
        // Printer and IPC shares
        assertFalse(SmbNetworkClient.isFolderShare(1, "Printer"));
        assertFalse(SmbNetworkClient.isFolderShare(3, "IPC"));
    }
}
