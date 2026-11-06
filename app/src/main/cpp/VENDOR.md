libjpeg-turbo 3.2.1 attribution and source pin

This software is based in part on the work of the Independent JPEG Group.
CXO FilmPack statically links the ARM64 libjpeg API with NEON SIMD support.
The pinned upstream source is unmodified; project JNI glue and CMake integration
are outside the upstream vendor directory.

Source: https://github.com/libjpeg-turbo/libjpeg-turbo
Version: 3.2.1
Commit: 7b4993786d856cbccabfc0ed2f923ba000e326cc
Archive SHA256: c27ad72a68da151177929aa969ae773a2da5ac2af9ce946079314fdac8cece7f

The complete upstream LICENSE.md and README.ijg follow.

Builds use only the local source. The Android integration selects jpeg-static,
8-bit baseline JPEG, 4:4:4, quality 98, integer slow DCT, standard Huffman tables.
Only ARM64 enables this optional backend; other ABIs keep the Kotlin encoder.
License texts are staged into APK assets/licenses/libjpeg-turbo.txt.
