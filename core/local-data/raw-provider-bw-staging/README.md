# S1RII provider BW contexts

Four native parameter contexts for Kodak HIE Filtre and Rollei IR400, at the
P1040758 and P1176257 warm/daylight anchors. CXORBAKE1 assets include pre/post/
extra matrices, sensor and rendering EV, film curve, inverse and verification
bake grids. The product rebuilds BW grids from parameters, with skipSaturationClamp
repair and runtime BW; it does not use JPEG provider assets. Assets are staged as
raw/s1rii-{hiefiltre,rolleiir400}[-landscape].cxorbake by app/build.gradle.kts.

The Python staging comparison uses independently rebuilt/native grids with the
same reference renderer. verifyRawProviderBw additionally exercises Kotlin product
forward/inverse construction and uses true native full Q15 outputs where available
(HIE Filtre warm and Rollei daylight). This is bounded WB-context evidence.
