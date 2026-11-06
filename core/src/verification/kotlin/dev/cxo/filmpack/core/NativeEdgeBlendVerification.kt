package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

fun main(args:Array<String>) {
    DataInputStream(File(args[0]).inputStream().buffered()).use {input ->
        val magic=ByteArray(8);input.readFully(magic);check(String(magic)=="CXOEDGE1")
        val count=input.readInt();var checked=0
        repeat(count) {
            val base=input.readInt();val texture=input.readInt();val opacity=input.readFloat()
            val gray=input.readInt()!=0;val expected=input.readInt()
            val actual=NativeEdgeBlend.applyArgb(base,texture,opacity,gray)
            check(actual==expected) {"Native edge blend differs at $it: ${actual.toUInt().toString(16)} / ${expected.toUInt().toString(16)}"}
            checked++
        }
        check(input.read()==-1)
        println("Original mode-6 oracle: $checked pixels / ${checked*3} components, all exact")
    }
    DataInputStream(File(args[1]).inputStream().buffered()).use {input ->
        val magic=ByteArray(8);input.readFully(magic);check(String(magic)=="CXOEBIL1")
        val count=input.readInt()
        repeat(count) {
            val base=input.readInt();val a=input.readInt();val b=input.readInt();val c=input.readInt();val d=input.readInt()
            val dx=input.readFloat();val dy=input.readFloat();val opacity=input.readFloat()
            val gray=input.readInt()!=0;val expected=input.readInt()
            val actual=NativeEdgeBlend.applyBilinear(base,a,b,c,d,dx,dy,opacity,gray)
            check(actual==expected) {"Native bilinear differs at $it: ${actual.toUInt().toString(16)} / ${expected.toUInt().toString(16)}"}
        }
        check(input.read()==-1)
        println("Original bilinear oracle: $count pixels / ${count*3} components, all exact")
    }
}
