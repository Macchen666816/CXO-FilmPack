package dev.cxo.filmpack.engine

import dev.cxo.filmpack.core.NativeEdgeBlend
import dev.cxo.filmpack.core.NativeEdgePlacement
import kotlin.math.floor

/** Original resources and recovered mode-6 blend (see the Android source for the evidence notes). */
object EdgeDefects {
    fun catalog(assets: AssetProvider): List<EdgeDefectChoice> =
        MiniJson.rows(assets.read("edge-defects/catalog.json").decodeToString()).map {
            EdgeDefectChoice(it["id"] as String, it["name"] as String, it["isNotEdge"] as? Boolean ?: false,
                it["grayscale"] as? Boolean ?: false, it["asset"] as String)
        }

    /** [loadTexture] decodes a bundled PNG to straight-alpha ARGB, downsampled so its long edge is ≤1024. */
    fun prepare(assets: AssetProvider, loadTexture: (String) -> Texture, settings: EdgeDefectSettings,
                width: Int, height: Int, orientation: Int = 1): Renderer? {
        require(width > 0 && height > 0)
        if (settings.id.isEmpty() || settings.intensity <= 0) return null
        val choice = catalog(assets).firstOrNull { it.id == settings.id }
            ?: throw IllegalArgumentException("Unknown edge defect: ${settings.id}")
        val texture = loadTexture(choice.asset)
        return Renderer(texture.pixels, texture.width, texture.height, choice, settings, width, height, orientation)
    }

    /** Immutable texture state. applyRow is safe on independent worker buffers. */
    class Renderer internal constructor(private val texture:IntArray,private val tw:Int,private val th:Int,
        private val choice:EdgeDefectChoice,settings:EdgeDefectSettings,
        private val width:Int,private val height:Int,private val orientation:Int) {
        private val opacity=settings.intensity.coerceIn(0,100)/100f
        private val placement=NativeEdgePlacement.resolve(settings.seed,when(settings.position) {
            EdgePosition.ALL -> 0;EdgePosition.TOP -> 1;EdgePosition.BOTTOM -> 2
            EdgePosition.LEFT -> 3;EdgePosition.RIGHT -> 4
        })
        // Seed/quarter-turn prefix is recovered. Full affine scaling and its
        // translationRatio are not restored yet; normalized placement stays approximate.
        private val edge=placement.clockwiseTurns
        private val rotation=placement.clockwiseTurns
        private val displayWidth=if(orientation in 5..8)height else width
        private val displayHeight=if(orientation in 5..8)width else height
        private val depth=if(edge==0 || edge==2)
            (displayWidth.toFloat()*th/tw/displayHeight).coerceAtMost(1f) else
            (displayHeight.toFloat()*th/tw/displayWidth).coerceAtMost(1f)

        fun applyRow(pixels:IntArray,y:Int,xOffset:Int=0,count:Int=pixels.size,offset:Int=0) {
            require(offset>=0 && count>=0 && offset+count<=pixels.size)
            require(y in 0 until height && xOffset>=0 && xOffset+count<=width)
            for(i in 0 until count) {
                val nx=(xOffset+i+.5f)/width;val ny=(y+.5f)/height
                // EXIF source -> displayed image, including mirrored orientations.
                val x:Float;val yy:Float
                when(orientation) {
                    2->{x=1-nx;yy=ny};3->{x=1-nx;yy=1-ny};4->{x=nx;yy=1-ny}
                    5->{x=ny;yy=nx};6->{x=1-ny;yy=nx};7->{x=1-ny;yy=1-nx};8->{x=ny;yy=1-nx}
                    else->{x=nx;yy=ny}
                }
                var u:Float;var v:Float
                if(choice.isNotEdge) {
                    when(rotation) {1->{u=yy;v=1-x};2->{u=1-x;v=1-yy};3->{u=1-yy;v=x};else->{u=x;v=yy}}
                } else {
                    when(edge) {0->{u=x;v=yy/depth};1->{u=yy;v=(1-x)/depth};2->{u=1-x;v=(1-yy)/depth};else->{u=1-yy;v=x/depth}}
                    if(v>1f)continue
                }
                pixels[offset+i]=sampleAndBlend(pixels[offset+i],u,v)
            }
        }
        private fun sampleAndBlend(base:Int,u:Float,v:Float):Int {
            val fx=(u.coerceIn(0f,1f)*(tw-1));val fy=(v.coerceIn(0f,1f)*(th-1))
            val x0=floor(fx).toInt();val y0=floor(fy).toInt();val x1=(x0+1).coerceAtMost(tw-1);val y1=(y0+1).coerceAtMost(th-1)
            val dx=fx-x0;val dy=fy-y0
            val p00=texture[y0*tw+x0];val p10=texture[y0*tw+x1];val p01=texture[y1*tw+x0];val p11=texture[y1*tw+x1]
            return NativeEdgeBlend.applyBilinear(base,p00,p10,p01,p11,dx,dy,opacity,choice.grayscale)
        }
    }
}
