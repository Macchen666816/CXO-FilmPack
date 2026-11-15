import Foundation
import CoreImage
import ImageIO
import UIKit
import UniformTypeIdentifiers
import CxoShared
import CxoRaw

/// Tightly packed RGBA, 8 bit (A ignored/opaque) or 16 bit little-endian, in sRGB.
struct PixelBuffer: @unchecked Sendable {
    let width: Int
    let height: Int
    let data: Data
    let is16: Bool
    var cameraRaw: CameraRawSource? = nil
    var cacheBacked = false

    func bandData(top: Int, rows: Int) throws -> Data {
        guard cacheBacked, let raw = cameraRaw else {
            return data.subdata(in: (top * width * bytesPerPixel)..<((top + rows) * width * bytesPerPixel))
        }
        let file = try FileHandle(forReadingFrom: raw.cacheURL)
        defer { try? file.close() }
        try file.seek(toOffset: UInt64(top * width * 6))
        let count = rows * width
        guard let rgb = try file.read(upToCount: count * 6), rgb.count == count * 6 else {
            throw AppError.message("RAW 缓存读取失败")
        }
        var rgba = Data(count: count * 8)
        rgba.withUnsafeMutableBytes { (dst: UnsafeMutableRawBufferPointer) in
            rgb.withUnsafeBytes { (src: UnsafeRawBufferPointer) in
                for i in 0..<count {
                    for c in 0..<6 { dst[i * 8 + c] = src[i * 6 + c] }
                    dst[i * 8 + 6] = 255; dst[i * 8 + 7] = 255
                }
            }
        }
        return rgba
    }

    var bytesPerPixel: Int { is16 ? 8 : 4 }

    func cgImage() -> CGImage? {
        guard cameraRaw == nil else { return nil }
        guard let provider = CGDataProvider(data: data as CFData),
              let cs = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        if is16 {
            let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue | CGBitmapInfo.byteOrder16Little.rawValue)
            return CGImage(width: width, height: height, bitsPerComponent: 16, bitsPerPixel: 64, bytesPerRow: width * 8,
                           space: cs, bitmapInfo: info, provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
        }
        let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue)
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                       space: cs, bitmapInfo: info, provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    func uiImage() -> UIImage? { cgImage().map { UIImage(cgImage: $0) } }

