package dev.cxo.filmpack.app

import android.test.AndroidTestCase
import dev.cxo.filmpack.app.post.*
import kotlin.math.abs

@Suppress("DEPRECATION")
class EdgeDefectsTest:AndroidTestCase() {
    fun testCatalogResourcesAndZeroIntensity() {
        val choices=EdgeDefects.catalog(context)
        assertEquals(46,choices.size)
        assertEquals(46,choices.map{it.id}.distinct().size)
        assertEquals(23,choices.map{it.asset}.distinct().size)
        assertNull(EdgeDefects.prepare(context,EdgeDefectSettings(),64,48))
        assertNull(EdgeDefects.prepare(context,EdgeDefectSettings(id=choices[0].id,intensity=0),64,48))
        for(c in choices) {
            val renderer=EdgeDefects.prepare(context,EdgeDefectSettings(c.id,100,EdgePosition.TOP),64,48)!!
            val row=IntArray(64){0xff777777.toInt()};renderer.applyRow(row,0)
            assertTrue(c.id,row.all{(it ushr 24)==255})
        }
    }
    fun testDeterminismAndTiledOffset() {
        val settings=EdgeDefectSettings("fuitelum1",73,EdgePosition.TOP,9382)
        val a=EdgeDefects.prepare(context,settings,64,48)!!
        val b=EdgeDefects.prepare(context,settings,64,48)!!
        var changed=false
        for(y in 0 until 48) {
            val full=IntArray(64){0xff597793.toInt()};val other=full.copyOf()
            a.applyRow(full,y);b.applyRow(other,y)
            assertTrue("same seed",full.contentEquals(other))
            val tiled=IntArray(70){0xff597793.toInt()}
            b.applyRow(tiled,y,0,21,3);b.applyRow(tiled,y,21,43,24)
            assertTrue("tile offsets",full.contentEquals(tiled.copyOfRange(3,67)))
            assertEquals(0xff597793.toInt(),tiled[0]);assertEquals(0xff597793.toInt(),tiled[69])
            changed=changed||full.any{it!=0xff597793.toInt()}
        }
        assertTrue("effect must change pixels",changed)
    }
    fun testAllExifOrientationsMatchDisplayedGeometry() {
        val sw=64;val sh=48
        val settings=EdgeDefectSettings("fuitelum1",82,EdgePosition.LEFT,51)
        for(orientation in 1..8) {
            val dw=if(orientation>=5)sh else sw;val dh=if(orientation>=5)sw else sh
            val display=IntArray(dw*dh){0xff597793.toInt()}
            val source=IntArray(sw*sh){0xff597793.toInt()}
            val dr=EdgeDefects.prepare(context,settings,dw,dh,1)!!
            val sr=EdgeDefects.prepare(context,settings,sw,sh,orientation)!!
            for(y in 0 until dh)dr.applyRow(display,y,0,dw,y*dw)
            for(y in 0 until sh)sr.applyRow(source,y,0,sw,y*sw)
            for(y in 0 until sh)for(x in 0 until sw) {
                val dx:Int;val dy:Int
                when(orientation) {
                    2->{dx=sw-1-x;dy=y};3->{dx=sw-1-x;dy=sh-1-y};4->{dx=x;dy=sh-1-y}
                    5->{dx=y;dy=x};6->{dx=sh-1-y;dy=x};7->{dx=sh-1-y;dy=sw-1-x};8->{dx=y;dy=sw-1-x}
                    else->{dx=x;dy=y}
                }
                val expected=display[dy*dw+dx];val actual=source[y*sw+x]
                for(shift in listOf(0,8,16))assertTrue("orientation $orientation at $x,$y",abs(((expected ushr shift)and 255)-((actual ushr shift)and 255))<=1)
            }
        }
    }
}
