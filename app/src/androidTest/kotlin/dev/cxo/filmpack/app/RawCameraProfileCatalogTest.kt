package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import org.json.JSONObject

@Suppress("DEPRECATION")
class RawCameraProfileCatalogTest : InstrumentationTestCase() {
    fun testEveryBatchProfileLoadsThroughProductionCatalog() {
        val context = instrumentation.targetContext
        val catalog = context.assets.open("raw/batch-generated/cameras.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        assertEquals("CXOCAMERAS1", catalog.getString("schema"))
        val rows = catalog.getJSONArray("cameras")
        assertEquals(catalog.getInt("generatedCameraCount"), rows.length())
        assertTrue("Expected the native-validated camera batch", rows.length() > 400)
        assertEquals(53, catalog.getJSONObject("sourceCounts").getInt("external_dxoCB"))
        assertEquals(52, catalog.getJSONObject("generatedSourceCounts").getInt("external_dxoCB"))

        val profiles = RawCameraProfiles(context)
        val bodyIds = HashSet<Int>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val bodyId = row.getInt("bodyId")
            assertTrue("Duplicate batch body ID $bodyId", bodyIds.add(bodyId))
            val models = row.getJSONArray("models")
            assertTrue("Body $bodyId has no EXIF model alias", models.length() > 0)
            val model = models.getString(0)
            val camera = profiles.forModel(model)
            assertNotNull("Could not resolve batch alias $model", camera)
            assertEquals(row.getString("id"), camera!!.id)
            assertTrue(camera.exposureBiasEv.isFinite())
            assertTrue(camera.fidelity.sensorToWorking(5000f).all { it.isFinite() })
        }
        assertEquals(rows.length(), bodyIds.size)

        val externalBody = profiles.forModel("DC-S1M2")
        assertNotNull("External Panasonic S1II profile did not load", externalBody)
        assertEquals("body-5071", externalBody!!.id)
    }
}
