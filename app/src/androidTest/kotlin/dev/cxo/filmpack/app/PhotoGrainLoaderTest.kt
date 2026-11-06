package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.*
import java.util.zip.InflaterInputStream

class PhotoGrainLoaderTest : InstrumentationTestCase() {
    private val request = PhotoGrainRequest(1024,1024,1,"KodakPortra400",
        NativeGrain8Geometry.Format.MANUAL,1f,100,17,true,false)

    private fun loader(provider: (NativeGrain8Catalog.Definition) -> ByteArray): PhotoGrainLoader {
        val assets = instrumentation.context.assets
        val catalog = NativeGrain8Catalog.read(assets.open("grain-catalog.cxograin").use { it.readBytes() })
        val lut = NativeGrain8ColorLutFactory.fromQ15Transform(LcmsOptimizedQ15Transform.read(
            assets.open("grain-adobe-to-widegamut.lcmsbin").use { it.readBytes() }))
        return PhotoGrainLoader(catalog,lut,provider)
    }

    fun testOnDemandSourceOwnershipSeedAndContext() {
        var loads = 0; var returned: ByteArray? = null
        val loader = loader { definition ->
            loads++
            InflaterInputStream(instrumentation.context.assets.open("grain-catalog-sources/${definition.grainEnum}.rgbz"))
                .use { it.readBytes() }.also { returned = it }
        }
        val source = ShortArray(13*17*3) { ((it*307+12000)%32768).toShort() }
        fun output(layer: PhotoGrainLayer) = ShortArray(source.size).also {
            layer.kernel.renderRgbQ15(source,it,13,67,53)
        }
        val first = output(loader.prepare(request))
        requireNotNull(returned).fill(0) // A provider cannot mutate the loader's owned source.
        assertTrue(first.contentEquals(output(loader.prepare(request))))
        val reseeded = output(loader.prepare(request.copy(seed=19)))
        assertFalse(first.contentEquals(reseeded))
        assertTrue(first.contentEquals(output(loader.prepare(request))))
        val neutral = output(loader.prepare(request.copy(neutralizeRedBlue=true)))
        assertFalse(first.contentEquals(neutral))
        assertTrue(source.contentEquals(output(loader.prepare(request.copy(intensity=0)))))
        assertEquals(1,loads)
        loader.clear(); loader.prepare(request)
        assertEquals(2,loads)
    }

    fun testUnsupportedGeometryAndCorruptSourceRejectedBeforeRendering() {
        var loads = 0
        val loader = loader { loads++; ByteArray(1000*1000*3) }
        val invalid = listOf(request.copy(sourceWidth=0),
            request.copy(seed=-1),request.copy(seed=0x100000000L),request.copy(intensity=201),
            request.copy(invocationScale=3),request.copy(sourceWidth=1,invocationScale=2),
            request.copy(grainIdentifier="Astia"))
        for (item in invalid) assertTrue(runCatching { loader.prepare(item) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0,loads)

        val orientedLoader = loader { definition ->
            InflaterInputStream(instrumentation.context.assets.open("grain-catalog-sources/${definition.grainEnum}.rgbz"))
                .use { it.readBytes() }
        }
        val oriented = orientedLoader.prepare(request.copy(
            sourceWidth=80,sourceHeight=40,sourceExifOrientation=6
        ))
        assertEquals(40,oriented.width)
        assertEquals(80,oriented.height)

        val corrupt = runCatching { loader.prepare(request) }.exceptionOrNull()
        assertTrue(corrupt is IllegalArgumentException && corrupt.message?.contains("Source patch does not match") == true)
        assertEquals(1,loads)
    }
}