    /// 8-bit copy whose longest edge is at most `maxEdge`.
    func downscaled(maxEdge: Int) -> PixelBuffer? {
        guard let cg = cgImage(), let cs = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        let scale = min(1.0, Double(maxEdge) / Double(max(width, height)))
        let w = max(1, Int((Double(width) * scale).rounded())), h = max(1, Int((Double(height) * scale).rounded()))
        var out = Data(count: w * h * 4)
        let ok: Bool = out.withUnsafeMutableBytes { p in
            guard let ctx = CGContext(data: p.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: cs,
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            ctx.interpolationQuality = .high
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return ok ? PixelBuffer(width: w, height: h, data: out, is16: false) : nil
    }
}

/// Shared ownership keeps the cache alive until the last queued band has finished.
final class CameraRawSource: @unchecked Sendable {
    let cacheURL: URL
    let width: Int, height: Int
    let model: String, make: String
    let rawBps: Int32, maximumAfterProcess: Int32
    let camMul: [Float]

    init(cacheURL: URL, info: cxo_raw_info) {
        self.cacheURL = cacheURL
        width = Int(info.width); height = Int(info.height)
        var modelBytes = info.model, makeBytes = info.make
        model = withUnsafeBytes(of: &modelBytes) { String(cString: $0.baseAddress!.assumingMemoryBound(to: CChar.self)) }
        make = withUnsafeBytes(of: &makeBytes) { String(cString: $0.baseAddress!.assumingMemoryBound(to: CChar.self)) }
        rawBps = info.raw_bps; maximumAfterProcess = Int32(info.maximum_after_process)
        camMul = [info.cam_mul.0, info.cam_mul.1, info.cam_mul.2, info.cam_mul.3]
    }

    deinit { try? FileManager.default.removeItem(at: cacheURL) }
}

/// An opened photograph, kept on disk (memory-mapped on demand) so many photos can be open at once.
/// Camera RAW uses LibRaw + body calibration; ProRAW/linear DNG always use Core Image.
/// Mutable decode state is accessed exclusively on RenderQueue.
final class SourceImage: @unchecked Sendable {
    let url: URL
    let isRaw: Bool
    let properties: [String: Any]
    let pixelSize: (width: Int, height: Int)
    private var cameraCache: CameraRawSource?
    private var appleOnly = false
    private var rawBuffers: [Int: PixelBuffer] = [:]



    init(url: URL) throws {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil), CGImageSourceGetCount(src) > 0 else {
            throw AppError.message("无法读取这张照片")
        }
        let props = (CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [String: Any]) ?? [:]
        var w = props[kCGImagePropertyPixelWidth as String] as? Int ?? 0
        var h = props[kCGImagePropertyPixelHeight as String] as? Int ?? 0
        let orientation = props[kCGImagePropertyOrientation as String] as? Int ?? 1
        if orientation >= 5 { swap(&w, &h) }
        guard w > 0, h > 0 else { throw AppError.message("无法读取这张照片的尺寸") }
        var raw = false
        if let type = CGImageSourceGetType(src) as String?, let ut = UTType(type) { raw = ut.conforms(to: .rawImage) }
        self.url = url
        self.properties = props
        self.pixelSize = (w, h)
        self.isRaw = raw
    }

    /// Decode only on the single render worker. Full-resolution RAW remains file-backed.
    func makeBuffer(maxEdge: Int, runtime: FilmRuntime, originalRaw: Bool) throws -> PixelBuffer {
        let longest = max(pixelSize.width, pixelSize.height)
        let edge = max(16, min(maxEdge, longest))
        if isRaw, originalRaw, !appleOnly {
            if cameraCache == nil {
                let tiff = properties[kCGImagePropertyTIFFDictionary as String] as? [String: Any]
                let make = tiff?[kCGImagePropertyTIFFMake as String] as? String ?? ""
                // Apple ProRAW stays on Apple's decoder regardless of the camera-RAW preference.
                let probe = make.lowercased().contains("apple") ? 2 : cxo_raw_probe(url.path)
                if probe == 1 {
                    var info = cxo_raw_info()
                    var error = [CChar](repeating: 0, count: 512)
                    let cache = FileManager.default.temporaryDirectory.appendingPathComponent("cxo-raw-\(UUID().uuidString).rgb16")
                    let status = cxo_raw_decode_camera(url.path, cache.path, &info, &error, Int32(error.count))
                    let raw = CameraRawSource(cacheURL: cache, info: info)
                    do {
                        let supported = try IosBridgeKt.rawSupportsCamera(engine: runtime.engine, model: raw.model, make: raw.make)
                        if supported || raw.model.isEmpty {
                            guard status == 0 else { throw AppError.message(String(cString: error)) }
                            cameraCache = raw
                        } else {
                            try? FileManager.default.removeItem(at: cache)
                            appleOnly = true
                        }
                    } catch {
                        try? FileManager.default.removeItem(at: cache)
                        throw error
                    }
                } else { appleOnly = true } // includes linear DNG; never demosaic it with LibRaw
            }
            if let raw = cameraCache {
                let fullEdge = max(raw.width, raw.height)
                if maxEdge >= fullEdge {
                    return PixelBuffer(width: raw.width, height: raw.height, data: Data(), is16: true,
                                       cameraRaw: raw, cacheBacked: true)
                }
                if let cached = rawBuffers[maxEdge] { return cached }
                var w: Int32 = 0, h: Int32 = 0
                guard cxo_raw_load_scaled(raw.cacheURL.path, Int32(raw.width), Int32(raw.height), Int32(maxEdge), nil, &w, &h) == 0 else {
                    throw AppError.message("RAW 缩放失败")
                }
                var data = Data(count: Int(w) * Int(h) * 8)
                let status = data.withUnsafeMutableBytes { bytes in
                    cxo_raw_load_scaled(raw.cacheURL.path, Int32(raw.width), Int32(raw.height), Int32(maxEdge),
                                        bytes.baseAddress!.assumingMemoryBound(to: UInt16.self), &w, &h)
                }
                guard status == 0 else { throw AppError.message("RAW 缓存读取失败") }
                let buffer = PixelBuffer(width: Int(w), height: Int(h), data: data, is16: true, cameraRaw: raw)
                if maxEdge == 180 || maxEdge == 2000 { rawBuffers[maxEdge] = buffer }
                return buffer
            }
        }
        if isRaw, let buffer = rawBuffer(edge: edge, longest: longest) { return buffer }
        return try bitmapBuffer(edge: edge)
    }

    private func rawBuffer(edge: Int, longest: Int) -> PixelBuffer? {
        guard let filter = CIRAWFilter(imageURL: url) else { return nil }
        if edge < longest { filter.scaleFactor = Float(edge) / Float(longest) }
        filter.extendedDynamicRangeAmount = 0
        guard let image = filter.outputImage, let cs = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        let extent = image.extent.integral
        let w = Int(extent.width), h = Int(extent.height)
        guard w > 0, h > 0, w < 1 << 15, h < 1 << 15 else { return nil }
        var out = Data(count: w * h * 8)
        let context = CIContext(options: [.cacheIntermediates: false])
        out.withUnsafeMutableBytes { p in
            context.render(image, toBitmap: p.baseAddress!, rowBytes: w * 8, bounds: extent, format: .RGBA16, colorSpace: cs)
        }
        return PixelBuffer(width: w, height: h, data: out, is16: true)
    }

    private func bitmapBuffer(edge: Int) throws -> PixelBuffer {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { throw AppError.message("无法读取这张照片") }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: edge,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary),
              let cs = CGColorSpace(name: CGColorSpace.sRGB) else { throw AppError.message("解码失败") }
        let w = cg.width, h = cg.height
        var out = Data(count: w * h * 4)
        let ok: Bool = out.withUnsafeMutableBytes { p in
            guard let ctx = CGContext(data: p.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: cs,
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            let rect = CGRect(x: 0, y: 0, width: w, height: h)
            ctx.setFillColor(red: 1, green: 1, blue: 1, alpha: 1)   // transparency is composited over white, as in FilmPack
            ctx.fill(rect)
            ctx.interpolationQuality = .high
            ctx.draw(cg, in: rect)
            return true
        }
        guard ok else { throw AppError.message("解码失败") }
        return PixelBuffer(width: w, height: h, data: out, is16: false)
    }
}

enum TextureLoader {
    /// Bundled PNG → straight-alpha RGBA, long edge ≤ 1024, as the film engine expects.
    static func load(path: String, root: String) -> Texture {
        let url = URL(fileURLWithPath: root).appendingPathComponent(path)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 1024,
        ]
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary),
              let cs = CGColorSpace(name: CGColorSpace.sRGB) else { return blank() }
        let w = cg.width, h = cg.height
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        let ok: Bool = pixels.withUnsafeMutableBytes { p in
            guard let ctx = CGContext(data: p.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: cs,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard ok else { return blank() }
        var i = 0
        while i < pixels.count {
            let a = Int(pixels[i + 3])
            if a > 0 && a < 255 {
                for k in 0..<3 { pixels[i + k] = UInt8(min(255, (Int(pixels[i + k]) * 255 + a / 2) / a)) }
            }
            i += 4
        }
        return (try? IosBridgeKt.makeTexture(rgba: Data(pixels), width: Int32(w), height: Int32(h))) ?? blank()
    }

    private static func blank() -> Texture {
        // 1x1 fully transparent; cannot fail for valid sizes.
        return try! IosBridgeKt.makeTexture(rgba: Data([0, 0, 0, 0]), width: 1, height: 1)
    }
}
