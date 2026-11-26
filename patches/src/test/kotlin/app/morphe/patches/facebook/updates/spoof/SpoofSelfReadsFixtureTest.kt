/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.updates.spoof

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spoof package version on every Facebook build the bundle declares: each class type once, as the
 * patcher's class map holds it, every read in a shape the patch rewrites, the arm64 build at its
 * pinned count, and AppComponentManager's version check among the reads. Reads the fixture bundles
 * from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class SpoofSelfReadsFixtureTest {
    @Test
    fun `every declared build's version reads can be answered`() {
        val version = AppCompatibilities.FACEBOOK_TARGET_VERSION
        val bundles = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }
        val miscounts = mutableListOf<String>()
        for (bundle in bundles) {
            val seen = mutableSetOf<String>()
            val methods = mutableListOf<Method>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (!seen.add(classDef.type)) continue
                    // Copied out: FixtureDex's parsed dex isn't kept past the visit.
                    for (method in classDef.methods) {
                        if (findSelfVersionReads(listOf(method)).isNotEmpty()) methods += ImmutableMethod.of(method)
                    }
                }
            }
            val suffix = bundle.nameWithoutExtension.substringAfter("-$version-")
            val expected = if (suffix == "arm64-v8a") expectedSelfVersionReads(AppCompatibilities.FACEBOOK_TARGET_VERSION_CODE) else null
            val total = findSelfVersionReads(methods).values.sum()
            if (expected != null && total != expected) {
                miscounts += "${bundle.name}: $total not $expected"
                continue
            }
            val plan = planSelfVersionReads(methods, expected)
            assertTrue("${bundle.name} $plan", plan.any { it.startsWith("Lcom/facebook/appcomponentmanager/AppComponentManagerService;->") })
        }
        assertEquals(emptyList<String>(), miscounts)
    }
}
