package info.piepgras.gridracer.legal

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Holds [AppPermissions] to the manifest, in both directions.
 *
 * The shipped APK's manifest is the merge of the hand-written one and every library's, so a
 * permission can ship without anyone having typed it (Play Billing, androidx.core and WorkManager
 * all add some). The Permissions screen must list those too - a permissions page that omits
 * what the app actually holds is wrong in the direction reviewers check.
 *
 * The manifest paths arrive as system properties from `tasks.withType<Test>` in
 * `app/build.gradle.kts`, which also declares the source manifest as a task input. Without that
 * input, adding a permission would leave the test task UP-TO-DATE and this test would never run
 * for exactly the change it exists to catch.
 */
class AppPermissionsTest {

    private val catalogued: Set<String> = AppPermissions.DECLARED.flatMap { it.manifestNames }.toSet()

    @Test
    fun `every permission in the source manifest has a catalog entry`() {
        val missing = permissionsIn(sourceManifest()) - catalogued
        assertTrue(
            "Declared in AndroidManifest.xml but not explained in AppPermissions (add an entry and its strings): $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the catalog and the merged manifest agree`() {
        val merged = mergedManifest()
        assumeTrue("merged manifest not built yet - run :app:assembleDebug first", merged != null)
        val shipped = permissionsIn(merged!!)
        assertEquals(
            "Shipped in the merged manifest (added by a library?) but not explained in AppPermissions",
            emptySet<String>(),
            shipped - catalogued,
        )
        assertEquals(
            "Explained in AppPermissions but no longer in the merged manifest - remove the stale entry",
            emptySet<String>(),
            catalogued - shipped,
        )
    }

    @Test
    fun `keys and manifest names are unique`() {
        val keys = AppPermissions.DECLARED.map { it.key } + AppPermissions.NEVER_REQUESTED.map { it.key }
        assertEquals("duplicate keys", keys.distinct(), keys)
        val names = AppPermissions.DECLARED.flatMap { it.manifestNames }
        assertEquals("a manifest name appears in two entries", names.distinct(), names)
    }

    private fun sourceManifest(): File {
        val path = System.getProperty("app.sourceManifest")
            ?: error("app.sourceManifest is not set - see tasks.withType<Test> in app/build.gradle.kts")
        return File(path).also { assertTrue("no manifest at $it", it.isFile) }
    }

    /** The debug/release merged manifest, wherever this AGP version puts it under the build dir. */
    private fun mergedManifest(): File? {
        val buildDir = System.getProperty("app.buildDir") ?: return null
        // This app has no BuildConfig class; only the debug variant runs unit tests here.
        return File(buildDir, "intermediates/merged_manifest/debug")
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.firstOrNull { it.name == "AndroidManifest.xml" }
    }

    private fun permissionsIn(manifest: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        return listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("android:name").nodeValue }
        }.toSet()
    }
}
