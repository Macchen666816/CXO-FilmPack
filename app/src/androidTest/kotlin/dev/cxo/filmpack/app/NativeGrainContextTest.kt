package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.*

class NativeGrainContextTest : InstrumentationTestCase() {
    fun testCapturedFlagsAndFullProductContextCatalog() {
        val assets = instrumentation.targetContext.assets
        val contexts = NativeGrain8RenderingContext.read(assets.open("films/no-toning-context.cxoctx").use { it.readBytes() })
        val count = instrumentation.context.assets.open("grain-context.golden").use { verifyGrainRenderingContext(contexts,it) }
        assertEquals(99,count); assertEquals(153,contexts.size)
        val engine = PhotoProcessor(instrumentation.targetContext)
        assertEquals(153,engine.films.size)
        val bitmap = Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        try {
            val preview = PhotoGrainPreview(bitmap,1,1,0)
            for (film in engine.films) {
                assertNotNull(contexts.definition(film.token))
                val request = engine.grainRequest(preview,film,"KodakPortra400",NativeGrain8Geometry.Format.MANUAL,1f,100,17)
                assertEquals(contexts.resolve(film.token).color,request.color)
                assertFalse(request.neutralizeRedBlue)
                assertEquals("KodakPortra400",request.grainIdentifier)
            }
        } finally { bitmap.recycle() }
        assertTrue(runCatching { contexts.resolve("Original") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { contexts.resolve("KodakPortra400",7) }.exceptionOrNull() is IllegalArgumentException)
    }

    fun testRenderingIdentityOverridesGrainIdentity() {
        val engine = PhotoProcessor(instrumentation.targetContext)
        val bitmap = Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        try {
            val preview = PhotoGrainPreview(bitmap,64,64,2)
            val monochrome = engine.films.first { it.token == "KodakTriX400" }
            val color = engine.films.first { it.token == "KodakPortra400" }
            val bwFilmColorGrain = engine.grainRequest(preview,monochrome,"KodakPortra400",NativeGrain8Geometry.Format.SMALL,1f,100,17)
            val colorFilmBwGrain = engine.grainRequest(preview,color,"KodakTriX400",NativeGrain8Geometry.Format.SMALL,1f,100,17,true)
            assertFalse(bwFilmColorGrain.color); assertTrue(colorFilmBwGrain.color)
            assertEquals(2,bwFilmColorGrain.invocationScale); assertEquals(0,colorFilmBwGrain.invocationScale)
            assertEquals(64,colorFilmBwGrain.sourceWidth)
        } finally { bitmap.recycle() }
    }
}
