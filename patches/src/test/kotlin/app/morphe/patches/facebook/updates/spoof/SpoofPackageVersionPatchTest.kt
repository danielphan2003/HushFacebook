/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.updates.spoof

import app.morphe.patcher.patch.PatchException
import app.morphe.patches.shared.compat.AppCompatibilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Spoof package version's manifest half: the chosen number goes in, and a manifest that disagrees
 * with the patcher, sets a versionCodeMajor or would go lower is refused before anything changes.
 */
class SpoofPackageVersionPatchTest {
    private val real = AppCompatibilities.FACEBOOK_TARGET_VERSION_CODE.toString()

    private fun manifest(code: String = real, major: String? = null): Document {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val root = document.createElement("manifest")
        root.setAttribute("package", AppCompatibilities.FACEBOOK_PACKAGE)
        root.setAttribute("android:versionCode", code)
        root.setAttribute("android:versionName", AppCompatibilities.FACEBOOK_TARGET_VERSION)
        if (major != null) root.setAttribute("android:versionCodeMajor", major)
        document.appendChild(root)
        return document
    }

    private val Document.code get() = documentElement.getAttribute("android:versionCode")

    private fun assertContains(text: String?, part: String) =
        assertTrue("\"$text\" doesn't say \"$part\"", text.orEmpty().contains(part))

    @Test
    fun `raises the manifest code to the chosen number`() {
        val document = manifest()
        document.spoofVersionCode(real, HIGHEST_VERSION_CODE)
        assertEquals("2147483647", document.code)
        assertEquals(AppCompatibilities.FACEBOOK_TARGET_VERSION, document.documentElement.getAttribute("android:versionName"))

        val same = manifest()
        same.spoofVersionCode(real, real.toInt())
        assertEquals(real, same.code)

        val next = manifest()
        next.spoofVersionCode(real, real.toInt() + 1)
        assertEquals((real.toInt() + 1).toString(), next.code)
    }

    @Test
    fun `refuses a code below Facebook's own before changing it`() {
        for (code in listOf(1, real.toInt() - 1)) {
            val document = manifest()
            val failure = assertThrows(PatchException::class.java) { document.spoofVersionCode(real, code) }
            assertContains(failure.message, "lower than this Facebook's own version code $real. Set the version number to $real or higher.")
            assertFalse("the APK is fine, only the option needs changing", failure.message.orEmpty().contains("Use an unmodified"))
            assertEquals(real, document.code)
        }
    }

    @Test
    fun `refuses a manifest that disagrees with the patcher before changing it`() {
        val document = manifest(real)
        val failure = assertThrows(PatchException::class.java) { document.spoofVersionCode("707615135", HIGHEST_VERSION_CODE) }
        assertContains(failure.message, "not 707615135")
        assertContains(failure.message, "Use an unmodified Facebook")
        assertEquals(real, document.code)

        val blank = manifest("")
        assertThrows(PatchException::class.java) { blank.spoofVersionCode("", HIGHEST_VERSION_CODE) }
        assertEquals("", blank.code)
    }

    @Test
    fun `refuses a versionCodeMajor before changing it`() {
        val document = manifest(major = "1")
        val failure = assertThrows(PatchException::class.java) { document.spoofVersionCode(real, HIGHEST_VERSION_CODE) }
        assertContains(failure.message, "versionCodeMajor")
        assertEquals(real, document.code)
    }

    @Test
    fun `refuses codes below one before changing them`() {
        for (code in listOf(0, -1, Int.MIN_VALUE)) {
            val document = manifest()
            assertThrows(PatchException::class.java) { document.spoofVersionCode(real, code) }
            assertEquals(real, document.code)
        }
        assertFalse(validSpoofedVersionCode(null))
        assertTrue(validSpoofedVersionCode(1))
        assertTrue(validSpoofedVersionCode(Int.MAX_VALUE))
    }

    @Test
    fun `takes every build of a declared release and nothing else`() {
        validateVersion(AppCompatibilities.FACEBOOK_TARGET_VERSION, real)
        validateVersion(AppCompatibilities.FACEBOOK_TARGET_VERSION, AppCompatibilities.FACEBOOK_V7A_TARGET_VERSION_CODE.toString())
        // The Android 9 build's code isn't declared, and the release is.
        validateVersion(AppCompatibilities.FACEBOOK_TARGET_VERSION, "707615100")
        for ((name, code) in listOf("587.0.0.1.1" to real, AppCompatibilities.FACEBOOK_TARGET_VERSION to "", "" to real)) {
            val failure = assertThrows(PatchException::class.java) { validateVersion(name, code) }
            assertContains(failure.message, "isn't supported")
        }
    }

    @Test
    fun `starts unselected with a required option at the highest code`() {
        assertEquals(SPOOF_VERSION_PATCH, spoofPackageVersionPatch.name)
        assertFalse(spoofPackageVersionPatch.default)
        assertEquals("Fixes", spoofPackageVersionPatch.category)
        assertEquals(setOf(SPOOF_VERSION_KEY), spoofPackageVersionPatch.options.keys)
        val option = spoofPackageVersionPatch.options.getValue(SPOOF_VERSION_KEY)
        assertEquals(HIGHEST_VERSION_CODE, option.default)
        assertTrue(option.required)
        try {
            assertThrows(Exception::class.java) { spoofPackageVersionPatch.options.set(SPOOF_VERSION_KEY, 0) }
            assertThrows(Exception::class.java) { spoofPackageVersionPatch.options.set(SPOOF_VERSION_KEY, -5) }
            spoofPackageVersionPatch.options.set(SPOOF_VERSION_KEY, 707615200)
            assertEquals(707615200, option.value)
        } finally {
            option.reset()
        }
        assertEquals(HIGHEST_VERSION_CODE, option.value)
    }

    @Test
    fun `the description states the limits`() {
        val description = spoofPackageVersionPatch.description.orEmpty()
        assertContains(description, "Play Store")
        assertContains(description, "may report the raised one to Meta")
        assertContains(description, "uninstalling first")
        assertContains(description, "isn't selected by default")
        assertFalse(description.contains('—') || description.contains('–'))
    }
}
