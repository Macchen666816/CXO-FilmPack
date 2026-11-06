package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.test.AndroidTestCase
import dev.cxo.filmpack.app.post.*
import dev.cxo.filmpack.core.PostProcessSettings
import java.util.concurrent.CancellationException

class PreviewBaseCacheTest : AndroidTestCase() {
    fun testPostDragsReuseFilmAndFinalMatchesDirectRender() {
        val source=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888).apply {eraseColor(0xff987654.toInt())}
        val cache=PreviewBaseCache();var calls=0
        val base:(Bitmap)->Bitmap={input -> calls++;input.copy(Bitmap.Config.ARGB_8888,true).apply {eraseColor(0xff709050.toInt())}}
        fun frame(interactive:Boolean,brightness:Int)=cache.frame("photo-film-100",source,interactive,false,{false},base,
            {applyPostEditing(context,it,PostEditingSettings(PostProcessSettings(brightness=brightness))) {false}})
        try {
            frame(false,0).recycle();assertEquals(1,calls)
            for(value in listOf(-60,-30,0,30,60)) {
                val small=frame(true,value)
                assertEquals(640,small.width);small.recycle()
            }
            assertEquals("Post-only drags must not rerun film",1,calls)
            val final=frame(false,23)
            val expected=base(source)
            applyPostEditing(context,expected,PostEditingSettings(PostProcessSettings(brightness=23))) {false}
            try {assertTrue(final.sameAs(expected));assertEquals(1200,final.width)} finally {final.recycle();expected.recycle()}
            val unchanged=frame(false,0)
            try {assertEquals(0xff709050.toInt(),unchanged.getPixel(500,500))} finally {unchanged.recycle()}
        } finally {cache.close();source.recycle()}
    }

    fun testBaseKeyInvalidationAndGrainGridPreserved() {
        val source=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888)
        val cache=PreviewBaseCache();val dimensions=mutableListOf<Int>()
        val base:(Bitmap)->Bitmap={input -> dimensions+=input.width;input.copy(Bitmap.Config.ARGB_8888,true)}
        try {
            cache.frame(1,source,true,false,{false},base,{it}).recycle()
            assertEquals(listOf(640),dimensions)
            cache.frame(2,source,true,true,{false},base,{it}).recycle()
            assertEquals(listOf(640,1200),dimensions)
            cache.frame(2,source,false,true,{false},base,{it}).recycle()
            assertEquals(listOf(640,1200,1200),dimensions)
            cache.frame(2,source,true,true,{false},base,{it}).recycle()
            assertEquals(3,dimensions.size)
        } finally {cache.close();source.recycle()}
    }

    fun testCancelledComputationCannotPoisonCache() {
        val source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
        val cache=PreviewBaseCache();var cancel=false;var calls=0
        try {
            try {
                cache.frame(1,source,false,false,{cancel},{input -> calls++;cancel=true;input.copy(Bitmap.Config.ARGB_8888,true)},{it})
                fail("Cancellation required")
            } catch(expected:CancellationException) { }
            cancel=false
            cache.frame(1,source,false,false,{cancel},{input -> calls++;input.copy(Bitmap.Config.ARGB_8888,true)},{it}).recycle()
            assertEquals(2,calls)
        } finally {cache.close();source.recycle()}
    }
}
