/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.updates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Spoof package version: a PackageInfo reporting the raised code answers the original one, which
 * the patch writes into originalVersionCode(), and anything else keeps what Android reported.
 * Unpatched, both stubs answer 0 and nothing is swapped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class OwnVersionTest {
    private static final int FACEBOOK_588 = 707615136;
    private static final int RAISED = 707615200;
    private static final long MAJOR = 5L << 32;

    private static PackageInfo info(long code) {
        PackageInfo info = new PackageInfo();
        info.packageName = "com.facebook.katana";
        info.setLongVersionCode(code);
        return info;
    }

    @Test
    public void theRaisedCodeAnswersTheOriginal() {
        assertEquals(FACEBOOK_588, OwnVersion.answer(RAISED, FACEBOOK_588, RAISED));
        assertEquals(FACEBOOK_588, OwnVersion.answer(Integer.MAX_VALUE, FACEBOOK_588, Integer.MAX_VALUE));
    }

    @Test
    public void anyOtherCodeKeepsWhatAndroidReported() {
        // Google Play services, WebView, stock Facebook beside a clone: not the raised number.
        for (int other : new int[] {254_513_000, FACEBOOK_588, RAISED - 1, 1}) {
            assertEquals(other, OwnVersion.answer(other, FACEBOOK_588, RAISED));
        }
        // An int above Integer.MAX_VALUE / 2 still reads back as the same 32 bits.
        assertEquals(-5, (int) OwnVersion.answer(-5, FACEBOOK_588, RAISED));
    }

    /** Before the patch writes the stubs, or when the code wasn't raised, nothing is guessed. */
    @Test
    public void unwrittenOrUnraisedStubsSwapNothing() {
        assertEquals(RAISED, OwnVersion.answer(RAISED, 0, 0));
        assertEquals(RAISED, OwnVersion.answer(RAISED, 0, RAISED));
        assertEquals(FACEBOOK_588, OwnVersion.answer(FACEBOOK_588, FACEBOOK_588, FACEBOOK_588));
        assertEquals(RAISED, OwnVersion.answer(RAISED, RAISED + 1, RAISED));
    }

    /** The extension alone (stubs at 0) hands back exactly what the PackageInfo holds, high half included. */
    @Test
    public void theStandInsReadThePackageInfo() {
        assertEquals(RAISED, OwnVersion.versionCode(info(RAISED)));
        assertEquals((long) RAISED, OwnVersion.longVersionCode(info(RAISED)));
        assertEquals(MAJOR | RAISED, OwnVersion.longVersionCode(info(MAJOR | RAISED)));
    }

    @Test
    public void aMissingPackageInfoThrowsLikeTheReadItReplaces() {
        assertThrows(NullPointerException.class, () -> OwnVersion.versionCode(null));
        assertThrows(NullPointerException.class, () -> OwnVersion.longVersionCode(null));
    }

    /** The patch finds the two stubs by name and shape, and the stand-ins by their descriptors. */
    @Test
    public void thePatchFindsItsStubsAndStandIns() throws Exception {
        for (String stub : new String[] {"originalVersionCode", "spoofedVersionCode"}) {
            Method method = OwnVersion.class.getDeclaredMethod(stub);
            assertEquals(int.class, method.getReturnType());
            assertTrue(stub, Modifier.isStatic(method.getModifiers()));
        }
        Method field = OwnVersion.class.getMethod("versionCode", PackageInfo.class);
        Method getter = OwnVersion.class.getMethod("longVersionCode", PackageInfo.class);
        assertEquals(int.class, field.getReturnType());
        assertEquals(long.class, getter.getReturnType());
        assertTrue(Modifier.isStatic(field.getModifiers()) && Modifier.isStatic(getter.getModifiers()));
    }
}
