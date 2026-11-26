/*
 * Adapted from SysAdminDoc/HushMessenger at 858a6a24a7388c4a0d0e21c8a3b640de284dead1
 * (extensions/messenger/.../OwnVersion.java), GPL-3.0. See NOTICE.
 * https://github.com/SysAdminDoc/HushMessenger
 *
 * Modified for Hushfacebook (Facebook), 2026: the patch writes the original and the raised codes
 * into two stubs here instead of a build record, Facebook's own PackageInfo is told apart by the
 * raised code it reports (Facebook's floor is Android 11, and this runs before any context is
 * stored), and the raw code is read through reflection, as DisablePlayStoreUpdatesPatch does, so
 * a version-read rewrite from another patch can't send it back here (#561).
 */
package app.morphe.extension.facebook.updates;

import android.content.pm.PackageInfo;
import app.morphe.extension.shared.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Facebook's own version code as it was before Spoof package version raised it. That patch sends
 * Facebook's reads of a PackageInfo version through here, so its own checks still see the build it
 * really is. AppComponentManager, for one, compares the code Android reports with the APK's. A
 * PackageInfo that doesn't report the raised code (another app's, or this one unpatched) keeps
 * what Android reported.
 */
@SuppressWarnings("unused")
public final class OwnVersion {

    private static final Field VERSION_CODE;
    private static final Method LONG_VERSION_CODE;
    private static volatile boolean announced;

    static {
        try {
            VERSION_CODE = PackageInfo.class.getField("versionCode");
            LONG_VERSION_CODE = PackageInfo.class.getMethod("getLongVersionCode");
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private OwnVersion() {}

    /** The code Facebook had before it was raised. The patch makes this answer it. */
    private static int originalVersionCode() {
        return 0;
    }

    /** The code the manifest was raised to. The patch makes this answer it. */
    private static int spoofedVersionCode() {
        return 0;
    }

    /** Stands in for {@code info.versionCode}, a null PackageInfo throwing as the read would. */
    public static int versionCode(PackageInfo info) {
        int reported = rawVersionCode(info);
        return (int) answer(reported, originalVersionCode(), spoofedVersionCode());
    }

    /** Stands in for {@code info.getLongVersionCode()}, keeping the high half Android reported. */
    public static long longVersionCode(PackageInfo info) {
        long reported = rawLongVersionCode(info);
        long low = answer((int) reported, originalVersionCode(), spoofedVersionCode());
        return (reported & 0xffff_ffff_0000_0000L) | (low & 0xffff_ffffL);
    }

    /**
     * The original code when {@code reported} is the raised one, else {@code reported}. Nothing is
     * swapped while either stub still answers 0 (the extension alone, before the patch writes them)
     * or when the code wasn't raised at all.
     */
    static long answer(int reported, int original, int spoofed) {
        if (original <= 0 || spoofed <= original || reported != spoofed) return reported & 0xffff_ffffL;
        if (!announced) {
            announced = true;
            Logger.printInfo(() -> "Facebook's own version reads answer " + original + ", the build it was patched from");
        }
        return original;
    }

    private static int rawVersionCode(PackageInfo info) {
        if (info == null) throw new NullPointerException("PackageInfo is null");
        try {
            return VERSION_CODE.getInt(info);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static long rawLongVersionCode(PackageInfo info) {
        if (info == null) throw new NullPointerException("PackageInfo is null");
        try {
            return (Long) LONG_VERSION_CODE.invoke(info);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException(cause);
        }
    }
}
