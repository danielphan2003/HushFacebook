/*
 * Adapted from SysAdminDoc/HushMessenger at 858a6a24a7388c4a0d0e21c8a3b640de284dead1
 * (patches/.../misc/SpoofPackageVersionPatch.kt), which adapted it from rushiranpise/morphe-patches
 * at e3bb3af54e13ecfac60eb8bf9bdf287330f0529f and RookieEnough/De-Vanced at
 * 5cf78f17aae0665c196fb71d63d50726d9ba518e (SpoofPackageVersionPatch.kt). GPL-3.0. See NOTICE.
 * https://github.com/SysAdminDoc/HushMessenger
 *
 * Modified for Hushfacebook (Facebook), 2026: one patch holding the manifest change and the
 * self-read rewrite, checked in full before either changes anything; the original code is written
 * into OwnVersion's stubs instead of read back from a build record; and the patch marks itself in
 * SettingsStatus.
 */
package app.morphe.patches.facebook.updates.spoof

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.onBytecode
import app.morphe.util.onResources
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.Method
import org.w3c.dom.Document

internal const val SPOOF_VERSION_PATCH = "Spoof package version"
internal const val SPOOF_VERSION_KEY = "versionCode"
internal const val HIGHEST_VERSION_CODE = Int.MAX_VALUE

/** The Facebook releases the bundle declares. */
internal val DECLARED_VERSIONS: Set<String> = AppCompatibilities.facebook()
    .flatMap { it.targets }.mapNotNull { it.version }.toSet()

/** Android version codes are positive 32-bit integers, so 1 through 2147483647. */
internal fun validSpoofedVersionCode(value: Int?): Boolean = value != null && value >= 1

private fun supportedApks(declared: Set<String> = DECLARED_VERSIONS) = declared.sorted().joinToString(" or ")

/**
 * Refuses a release the bundle doesn't declare, and a version code that isn't a number, before
 * anything changes. Every build of a declared release passes: the arm64 and armv7 ones and the
 * Android 9 one, whose code isn't declared. Only the read count is pinned per build.
 */
internal fun validateVersion(versionName: String, versionCode: String, declared: Set<String> = DECLARED_VERSIONS) {
    if (versionName.trim() !in declared || versionCode.trim().toIntOrNull() == null) throw PatchException(
        "$SPOOF_VERSION_PATCH: this Facebook build ($versionName, version code $versionCode) isn't supported, so " +
            "nothing was changed. Use an unmodified Facebook ${supportedApks(declared)}.")
}

/**
 * Sets the manifest's version code from [real] to [spoofed]. Refused before anything changes when
 * the manifest has no manifest element, a versionCodeMajor (whose high half would change what the
 * number means) or a different code than the one the patcher read.
 */
internal fun Document.spoofVersionCode(real: String, spoofed: Int) {
    val root = documentElement
    if (root == null || root.tagName != "manifest") refuse("AndroidManifest.xml has no manifest element")
    if (!validSpoofedVersionCode(spoofed)) refuse("$spoofed isn't a version code from 1 to $HIGHEST_VERSION_CODE")
    if (root.hasAttribute("android:versionCodeMajor")) refuse("AndroidManifest.xml sets a versionCodeMajor")
    val code = root.getAttribute("android:versionCode").trim()
    val own = code.toIntOrNull()
    if (code != real.trim() || own == null) refuse("AndroidManifest.xml says its version code is \"$code\", not $real")
    // A lower number has the Play Store offering this same release as an update, and Android won't install it over
    // the version it came from.
    if (spoofed < own) throw PatchException(
        "$SPOOF_VERSION_PATCH: $spoofed is lower than this Facebook's own version code $own. Set the version number to $own or higher.")
    root.setAttribute("android:versionCode", spoofed.toString())
}

private fun refuse(reason: String): Nothing =
    throw PatchException("$SPOOF_VERSION_PATCH: $reason. Use an unmodified Facebook ${supportedApks()}.")

/** OwnVersion's stub for the original code, which the patch makes answer it. */
internal fun isOriginalVersionCodeStub(method: Method) =
    method.name == "originalVersionCode" && method.returnType == "I" && method.parameterTypes.isEmpty()

/** OwnVersion's stub for the raised code, which the patch makes answer it. */
internal fun isSpoofedVersionCodeStub(method: Method) =
    method.name == "spoofedVersionCode" && method.returnType == "I" && method.parameterTypes.isEmpty()

@Suppress("unused")
val spoofPackageVersionPatch = patch(
    name = "Spoof package version",
    description = "Gives Facebook a higher version number you pick, so the Play Store stops offering Meta's " +
        "updates. Facebook's own version checks still see its real number, but it may report the raised one to " +
        "Meta. It isn't selected by default because going back to a lower number means uninstalling first, which " +
        "erases Facebook's data, and it doesn't work on a Root Mount install. Pick it or Disable Play Store " +
        "updates, not both. Turn on Expert mode in Manager to pick it. Works as soon as you patch it in, with no " +
        "switch.",
    default = false,
) {
    category("Fixes")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    val versionCode by intOption(
        key = SPOOF_VERSION_KEY,
        default = HIGHEST_VERSION_CODE,
        title = "Version number",
        description = "A whole number up to $HIGHEST_VERSION_CODE, no lower than the Facebook you're patching. Pick " +
            "one higher than Facebook's version in the Play Store, or it will keep offering updates. The default is " +
            "the highest Android allows.",
        required = true,
    ) { validSpoofedVersionCode(it) }

    execute {
        val real = packageMetadata.versionCode
        validateVersion(packageMetadata.versionName, real)
        val spoofed = versionCode?.takeIf { validSpoofedVersionCode(it) }
            ?: refuse("no version code from 1 to $HIGHEST_VERSION_CODE was set")
        val code = real.trim().toInt()

        // Everything is checked before anything changes: the extension's stubs, every read's shape
        // and their count, then the manifest, which refuses before it writes.
        var targets = emptySet<String>()
        onBytecode {
            requireStatusMethod("spoofPackageVersion")
            val ownVersion = classDefByOrNull(OWN_VERSION)?.methods?.toList().orEmpty()
            if (ownVersion.none(::isOriginalVersionCodeStub) || ownVersion.none(::isSpoofedVersionCodeStub)) {
                throw PatchException("$SPOOF_VERSION_PATCH: $OWN_VERSION has no originalVersionCode()I and spoofedVersionCode()I")
            }
            // Only the methods that read a version code are kept, not every method of every class.
            val readers = mutableListOf<Method>()
            classDefForEach { classDef -> classDef.methods.filterTo(readers) { it.readsVersionCode() } }
            targets = planSelfVersionReads(readers, expectedSelfVersionReads(code))
        }
        onResources { document("AndroidManifest.xml").use { it.spoofVersionCode(real, spoofed) } }
        onBytecode {
            for (id in targets) {
                mutableClassDefBy(id.substringBefore("->")).methods.single { it.id() == id }.answerOwnVersionReads()
            }
            val ownVersion = mutableClassDefBy(OWN_VERSION).methods
            ownVersion.single(::isOriginalVersionCodeStub).returnEarly(code)
            ownVersion.single(::isSpoofedVersionCodeStub).returnEarly(spoofed)
            enableStatus("spoofPackageVersion")
        }
    }
}
