/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.updates.spoof

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spoof package version's code half: every PackageInfo version read in Facebook's own classes is
 * found and sent through OwnVersion, with the answer in the register the read wrote, and nothing is
 * rewritten when the count or a read's shape is off.
 */
class SpoofSelfReadsTest {
    private val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
    private val lookup = "Landroid/content/pm/PackageManager;->getPackageInfo(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;"
    private val manager = "Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;"

    private fun method(
        type: String,
        name: String,
        parameters: List<String>,
        returnType: String,
        registers: Int,
        smali: String,
        flags: Int = static,
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
            flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    private fun MutableMethod.body(): List<Instruction> = implementation!!.instructions.toList()

    /** AppComponentManager's shape: its own name, getPackageInfo, then the field. */
    private fun ownField(type: String = "Lcom/facebook/appcomponentmanager/AppComponentManagerService;") =
        method(type, "A0A", listOf("Landroid/content/Context;"), "I", 4, """
            invoke-virtual {p0}, $manager
            move-result-object v2
            invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v2, v0, v1}, $lookup
            move-result-object v0
            iget v0, v0, $VERSION_FIELD
            return v0
        """.trimIndent())

    /** A crash reporter's shape: the getter, its result in a register pair. */
    private fun ownGetter() = method("LX/0acr;", "A00", listOf("Landroid/content/Context;"), "J", 4, """
        invoke-virtual {p0}, $manager
        move-result-object v2
        invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
        move-result-object v1
        const/4 v0, 0x0
        invoke-virtual {v2, v1, v0}, $lookup
        move-result-object v0
        invoke-virtual {v0}, $VERSION_GETTER
        move-result-wide v0
        return-wide v0
    """.trimIndent())

    /** Another app's PackageInfo, read the same way: found too, and OwnVersion leaves it alone at run time. */
    private fun gms() = method("LX/1v0;", "A00", listOf("Landroid/content/Context;"), "I", 4, """
        invoke-virtual {p0}, $manager
        move-result-object v2
        const-string v0, "com.google.android.gms"
        const/4 v1, 0x0
        invoke-virtual {v2, v0, v1}, $lookup
        move-result-object v0
        iget v0, v0, $VERSION_FIELD
        return v0
    """.trimIndent())

    private fun fromParameter() = method("LX/0E7;", "A00", listOf("Landroid/content/pm/PackageInfo;"), "J", 3,
        "invoke-virtual {p0}, $VERSION_GETTER\nmove-result-wide v0\nreturn-wide v0")

    /** A wrapper that hands back a PackageInfo without reading its code has nothing to rewrite. */
    private fun wrapper() = method("LX/TAt;", "A0D", listOf("Landroid/content/Context;"), "Landroid/content/pm/PackageInfo;", 3, """
        invoke-virtual {p0}, $manager
        move-result-object v1
        invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
        move-result-object v0
        const/4 v2, 0x0
        invoke-virtual {v1, v0, v2}, $lookup
        move-result-object v0
        return-object v0
    """.trimIndent())

    private fun ids(methods: List<Method>) = findSelfVersionReads(methods).mapKeys { it.key.id() }

    @Test
    fun `every version read outside Morphe's classes is found and counted`() {
        val own = ownField()
        val getter = ownGetter()
        val other = gms()
        val parameter = fromParameter()
        val twice = method("LX/twice;", "A00", listOf("Landroid/content/pm/PackageInfo;"), "J", 4, """
            iget v0, p0, $VERSION_FIELD
            invoke-virtual {p0}, $VERSION_GETTER
            move-result-wide v1
            return-wide v1
        """.trimIndent())
        // Hushfacebook's own, Morphe's and OwnVersion's reads are never counted.
        val hush = ownField("Lapp/morphe/extension/shared/Utils;")
        val morphe = ownField("Lapp/morphe/extension/all/versioncode/Other;")
        val ownVersion = ownField(OWN_VERSION)

        val found = ids(listOf(own, getter, other, parameter, wrapper(), twice, hush, morphe, ownVersion))
        assertEquals(
            mapOf(own.id() to 1, getter.id() to 1, other.id() to 1, parameter.id() to 1, twice.id() to 2),
            found,
        )
    }

    @Test
    fun `the plan takes Hushfacebook's own reads beside Facebook's, uncounted`() {
        val own = ownField()
        val hush = ownField("Lapp/morphe/extension/shared/Utils;")
        val facebookExtension = ownField("Lapp/morphe/extension/facebook/settings/Report;")
        val morphe = ownField("Lapp/morphe/extension/all/versioncode/Other;")
        val ownVersion = ownField(OWN_VERSION)
        val playStore = ownField("Lapp/morphe/extension/facebook/updates/DisablePlayStoreUpdatesPatch;")
        val plan = planSelfVersionReads(listOf(own, hush, facebookExtension, morphe, ownVersion, playStore), expected = 1)
        assertEquals(sortedSetOf(own.id(), hush.id(), facebookExtension.id()), plan)
    }

    @Test
    fun `the field read becomes a call into the same register`() {
        val method = ownField()
        val before = method.body()
        method.answerOwnVersionReads()
        val code = method.body()
        assertEquals(before.size + 1, code.size)
        assertEquals(before.take(7), code.take(7))
        val call = code[7] as RegisterRangeInstruction
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals(OWN_VERSION_CODE, (call as ReferenceInstruction).reference.toString())
        assertEquals(0, call.startRegister)
        assertEquals(1, call.registerCount)
        assertEquals(Opcode.MOVE_RESULT, code[8].opcode)
        assertEquals(0, (code[8] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN, code[9].opcode)
    }

    @Test
    fun `a field read into another register keeps both`() {
        val method = method("LX/two;", "A00", listOf("Landroid/content/pm/PackageInfo;"), "I", 3,
            "iget v1, p0, $VERSION_FIELD\nreturn v1")
        method.answerOwnVersionReads()
        val code = method.body()
        val call = code[0] as RegisterRangeInstruction
        assertEquals(2, call.startRegister)
        assertEquals(1, (code[1] as OneRegisterInstruction).registerA)
    }

    @Test
    fun `the getter becomes a call with the same result`() {
        val method = ownGetter()
        val before = method.body()
        method.answerOwnVersionReads()
        val code = method.body()
        assertEquals(before.size, code.size)
        val call = code[7] as RegisterRangeInstruction
        assertEquals(OWN_LONG_VERSION_CODE, (call as ReferenceInstruction).reference.toString())
        assertEquals(0, call.startRegister)
        assertEquals(before.drop(8), code.drop(8))

        // A PackageInfo above v15 reaches the getter through the range form, and keeps its register.
        val high = method("LX/high;", "A00", listOf("Landroid/content/pm/PackageInfo;"), "J", 19,
            "move-object/from16 v17, p0\ninvoke-virtual/range {v17 .. v17}, $VERSION_GETTER\nmove-result-wide v0\nreturn-wide v0")
        high.answerOwnVersionReads()
        val range = high.body()[1] as RegisterRangeInstruction
        assertEquals(17, range.startRegister)
        assertEquals(OWN_LONG_VERSION_CODE, (range as ReferenceInstruction).reference.toString())
    }

    @Test
    fun `a wrong count or shape changes nothing`() {
        val methods = listOf(ownField(), ownGetter(), wrapper(), gms())
        assertEquals(setOf(methods[0].id(), methods[1].id(), methods[3].id()), planSelfVersionReads(methods, expected = 3))
        val before = methods.map { it.body() }
        val failure = assertThrows(PatchException::class.java) { planSelfVersionReads(methods, expected = 69) }
        assertTrue(failure.message, failure.message.orEmpty().contains("found 3 version code reads, expected 69"))
        assertEquals(before, methods.map { it.body() })

        // Unpinned, any count passes, but none at all is a build this patch can't recognise.
        assertEquals(3, planSelfVersionReads(methods, expected = null).size)
        assertThrows(PatchException::class.java) { planSelfVersionReads(listOf(wrapper()), expected = null) }

        val odd = method("LX/odd;", "A00", listOf("Landroid/content/pm/PackageInfo;"), "J", 3,
            "invoke-super {p0}, $VERSION_GETTER\nmove-result-wide v0\nreturn-wide v0")
        val shape = assertThrows(PatchException::class.java) { planSelfVersionReads(listOf(odd), expected = null) }
        assertTrue(shape.message, shape.message.orEmpty().contains("LX/odd;->A00(Landroid/content/pm/PackageInfo;)J reads its version with"))

        val write = method("LX/write;", "A00", listOf("Landroid/content/pm/PackageInfo;"), "V", 2,
            "const/4 v0, 0x1\niput v0, p0, $VERSION_FIELD\nreturn-void")
        assertThrows(PatchException::class.java) { planSelfVersionReads(listOf(ownField(), write), expected = null) }
    }

    /** Only the build that was measured has a pin; the others are checked for shape alone. */
    @Test
    fun `only the measured arm64 build is pinned`() {
        assertEquals(69, expectedSelfVersionReads(AppCompatibilities.FACEBOOK_TARGET_VERSION_CODE))
        assertEquals(null, expectedSelfVersionReads(AppCompatibilities.FACEBOOK_V7A_TARGET_VERSION_CODE))
        assertEquals(setOf(AppCompatibilities.FACEBOOK_TARGET_VERSION_CODE), SELF_VERSION_READS.keys)
    }
}
