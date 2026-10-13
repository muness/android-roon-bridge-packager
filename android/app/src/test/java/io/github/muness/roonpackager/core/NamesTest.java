package io.github.muness.roonpackager.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The single-dot rule, asserted against the names the desktop packer's own
 * self-test asserts. These are not made-up cases: every one of them is a real
 * file in Roon Bridge's tree, and the kept-name cases are the ones whose rename
 * broke the first device run.
 */
public final class NamesTest {

    private static final String SUFFIX = "_roon.so";

    @Test
    public void aNameAndroidExtractsIsKeptExactly() {
        // The .NET host realpath()s the libcoreclr.so it found and then appends
        // that literal name to the directory it landed in, so renaming these
        // produces HRESULT 0x80008088 and nothing else.
        for (String kept : new String[]{"libcoreclr.so", "libclrjit.so", "libclrgc.so",
                "libhostpolicy.so", "libhostfxr.so", "libraatmanager.so"}) {
            assertTrue(kept + " should be extractable as it is", Names.extractable(kept));
            assertEquals(kept, Names.packaged(kept, SUFFIX));
        }
    }

    @Test
    public void aVersionedSonameFoldsToOneDot() {
        assertEquals("libssl_3_roon.so", Names.packaged("libssl.so.3", SUFFIX));
        assertEquals("libcrypto_3_roon.so", Names.packaged("libcrypto.so.3", SUFFIX));
        assertEquals("libz_1_roon.so", Names.packaged("libz.so.1", SUFFIX));
        assertEquals("libzstd_1_roon.so", Names.packaged("libzstd.so.1", SUFFIX));
        assertEquals("libdl_2_roon.so", Names.packaged("libdl.so.2", SUFFIX));
        assertEquals("libpthread_0_roon.so", Names.packaged("libpthread.so.0", SUFFIX));
        assertEquals("librt_1_roon.so", Names.packaged("librt.so.1", SUFFIX));
    }

    @Test
    public void aDottedLibraryNameFoldsToOneDot() {
        assertEquals("libSystem_Native_roon.so",
                Names.packaged("libSystem.Native.so", SUFFIX));
    }

    @Test
    public void aPlainExecutableGetsTheLibPrefix() {
        assertEquals("libcheck_alsa_roon.so", Names.packaged("check_alsa", SUFFIX));
        assertEquals("libRAATServer_roon.so", Names.packaged("RAATServer", SUFFIX));
    }

    @Test
    public void everyPackagedNameHasExactlyOneDot() {
        for (String base : new String[]{"libssl.so.3", "libSystem.Native.so", "check_alsa",
                "libcoreclr.so", "RoonBridge.exe", "libz.so.1.3.1"}) {
            String packaged = Names.packaged(base, SUFFIX);
            assertEquals(packaged + " must have exactly one dot", 1, Names.dotCount(packaged));
            assertTrue(packaged + " must be a name Android extracts",
                    Names.extractable(packaged));
        }
    }

    @Test
    public void twoDotsIsNotExtractable() {
        assertFalse(Names.extractable("libssl.so.3"));
        assertFalse(Names.extractable("libSystem.Native.so"));
        assertFalse(Names.extractable("networkaudiod"));
    }

    @Test
    public void theNaaEntryNameIsWhatTheDesktopPackerUses() {
        assertTrue(Names.extractable("libnetworkaudiod_exec.so"));
    }
}
