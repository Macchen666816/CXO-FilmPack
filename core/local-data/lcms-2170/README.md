This directory contains a profile-pair-specific LittleCMS 2.17 compiled RGB16 transform, evaluated at runtime in pure Kotlin by LcmsOptimizedQ15Transform.

adobe-to-srgb.lcmsbin is generated offline from the two embedded ICC profiles using official unmodified LCMS 2.17 source (intent 0, flags 0x40). It contains one 33³ CLUT. Native DxO observations are verification-only and never enter this resource. Runtime has no native library dependency.

validation.golden contains the separately stored 5516 DxO Color observations plus 201473 independent LCMS RGB16 verification samples. This file is a verification oracle and must not be loaded as transform data.

Reproduce from /Users/chenyixuan/ReverseWork/DxOFilmPack:
  python3 scripts/verify_kotlin_lcms.py

Detailed provenance, source archive/license, generation code, hashes and results:
  /Users/chenyixuan/ReverseWork/DxOFilmPack/evidence/lcms-kotlin-20261101/REPORT.md
  /Users/chenyixuan/ReverseWork/DxOFilmPack/evidence/lcms-kotlin-20261101/verification.json

The Kotlin evaluator and resources have been verified on JVM17. Android execution and arbitrary ICC transform compilation are not established by this check.


The second resource, `adobe-to-source-srgb.lcmsbin`, targets the retained source TIFF embedded ICC (046968ab6359293e109e90316890a3394161911a255d41da5f1629a792ba10f7), not the built-in sRGB profile. Source generation and independent JVM/native Color verification: research `evidence/wb-target-20261101/lcms-source-pair/`. `source-validation.golden` is validation-only. Its Android test is added separately; execution requires a fresh device run.
