# 修改说明 / Modifications

本目录是 CXO FilmPack（Copyright (c) 2026 Macchen，CXO FilmPack 非商业使用许可）的衍生作品，按该许可要求保留版权声明、注明修改，并以同一许可分发。
This directory is a derivative of CXO FilmPack (Copyright (c) 2026 Macchen) under the CXO FilmPack Non-Commercial
License; the notice is retained, modifications are identified below, and the result is distributed under the same license.

基于上游提交 / Based on upstream commit `4ae863da62ae435ed6fd4c72fde02af9271717c4`。上游 `app/`、`core/` 等目录未作任何修改。

## `shared/src/commonMain/kotlin/dev/cxo/filmpack/core`（`core` 的 iOS 编译副本）

上游 `core/src/main/kotlin/dev/cxo/filmpack/core` 的 65 个文件中，51 个逐字节相同，14 个为了能在 Kotlin/Native 上编译做了机械替换，计算结果不变：

- `DynamicDirectFilmCatalog`、`DynamicFilmCatalog`、`DynamicProviderBwFilmCatalog`、`FixedSrgbEntryLut`、`FixedWideGamutToSrgb`、`NativeGrain8RenderingContext`、`RgbFilmCatalog`、`SharedFilmCatalog`：十六进制摘要由 `"%02x".format` 改为 `toString(16).padStart(2, '0')`
- `FilmLutIO`、`RawNegativeProviderCatalog`、`RawRgbContext`：`java.lang.Short/Long.reverseBytes`、`Float/Double` 位转换改为 Kotlin 等价函数
- `RawRenderingParameters`：`java.lang.Float.MIN_NORMAL` 改为其数值 `1.17549435E-38f`
- `NativeGrain8Cubic`：`BigDecimal` 精确乘加改为 Dekker TwoProduct 误差无损算法（同样只做一次 Double 舍入）
- `NativeGrain8Resources`：颗粒包读取不再依赖 `java.util.zip` / `java.io.File`，改用平台文件读取与内置解压

新增：`ByteSource.kt`（平台文件读取）、`CompatJvm.kt`（`Integer`、`Math`、`Charset` 等 JVM API 的最小替代），以及 `shared/src/commonMain/kotlin/java/**`（`java.io`、`java.security.MessageDigest`、`java.util` 的最小替代），`shared/src/iosMain/.../core/PlatformIos.kt`。

## `shared/src/commonMain/kotlin/dev/cxo/filmpack/engine`（新增）

- `FilmEngine.kt`、`EdgeDefects.kt`、`PhotoGrainLayer.kt`、`PhotoGrainLoader.kt`、`Models.kt`、`MiniJson.kt`：安卓 `PhotoProcessor` 等类中与平台无关部分的移植（胶片变换、颗粒、输出 CMM、后期调整、边缘效果）
- `RawEngine.kt`：安卓 `RawRenderPipeline`、`RawCameraProfiles`、`RawCameraBwCatalog` 的移植，去掉 Android 依赖
- `iosMain/.../engine/IosBridge.kt`：Swift 调用入口

## `native/`（新增）

`cxo_raw.h/.cpp`：LibRaw 的 C 接口。解码参数与上游 `raw_bridge.cpp` 的 `decodeCamera` 相同；区别：由 LibRaw 直接按 EXIF 转成显示方向；LibRaw 对象分配在堆上（iOS 后台线程栈只有 512KB）；输出按行分块写入缓存以降低内存；像素上限由 6000 万提高到 6400 万（以支持 Sony A7R IV / V 等 6100 万像素机身）。

## `App/`（新增）

SwiftUI 界面与导出功能，全部为新写代码。胶片边框与索引图的片基、齿孔、边字比例参照 film-index-generator（MIT），见 `THIRD_PARTY_NOTICES.md`。
