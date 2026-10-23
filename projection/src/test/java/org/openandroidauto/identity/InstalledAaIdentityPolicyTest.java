package org.openandroidauto.identity;

import org.junit.Test;
import static org.junit.Assert.*;

public class InstalledAaIdentityPolicyTest {
    @Test public void onlyExactReviewedVersionAndSignerAreAccepted() {
        assertTrue(InstalledAaIdentityPolicy.versionMatches(178663874L,"17.8.663874-release"));
        assertFalse(InstalledAaIdentityPolicy.versionMatches(178663875L,"17.8.663874-release"));
        assertFalse(InstalledAaIdentityPolicy.versionMatches(178663874L,"17.8.663874-stub"));
        assertFalse(InstalledAaIdentityPolicy.versionMatches(12562600L,"1.2.562600-stub"));
        assertTrue(InstalledAaIdentityPolicy.signerMatches(new String[]{InstalledAaIdentityPolicy.SIGNER_SHA256}));
        assertFalse(InstalledAaIdentityPolicy.signerMatches(new String[]{"unreviewed"}));
        assertFalse(InstalledAaIdentityPolicy.signerMatches(new String[]{InstalledAaIdentityPolicy.SIGNER_SHA256,"extra"}));
        assertFalse(InstalledAaIdentityPolicy.signerMatches(null));
    }
    @Test public void fullApkPinRejectsOneByteOrEncodingChanges() {
        assertTrue(InstalledAaIdentityPolicy.hashMatches(InstalledAaIdentityPolicy.APK_SHA256));
        assertFalse(InstalledAaIdentityPolicy.hashMatches("0"+InstalledAaIdentityPolicy.APK_SHA256.substring(1)));
        assertFalse(InstalledAaIdentityPolicy.hashMatches(InstalledAaIdentityPolicy.APK_SHA256.toUpperCase(java.util.Locale.ROOT)));
        assertFalse(InstalledAaIdentityPolicy.hashMatches(null));
    }
    @Test public void apkAndZipBoundsRejectOverflowUnknownSizesAndTooManyEntries() {
        assertTrue(InstalledAaIdentityPolicy.apkSizeAllowed(34941559));
        assertFalse(InstalledAaIdentityPolicy.apkSizeAllowed(0));
        assertFalse(InstalledAaIdentityPolicy.apkSizeAllowed(InstalledAaIdentityPolicy.MAX_APK_BYTES+1));
        assertTrue(InstalledAaIdentityPolicy.entryAllowed(5213,9435612,2000000,25700776));
        assertFalse(InstalledAaIdentityPolicy.entryAllowed(8193,0,0,0));
        assertFalse(InstalledAaIdentityPolicy.entryAllowed(1,-1,1,0));
        assertFalse(InstalledAaIdentityPolicy.entryAllowed(1,1,-1,0));
        assertFalse(InstalledAaIdentityPolicy.entryAllowed(1,1,1,Long.MAX_VALUE));
        assertFalse(InstalledAaIdentityPolicy.entryAllowed(1,1,1,InstalledAaIdentityPolicy.MAX_UNCOMPRESSED_BYTES));
    }
    @Test public void archiveNamesCannotEscapeOrHideAmbiguousPaths() {
        assertTrue(InstalledAaIdentityPolicy.entryNameAllowed("res/drawable/a.xml"));
        assertTrue(InstalledAaIdentityPolicy.entryNameAllowed("META-INF/"));
        for(String name:new String[]{"../a","a/../b","a/./b","/absolute","a\\b","a\0b",""})
            assertFalse(InstalledAaIdentityPolicy.entryNameAllowed(name));
    }
    @Test public void onlyRootStandardDexNamesAreReadAsDex() {
        for(String name:new String[]{"classes.dex","classes2.dex","classes10.dex"}) assertTrue(InstalledAaIdentityPolicy.dexName(name));
        for(String name:new String[]{"classes1.dex","classes02.dex","assets/classes.dex","classes.dex/"}) assertFalse(InstalledAaIdentityPolicy.dexName(name));
    }
    @Test public void dexHeaderIsBoundedAndVersionChecked() {
        assertTrue(InstalledAaIdentityPolicy.dexMagicAllowed(new byte[]{'d','e','x','\n','0','3','9',0}));
        assertFalse(InstalledAaIdentityPolicy.dexMagicAllowed(new byte[]{'d','e','x','\n','0','9','9',0}));
        assertFalse(InstalledAaIdentityPolicy.dexMagicAllowed(new byte[]{'d','e','x','\n','0','3','9',1}));
        assertFalse(InstalledAaIdentityPolicy.dexMagicAllowed(new byte[]{'d','e','x'}));
    }
}
