# RAW HSV catalog

`catalog.json` and `curves/*.bin` are product resources generated from the
recovered `data/v2/films.json` records. Each curve is the original 32768-entry
signed-int16 Q15 carrier; the runtime adds one guard sample by copying the
last code. `matrixB` is stored row-major. Shared entries use `curve`; the 18
CXOHSV03 entries use `curveMode: "per_channel"` and a three-item `curves`
array in R/G/B order.

Current routing is explicit: 148 parameter records are retained, but only
80 `controlledRaw=true` entries enter the S1RII camera-linear path (72 fixed
shared, 3 per-channel, 5 dynamic-direct). The other 68 records have initializer
EV adjustments and use the established sRGB RAW bridge until the provider's
extra EV/matrix rules are recovered. A parameter record is not proof of a
closed camera-linear route. S9 also keeps its existing fallback.

`runtimeSafe` is a bounded numerical property, separate from routing/parity.
The latest scan with corrected fixed pre and explicit BW rules covers 75 fixed
entries over three captured Elite S1RII WB anchors: 74 have nonnegative repaired
S/V; Portra400 retains finite signed saturation and uses the explicit experimental
renderer mode. The five dynamic-direct contexts retain their experimental mode.
Older 89/36 and 119/6 safety counts used earlier parameter/routing rules and are
historical. Grain is applied after the RAW renderer and before output CMM.

## 2026-11-03 dynamic-direct RAW 增量

`catalog.json` 参数记录现为 148 条（当前实际受控路由80条，见上文）：在原有 125 条 shared HSV 与 18 条 R/G/B 曲线条目之外，新增 `ColorModeArgentiqueAstia` 以及 Provia、Velvia、Ekta100VS、Kod64 四条 dynamic-direct 记录。这些条目来自两张 S1RII RW2 的原版受控捕获，使用各自共享 film curve 与 post matrix，标记 `experimental_dynamic_s1rii` 和 `runtimeSafe=false`。它们只放开 S1RII 的 camera-linear RAW 路由；S9、其它机身、任意 EV 和整图 parity 仍不由此推断。每个已捕获 WB 锚点保留独立 `CXORBAKE1` fixture，因为 pre 矩阵与 prepared grid 随上下文变化。四款的有效 curve/post 直接来自 RAW RenderingData，与 JPEG dynamic 资源不同；不可套用 JPEG 的 film_B/curve。

## RAW BW semantics (2026-11-03)

Every catalog entry now explicitly records `typeBw` and `runtimeBw`, copied
from its existing verified CXOHSV02/03 header by `tools/sync_raw_bw_flags.py`.
There are 58 BW and 90 color records. Keep the two flags separate: `typeBw`
controls the baker's gray-node defaults and repair's saturation-clamp bypass;
`runtimeBw` controls native weighted gray after HSV reconstruction/clamp and
before the RAW postcurve/Naka encoding. The native Tri-X S1RII context independently
confirms RenderingData[0]=1 and runtime interface+0x1659=1.

`verifyRawBw` validates all 148 catalog flags against the source headers and
three native runtime/full-chain 17³ probes (Tri-X, HIE Filtre, Rollei IR400).
Those probes validate the runtime operation, not general RAW provider construction.
Fixed providers use `preTransform = film.matrixA * inverse(Elite.matrixA)`
followed by the selected Elite camera pre; the five dynamic providers already
have their own pre and use an identity transform. Native Tri-X pre agrees at
the captured daylight anchor, and its complete product catalog/pre/bake/runtime
regression now differs by at most 1 Q15 code over 4913 native samples.
Initializer EV adjustments and per-channel post construction have additional
provider rules and must retain their documented validation boundaries.

Runtime rendering uses the forward RAW postcurve; the composed inverse is used
only inside grid baking. `verifyRawFixedNative` compares forward curves to native
carrier +6144 at three Elite and ten dynamic-direct contexts. Run
`sync_raw_bw_flags.py` after rebuilding the RAW catalog. Bounded repair scans
emit safety maps; `tools/update_raw_runtime_safety.py` applies them only after
both Kotlin scans have been rerun with the final parameter rules.
