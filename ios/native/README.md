# Camera RAW native decoder

LibRaw 0.22.3 is not copied here: build-libraw.command compiles the same, unmodified
tree the Android build uses, app/src/main/cpp/vendor/libraw. License selection: CDDL 1.0.
See that directory's LICENSE.CDDL and COPYRIGHT, and THIRD_PARTY_NOTICES/LibRaw-*.

Run ../build-libraw.command (from ios/) to compile a static, optimised CxoRaw.xcframework.
Xcode links that artifact, never the vendored C++ sources. Optional GPL packs,
RawSpeed and DNG SDK integration are disabled. Apple ProRAW and linear DNG are
handled exclusively by Core Image in Swift.

The C interface is synchronous; invoke it only from the existing serial
RenderQueue. Cache payloads are RGB16LE in display orientation, without headers.
Full-resolution Swift export reads bounded row bands from the cache; previews
use area means. Scene intent always samples the full cache through Kotlin.

Camera cache output uses a <=1 MiB RGB16 band and synchronous file writes.
The bridge mirrors LibRaw 0.22.3 copy_mem_image's fixed camera-route gamma
lookup and flip mapping; do not generalize it to other output parameters.
There is no supported API to release only rawdata.raw_alloc before demosaic,
so the raw allocation and imgdata.image lifetime remain under LibRaw ownership.

Memory diagnostics: run `CONFIGURATION=Debug ./build-libraw.command`, then
`./build-ios.command`. The native library stays -O3, but DEBUG enables
TASK_VM_INFO logging at entry, LibRaw progress callbacks, unpack/develop,
output bands and exit. It records phys_footprint, sampled peak, process-lifetime
footprint peak, resident size and resident peak without a sampling thread.
The lifetime peaks include earlier work in a long-lived app; compare fresh
processes for before/after measurements. macOS file-backed shared mmap pages
can increase resident memory without increasing phys_footprint.
For distribution, rebuild with `CONFIGURATION=Release ./build-libraw.command`
(the default); the diagnostics and progress handler are compiled out. Xcode's
configuration alone does not rebuild this separately compiled static library.
