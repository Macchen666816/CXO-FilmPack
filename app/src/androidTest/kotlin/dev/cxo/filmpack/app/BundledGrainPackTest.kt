package dev.cxo.filmpack.app

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.NativeGrain8Geometry
import java.io.File
import java.io.RandomAccessFile

/** Verifies the production APK provisions its bundled grain pack on first use. */
class BundledGrainPackTest : InstrumentationTestCase() {
    fun testPrepareProvisionsPackWithoutOpenInstalled() {
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "bundled-grain-prepare-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            GrainPackStore(IsolatedFilesContext(base, root)).use { store ->
                val request = PhotoGrainRequest(1024, 1024, 1, "KodakPortra400",
                    NativeGrain8Geometry.Format.MANUAL, 1f, 100, 17, true, false)
                assertEquals(1024, store.prepare(request).width)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    fun testMissingInstallationIsProvisionedFromBundledAsset() {
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "bundled-grain-test-${System.nanoTime()}").apply { mkdirs() }
        val context = IsolatedFilesContext(base, root)
        try {
            val installed = File(File(root, "grain"), GrainPackStore.BUNDLED_ASSET)
            GrainPackStore(context).use { store ->
                assertEquals(99, store.openInstalled().size)
                assertEquals(GrainPackStore.PACK_BYTES, installed.length())
            }
            val firstModified = installed.lastModified()
            GrainPackStore(context).use { reopened ->
                assertEquals(99, reopened.openInstalled().size)
                assertEquals(firstModified, installed.lastModified())
            }
            val bad = File(root, "bad.cxograinpack").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            try {
                assertTrue(runCatching {
                    GrainPackStore(context).use { it.importPack(Uri.fromFile(bad), {}, { false }) }
                }.isFailure)
                assertEquals(firstModified, installed.lastModified())
                assertEquals(GrainPackStore.PACK_BYTES, installed.length())
            } finally {
                bad.delete()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /** Both a truncated installation and a same-size bad hash must recover;
     * validation must not just trust the existence/length of an installed file. */
    fun testCorruptInstallationRestoresBundledPackAndRenderingAfterReopen() {
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "bundled-grain-repair-test-${System.nanoTime()}").apply { mkdirs() }
        val context = IsolatedFilesContext(base, root)
        val installed = File(File(root, "grain"), GrainPackStore.BUNDLED_ASSET)
        val request = PhotoGrainRequest(1024, 1024, 1, "KodakPortra400",
            NativeGrain8Geometry.Format.MANUAL, 1f, 100, 17, true, false)
        val input = FloatArray(32 * 3) { .4f }
        val expected = FloatArray(input.size)
        try {
            GrainPackStore(context).use { store ->
                store.prepare(request).kernel.renderNormalizedRgb(input, expected, 32, 13, 7)
            }
            for (truncated in listOf(true, false)) {
                if (truncated) installed.writeBytes(byteArrayOf(1, 2, 3))
                else RandomAccessFile(installed, "rw").use { file ->
                    file.seek(4_000_000)
                    val original = file.readUnsignedByte()
                    file.seek(4_000_000)
                    file.write(original xor 1)
                }
                GrainPackStore(context).use { repaired ->
                    // Exercise both public lazy-provisioning entry points.
                    if (truncated) assertEquals(99, repaired.openInstalled().size)
                    val actual = FloatArray(input.size)
                    repaired.prepare(request).kernel.renderNormalizedRgb(input, actual, 32, 13, 7)
                    assertTrue("Repair must restore identical grain rendering", expected.contentEquals(actual))
                    assertEquals(GrainPackStore.PACK_BYTES, installed.length())
                }
                assertEquals("Repair must remove staging files", 1, installed.parentFile!!.listFiles()!!.size)
                // A new store independently hashes and opens the committed replacement.
                GrainPackStore(context).use { assertEquals(99, it.openInstalled().size) }
            }
        } finally { root.deleteRecursively() }
    }

    private class IsolatedFilesContext(base: Context, private val isolatedFiles: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedFiles
    }
}
