# Adobe DNG SDK for Apple ProRAW

Adobe DNG SDK 1.7.1 (2768), released 2026-10-13.
Source: https://download.adobe.com/pub/adobe/dng/dng_sdk_1_7_1_2768_20261013.zip
Original ZIP SHA-256: c1d3a545ed45b150e59af2e6aa0695c1551f0e2bed34ad87436898a05194d2b1

Imported from the verified local SDK experiment under
`ReverseWork/DxOFilmPack/evidence/oppo-dng-20261105/sdk-experiment/`.
`source/` contains the official SDK C++ headers and sources, excluding CLI validation,
XMP linkage, metadata update and JPEG XL implementation. Pixel processing sources
are unmodified. `cxo_jxl_unsupported.cpp` replaces JPEG XL entry points with explicit
unsupported errors; `include/jxl/color_encoding.h` retains the upstream JPEG XL
public type definitions required by SDK headers (BSD license included).
No JPEG XL library or codec is embedded. XMP, baseline/lossy JPEG and VC5 disabled;
SDK's own lossless JPEG decoder remains enabled. SDK thread-safe globals use POSIX
mutexes; the default host runs area tasks on the calling worker thread.

`apple_proraw_bridge.cpp` admits Apple three-channel Linear DNG only, decodes
SDK Stage3 and applies the recovered embedded-profile matrix/exposure front end
to full-resolution linear ProPhoto Q15 rows. The application then applies the
dedicated DNG Original stage or RAW film pipeline and output color management.
Source orientation is retained as metadata. It does not borrow another camera's
calibration or decode an embedded JPEG preview. JPEG XL ProRAW is rejected.
The standard LibRaw camera/Bayer route is unchanged.

Adobe license and the JPEG XL type-header license are retained here and in
`THIRD_PARTY_NOTICES`, which the Android asset staging task packages automatically.
