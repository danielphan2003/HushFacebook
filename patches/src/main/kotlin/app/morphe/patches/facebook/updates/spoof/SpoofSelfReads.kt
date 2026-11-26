/*
 * Adapted from SysAdminDoc/HushMessenger at 858a6a24a7388c4a0d0e21c8a3b640de284dead1
 * (patches/.../misc/SpoofSelfReads.kt), GPL-3.0. See NOTICE.
 * https://github.com/SysAdminDoc/HushMessenger
 *
 * Modified for Hushfacebook (Facebook), 2026: the count is pinned per Facebook build by version
 * code instead of by Messenger's control profile, and only where it was measured from real bytes.
 * Hushfacebook's own extension classes have their reads answered too (uncounted), as Disable Play
 * Store updates does, so its diagnostic report names the real version; OwnVersion itself and the
 * Play Store patch's class are left alone.
 */
package app.morphe.patches.facebook.updates.spoof

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

internal const val OWN_VERSION = "$EXTENSION_PACKAGE/updates/OwnVersion;"
private const val PACKAGE_INFO = "Landroid/content/pm/PackageInfo;"
internal const val VERSION_FIELD = "$PACKAGE_INFO->versionCode:I"
internal const val VERSION_GETTER = "$PACKAGE_INFO->getLongVersionCode()J"
internal const val OWN_VERSION_CODE = "$OWN_VERSION->versionCode($PACKAGE_INFO)I"
internal const val OWN_LONG_VERSION_CODE = "$OWN_VERSION->longVersionCode($PACKAGE_INFO)J"

/** Every class Hushfacebook or any other Morphe extension merges in starts with this. */
private const val MORPHE_CLASSES = "Lapp/morphe/"

/** Hushfacebook's own extension classes, whose reads are answered but not counted. */
private val HUSH_CLASSES = listOf("$EXTENSION_PACKAGE/", "Lapp/morphe/extension/shared/")

/** Classes whose reads stay as they are: OwnVersion's own, and the Play Store patch's, which read through reflection. */
private val LEFT_ALONE = setOf(OWN_VERSION, "$EXTENSION_PACKAGE/updates/DisablePlayStoreUpdatesPatch;")

/**
 * Every PackageInfo version read outside Morphe's classes in Facebook 588.0.0.42.55's arm64 build
 * (version code 707615136): 69 in 66 methods, 51 field reads and 18 getter calls. Measured with
 * dexlib2 on the classes*.dex the patcher loads, from the published Hushfacebook 0.24.0 build of
 * that original (Helium's standalone WebView dex in assets and lib holds more, which the patcher
 * never reads). Most read another app (Google Play services, the Play Store, WebView) and keep
 * what Android reports; the rest are Facebook's own, AppComponentManager's check, Profilo's trace
 * end and Play Billing among them. A different count means the build changed, and nothing is
 * rewritten.
 *
 * The armv7 build (707615135) and the Android 9 build are separate rebuilds that weren't measured,
 * so they have no pin: every read is still checked for a shape this patch rewrites before any is.
 */
internal val SELF_VERSION_READS: Map<Int, Int> = mapOf(AppCompatibilities.FACEBOOK_TARGET_VERSION_CODE to 69)

/** The pinned count for the build with [versionCode], or null where none was measured. */
internal fun expectedSelfVersionReads(versionCode: Int): Int? = SELF_VERSION_READS[versionCode]

internal fun Method.id() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.registers(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

private fun Instruction.isVersionRead() = reference().let { it == VERSION_FIELD || it == VERSION_GETTER }

private fun Method.versionReads() = code().count { it.isVersionRead() }

/** Whether [this] is one of Facebook's own classes rather than one a Morphe extension merged in. */
private fun Method.isFacebooks() = !definingClass.startsWith(MORPHE_CLASSES)

private fun Method.isHushfacebooks() = HUSH_CLASSES.any { definingClass.startsWith(it) } && definingClass !in LEFT_ALONE

/**
 * Every method of Facebook's own that reads a PackageInfo version code, with how many reads each
 * has. Where a PackageInfo came from isn't traced: a parameter, a field, a cached wrapper or a loop
 * over services can all hold Facebook's own, and OwnVersion checks the code when the read runs, so
 * another app's PackageInfo keeps what Android reported.
 */
internal fun findSelfVersionReads(methods: Iterable<Method>): Map<Method, Int> = methods
    .filter { it.isFacebooks() }
    .mapNotNull { method -> method.versionReads().takeIf { it > 0 }?.let { method to it } }
    .toMap()

private fun refuse(reason: String): Nothing = throw PatchException("$SPOOF_VERSION_PATCH: $reason. Nothing was changed.")

/** Checks every read in [this] can be rewritten, before any of them is. */
internal fun Method.validateVersionReads() {
    for (instruction in code().filter { it.isVersionRead() }) when (instruction.opcode) {
        Opcode.IGET -> Unit
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE ->
            if (instruction.registers().size != 1) refuse("${id()} reads its version in an unknown way")
        else -> refuse("${id()} reads its version with ${instruction.opcode.name}")
    }
}

/**
 * The ids of the methods to rewrite: Facebook's own once their reads add up to [expected] (any
 * count when it's null), and Hushfacebook's own beside them. Every read has to have a shape this
 * patch rewrites, or nothing is.
 */
internal fun planSelfVersionReads(methods: Iterable<Method>, expected: Int?): Set<String> {
    val all = methods.toList()
    val found = findSelfVersionReads(all)
    val total = found.values.sum()
    if (expected != null && total != expected) refuse("found $total version code reads, expected $expected")
    if (total == 0) refuse("found no version code reads")
    val ours = all.filter { it.isHushfacebooks() && it.versionReads() > 0 }
    (found.keys + ours).forEach { it.validateVersionReads() }
    return (found.keys + ours).mapTo(sortedSetOf()) { it.id() }
}

/**
 * Sends each version read in [this] through OwnVersion, which answers the original code for
 * Facebook's own PackageInfo and what Android reported for any other. The field read becomes a
 * call and a move-result into the same register.
 */
internal fun MutableMethod.answerOwnVersionReads() {
    validateVersionReads()
    val reads = code().withIndex().filter { it.value.isVersionRead() }.map { it.index }
    for (index in reads.reversed()) {
        val instruction = code()[index]
        if (instruction.opcode == Opcode.IGET) {
            val read = instruction as TwoRegisterInstruction
            replaceInstruction(index, "invoke-static/range {v${read.registerB} .. v${read.registerB}}, $OWN_VERSION_CODE")
            addInstruction(index + 1, "move-result v${read.registerA}")
        } else {
            val info = instruction.registers().single()
            replaceInstruction(index, "invoke-static/range {v$info .. v$info}, $OWN_LONG_VERSION_CODE")
        }
    }
}
