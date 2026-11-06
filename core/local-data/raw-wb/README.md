# RAW WB metadata verification

`RawWhiteBalance` 输入 LibRaw-style camera R/G/B multipliers 与参数注入的 calibrated temperature/tint/RGB scale网格，输出有界 temperature/tint估计。它不从像素找白点，也不解析RW2。

```sh
./gradlew --offline :core:verifyRawWhiteBalance
```

`as-shot.json` 为独立 LibRaw metadata + DxO prepared 温度/tint golden；`s1rii-grid.golden` 来自已解码相机资源的 `whiteBalance.tempTint`。它们仅为local verification数据，未进入app assets。研究来源：`/Users/chenyixuan/ReverseWork/DxOFilmPack/evidence/raw-fidelity-20261103/camera/wb-report.md`。

2026-11-03 JVM实测：P1040758 得4199K/tint12.795536（tint差0.000130）；P1176257 得5485K/tint-2.126343（差0.000255）。温度与原版完全一致。另验3个资源节点、通用比例不变性、防御复制和非法输入。

目前只验证S1RII这两实际metadata与有限资源节点；其他相机、不同资源布局、额外metadata规范化尚未验证。原版搜索是粗100K/细1K、对R/G与B/G各自解tint并比较L1误差；没有拟合照片或两样本常数。
