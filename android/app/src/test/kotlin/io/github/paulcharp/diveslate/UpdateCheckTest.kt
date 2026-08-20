package io.github.paulcharp.diveslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the updater will and will not act on.
 *
 * This is the one place in the app where a remote file decides that a binary
 * gets downloaded and handed to Android's installer, so every check in
 * [UpdateCheck.parse] is load-bearing: the host pin, the scheme, the shape of
 * the checksum, the plausibility of the size. None of them needs a device, and
 * until now none of them had a test.
 */
class UpdateCheckTest {

    private val digest = "a".repeat(64)

    private fun manifest(
        versionCode: Int = 99,
        versionName: String = "9.9.9",
        apkUrl: String =
            "https://github.com/paul-charp/Dive-Slate/releases/download/v9.9.9/app.apk",
        apkSha256: String = digest,
        apkSize: Long = 2_000_000,
    ): String = """
        {
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "apkUrl": "$apkUrl",
          "apkSha256": "$apkSha256",
          "apkSize": $apkSize,
          "releaseUrl": "https://github.com/paul-charp/Dive-Slate/releases/tag/v9.9.9"
        }
    """.trimIndent()

    @Test
    fun `a well-formed manifest parses`() {
        val release = UpdateCheck.parse(manifest())
        assertEquals(99, release.versionCode)
        assertEquals("9.9.9", release.versionName)
        assertEquals(2_000_000, release.apkSize)
        assertEquals(digest, release.apkSha256)
    }

    /** GitHub serves release assets from more than one host, and all of them are pinned. */
    @Test
    fun `every host a release asset actually comes from is accepted`() {
        for (host in listOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )) {
            UpdateCheck.parse(manifest(apkUrl = "https://$host/whatever/app.apk"))
        }
    }

    /**
     * The manifest is the trust root, and it is not allowed to send the app
     * somewhere else for the binary.
     */
    @Test
    fun `an APK hosted anywhere else is refused`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            UpdateCheck.parse(manifest(apkUrl = "https://example.com/app.apk"))
        }
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("not hosted on GitHub"))
    }

    @Test
    fun `plain http is refused even from GitHub`() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateCheck.parse(manifest(apkUrl = "http://github.com/x/app.apk"))
        }
    }

    /** A URL that will not parse is refused rather than waved through with a null host. */
    @Test
    fun `an unparseable URL is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateCheck.parse(manifest(apkUrl = "not a url at all"))
        }
    }

    /**
     * The checksum is the only thing between a substituted download and an
     * install, so anything that is not a SHA-256 digest is not a checksum.
     */
    @Test
    fun `a checksum that is not a SHA-256 digest is refused`() {
        for (bad in listOf("", "deadbeef", "z".repeat(64), "a".repeat(63), "a".repeat(65))) {
            assertThrows(
                "accepted '$bad' as a checksum",
                IllegalArgumentException::class.java,
            ) { UpdateCheck.parse(manifest(apkSha256 = bad)) }
        }
    }

    /** Uppercase is the same digest; it is compared lowercased. */
    @Test
    fun `an uppercase checksum is accepted and normalised`() {
        assertEquals(digest, UpdateCheck.parse(manifest(apkSha256 = "A".repeat(64))).apkSha256)
    }

    @Test
    fun `an implausible size is refused`() {
        for (bad in listOf(0L, -1L, 101L * 1024 * 1024)) {
            assertThrows(
                "accepted $bad bytes",
                IllegalArgumentException::class.java,
            ) { UpdateCheck.parse(manifest(apkSize = bad)) }
        }
    }

    /**
     * A field missing is a manifest that cannot be acted on, and guessing a
     * default for it would be the "degrade to a guess" this codebase refuses in
     * its derived figures.
     */
    @Test
    fun `a manifest missing a field is refused rather than defaulted`() {
        val full = manifest()
        for (field in listOf(
            "versionCode", "versionName", "apkUrl", "apkSha256", "apkSize", "releaseUrl",
        )) {
            val without = full.lines().filterNot { it.trim().startsWith("\"$field\"") }
                .joinToString("\n")
                // The line before the removed one may now carry a trailing comma.
                .replace(Regex(",(\\s*})"), "$1")
            assertThrows("accepted a manifest with no $field", Exception::class.java) {
                UpdateCheck.parse(without)
            }
        }
    }

    @Test
    fun `something that is not JSON at all is refused`() {
        for (body in listOf("", "<html>404</html>", "null")) {
            assertThrows(Exception::class.java) { UpdateCheck.parse(body) }
        }
    }
}
