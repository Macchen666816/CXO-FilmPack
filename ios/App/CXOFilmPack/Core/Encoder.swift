import Foundation
import ImageIO
import UniformTypeIdentifiers
import Photos
import CoreText
import UIKit

enum Encoder {
    static func encode(_ buffer: PixelBuffer, format: OutputFormat, quality: Int, properties: [String: Any]) throws -> Data {
        guard let image = buffer.cgImage() else { throw AppError.message("无法生成图片") }
        let type = (format == .jpeg ? UTType.jpeg : UTType.tiff).identifier
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, type as CFString, 1, nil) else {
            throw AppError.message("无法创建输出文件")
        }
        var props = metadata(from: properties)
        if format == .jpeg { props[kCGImageDestinationLossyCompressionQuality as String] = Double(quality) / 100.0 }
        CGImageDestinationAddImage(destination, image, props as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw AppError.message("编码失败") }
        return data as Data
    }

    /// Keeps EXIF/GPS/TIFF capture metadata; pixels are already upright, so orientation is reset.
    private static func metadata(from source: [String: Any]) -> [String: Any] {
        var out: [String: Any] = [kCGImagePropertyOrientation as String: 1]
        if var exif = source[kCGImagePropertyExifDictionary as String] as? [String: Any] {
            exif.removeValue(forKey: kCGImagePropertyExifPixelXDimension as String)
            exif.removeValue(forKey: kCGImagePropertyExifPixelYDimension as String)
            out[kCGImagePropertyExifDictionary as String] = exif
        }
        if var tiff = source[kCGImagePropertyTIFFDictionary as String] as? [String: Any] {
            tiff.removeValue(forKey: kCGImagePropertyTIFFOrientation as String)
            out[kCGImagePropertyTIFFDictionary as String] = tiff
        }
        if let gps = source[kCGImagePropertyGPSDictionary as String] { out[kCGImagePropertyGPSDictionary as String] = gps }
        return out
    }

    static func saveToPhotos(_ urls: [URL]) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw AppError.message("没有相册写入权限") }
        try await PHPhotoLibrary.shared().performChanges {
            for url in urls {
                let request = PHAssetCreationRequest.forAsset()
                request.addResource(with: .photo, fileURL: url, options: nil)
            }
        }
    }

    /// Single photo inside a film-strip frame (135 with sprocket holes, or a 120 rebate). The strip keeps the
    /// photo's own proportions; portrait photos get a vertical strip.
    static func framed(_ src: PixelBuffer, is120: Bool, category: String, title: String, params: String = "", frame: Int,
                       orientation: StripOrientation = .auto) -> PixelBuffer {
        guard let image = src.cgImage() else { return src }
        let w = Double(src.width), h = Double(src.height)
        let portrait = w < h
        let landscape = orientation == .horizontal || (orientation == .auto && !portrait)   // strip runs horizontally
        let spec = FilmStripArt.Spec(is120: is120, slotW: max(w, h), slotH: min(w, h))
        let pad = (spec.slotW * 0.08).rounded()
        let lw = spec.stripWidth(frames: 1) + 2 * pad, lh = spec.stripH + 2 * pad
        let fw = Int((landscape ? lw : lh).rounded()), fh = Int((landscape ? lh : lw).rounded())
        guard let pixels = FilmStripArt.render(width: fw, height: fh, { ctx in
            ctx.setFillColor(FilmStripArt.paper)
            ctx.fill(CGRect(x: 0, y: 0, width: Double(fw), height: Double(fh)))
            if !landscape { ctx.concatenate(CGAffineTransform(a: 0, b: -1, c: 1, d: 0, tx: 0, ty: lw)) }
            FilmStripArt.drawStrip(ctx, spec: spec, x: pad, y: pad, process: .init(category: category), rowIndex: 0, centeredHoles: true,
                                frames: [.init(image: image, name: title, params: params, number: frame, rotate: portrait)])
        }) else { return src }
        return PixelBuffer(width: fw, height: fh, data: pixels, is16: false)
    }

    /// Lays the frames out as film strips (`columns` per strip) on paper, like a contact sheet. Portrait photos
    /// are turned sideways into the landscape window, as on a real contact sheet.
    static func contactSheet(_ frames: [(buffer: PixelBuffer, name: String, category: String, params: String)], is135: Bool,
                             columns: Int, cellEdge: Double) -> PixelBuffer? {
        guard !frames.isEmpty else { return nil }
        let cols = max(1, min(columns, frames.count))
        let rows = (frames.count + cols - 1) / cols
        let spec = FilmStripArt.Spec(is120: !is135, slotW: cellEdge, slotH: is135 ? (cellEdge / 1.5).rounded() : cellEdge)
        let margin = (cellEdge * 0.12).rounded(), rowGap = (cellEdge * 0.12).rounded()
        let sheetW = spec.stripWidth(frames: cols) + 2 * margin
        let sheetH = Double(rows) * spec.stripH + Double(rows - 1) * rowGap + 2 * margin
        let fw = Int(sheetW.rounded()), fh = Int(sheetH.rounded())
        guard let pixels = FilmStripArt.render(width: fw, height: fh, { ctx in
            ctx.setFillColor(FilmStripArt.paper)
            ctx.fill(CGRect(x: 0, y: 0, width: Double(fw), height: Double(fh)))
            for r in 0..<rows {
                let first = r * cols
                let row = frames[first..<min(first + cols, frames.count)]
                let items = row.enumerated().compactMap { (k, f) -> FilmStripArt.Frame? in
                    guard let image = f.buffer.cgImage() else { return nil }
                    return .init(image: image, name: f.name, params: f.params, number: first + k + 1, rotate: f.buffer.height > f.buffer.width)
                }
                FilmStripArt.drawStrip(ctx, spec: spec, x: margin, y: margin + Double(r) * (spec.stripH + rowGap),
                                    process: .init(category: row.first?.category ?? ""), rowIndex: r, centeredHoles: false, frames: items)
            }
        }) else { return nil }
        return PixelBuffer(width: fw, height: fh, data: pixels, is16: false)
    }
}

/// Film-strip artwork. The geometry, sprocket and edge-print proportions follow film-index-generator
/// (MIT, Copyright (c) 2026 Judian99; fork by ohsobrkn), re-implemented here with Core Graphics.
enum FilmStripArt {
    static let paper = CGColor(srgbRed: 0.94, green: 0.91, blue: 0.83, alpha: 1)
    private static let holeFill = CGColor(srgbRed: 0.957, green: 0.929, blue: 0.875, alpha: 1)   // #f4eddf

    struct Frame {
        let image: CGImage
        let name: String
        let params: String          // capture data, printed edge-style ("ISO 400  F/2.8  1/250")
        let number: Int
        let rotate: Bool            // portrait photo turned 90 degrees clockwise into a landscape window
    }

    /// Edge-print ink by development process (C-41 orange, B&W white, E-6 warm skin tone, ECN-2 cream).
    struct Process {
        var ink: CGColor, glow: CGColor
        let numberSuffix: Bool
        let presets135: [String], presets120: [String]
        init(category: String) {
            switch category {
            case "黑白胶片":
                ink = CGColor(srgbRed: 0.933, green: 0.933, blue: 0.91, alpha: 0.92); glow = CGColor(srgbRed: 0.94, green: 0.94, blue: 0.92, alpha: 0.35)
                numberSuffix = true
                presets135 = ["135-36", "SAFETY FILM", "DX 5063", "PANCHROMATIC", "135"]; presets120 = ["120", "SAFETY FILM", "PANCHROMATIC"]
            case "正片", "直出正片":
                ink = CGColor(srgbRed: 0.976, green: 0.765, blue: 0.58, alpha: 0.92); glow = CGColor(srgbRed: 0.976, green: 0.765, blue: 0.58, alpha: 0.4)
                numberSuffix = false
                presets135 = ["135-36", "E-6", "SAFETY FILM", "135"]; presets120 = ["120", "E-6"]
            case "电影胶片":
                ink = CGColor(srgbRed: 0.98, green: 0.90, blue: 0.745, alpha: 0.92); glow = CGColor(srgbRed: 0.98, green: 0.90, blue: 0.745, alpha: 0.35)
                numberSuffix = false
                presets135 = ["EASTMAN", "KEEP FILM 5219", "ECN-2", "SAFETY FILM"]; presets120 = ["EASTMAN", "ECN-2", "SAFETY FILM"]
            default:
                ink = CGColor(srgbRed: 1, green: 0.69, blue: 0.25, alpha: 0.92); glow = CGColor(srgbRed: 1, green: 0.667, blue: 0.235, alpha: 0.45)
                numberSuffix = true
                presets135 = ["135-36", "C-41", "DX 5063", "SAFETY FILM", "135"]; presets120 = ["120", "C-41", "SAFETY FILM"]
            }
        }
    }

    struct Spec {
        let is120: Bool
        let slotW: Double, slotH: Double
        let gap: Double, bandH: Double, sprocketH: Double, textH: Double, shift: Double
        let pitch: Double, holeW: Double, padX: Double

        init(is120: Bool, slotW: Double, slotH: Double) {
            self.is120 = is120; self.slotW = slotW; self.slotH = slotH
            if is120 {
                textH = (slotH * 0.044).rounded(); sprocketH = 0; shift = 0
                bandH = max(textH, (slotH * 0.02).rounded())
                padX = (slotH * 0.05).rounded(); gap = (slotH * 0.085).rounded()
                pitch = slotH * (4.75 / 56); holeW = slotH * (2.8 / 56)
            } else {
                let mm = slotW / 36
                textH = (slotW * 0.068).rounded(); sprocketH = (slotW * 0.1).rounded()
                shift = min((slotW * 0.022).rounded(), textH)
                bandH = max(sprocketH + textH - shift, (mm * (35 - 24) / 2).rounded())
                gap = max(0, mm * 38 - slotW); padX = gap / 2
                pitch = mm * 4.75; holeW = (slotW * 0.058).rounded()
            }
        }
        var stripH: Double { slotH + 2 * bandH }
        func stripWidth(frames n: Int) -> Double { Double(n) * slotW + Double(max(0, n - 1)) * gap + 2 * padX }
        var markPitch: Double { slotW + gap }
    }

    /// Runs `body` in a top-down (y grows downwards) 8-bit sRGB context and returns its pixels.
    static func render(width: Int, height: Int, _ body: (CGContext) -> Void) -> Data? {
        guard width > 0, height > 0, let cs = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        var data = Data(count: width * height * 4)
        let ok: Bool = data.withUnsafeMutableBytes { p in
            guard let ctx = CGContext(data: p.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            ctx.interpolationQuality = .high
            ctx.translateBy(x: 0, y: CGFloat(height)); ctx.scaleBy(x: 1, y: -1)     // top-down from here on
            body(ctx)
            return true
        }
        return ok ? data : nil
    }

    private static func path(_ x: Double, _ y: Double, _ w: Double, _ h: Double, _ r: Double) -> CGPath {
        CGPath(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerWidth: min(r, w / 2, h / 2), cornerHeight: min(r, w / 2, h / 2), transform: nil)
    }

    private static func gradient(_ stops: [(Double, CGColor)]) -> CGGradient? {
        CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: stops.map { $0.1 } as CFArray, locations: stops.map { CGFloat($0.0) })
    }

    // MARK: strip

    static func drawStrip(_ ctx: CGContext, spec: Spec, x: Double, y: Double, process: Process, rowIndex: Int,
                          centeredHoles: Bool, frames: [Frame]) {
        let stripW = spec.stripWidth(frames: frames.count), stripH = spec.stripH
        let radius = spec.is120 ? max(2, (spec.slotW * 0.004).rounded()) : max(6, (spec.slotW * 0.015).rounded())
        let strip = path(x, y, stripW, stripH, radius)

        // Drop shadow, then the dark base with a faint diagonal sheen.
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: -(spec.slotW * 0.018).rounded()), blur: (spec.slotW * 0.05).rounded(),
                      color: CGColor(srgbRed: 0.1, green: 0.08, blue: 0.05, alpha: 0.35))
        ctx.addPath(strip); ctx.setFillColor(CGColor(srgbRed: 0.075, green: 0.067, blue: 0.063, alpha: 1)); ctx.fillPath()
        ctx.restoreGState()

        ctx.saveGState()
        ctx.addPath(strip); ctx.clip()
        func c(_ hex: UInt32, _ a: Double = 1) -> CGColor {
            CGColor(srgbRed: Double((hex >> 16) & 255) / 255, green: Double((hex >> 8) & 255) / 255, blue: Double(hex & 255) / 255, alpha: a)
        }
        if let g = gradient([(0, c(0x231e19)), (0.12, c(0x161311)), (0.5, c(0x191512)), (0.88, c(0x151210)), (1, c(0x241f1a))]) {
            ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: y), end: CGPoint(x: 0, y: y + stripH), options: [])
        }
        let white = { (a: Double) in CGColor(srgbRed: 1, green: 0.98, blue: 0.92, alpha: a) }
        if let g = gradient([(0, white(0.05)), (0.35, white(0)), (0.8, white(0.025)), (1, white(0))]) {
            ctx.drawLinearGradient(g, start: CGPoint(x: x, y: y), end: CGPoint(x: x + stripW * 0.55, y: y + stripH), options: [])
        }
        ctx.restoreGState()

        // Frames
        for (i, f) in frames.enumerated() {
            drawFrame(ctx, f, x: x + spec.padX + Double(i) * spec.markPitch, y: y + spec.bandH, spec: spec)
        }

        // Sprockets (135 only)
        if !spec.is120 {
            drawSprockets(ctx, spec: spec, x: x, zoneY: y + spec.textH - spec.shift, stripW: stripW, centered: centeredHoles)
            drawSprockets(ctx, spec: spec, x: x, zoneY: y + stripH - spec.textH - spec.sprocketH + spec.shift, stripW: stripW, centered: centeredHoles)
        }

        // Edge print
        if spec.is120 { edgePrint120(ctx, spec: spec, x: x, y: y, process: process, rowIndex: rowIndex, frames: frames) }
        else { edgePrint135(ctx, spec: spec, x: x, y: y, process: process, rowIndex: rowIndex, frames: frames) }

        ctx.addPath(path(x + 0.5, y + 0.5, stripW - 1, stripH - 1, radius))
        ctx.setStrokeColor(CGColor(srgbRed: 1, green: 0.973, blue: 0.9, alpha: 0.07)); ctx.setLineWidth(1); ctx.strokePath()
    }

    private static func drawFrame(_ ctx: CGContext, _ f: Frame, x: Double, y: Double, spec: Spec) {
        let slot = CGRect(x: x, y: y, width: spec.slotW, height: spec.slotH)
        let radius = max(2, (spec.slotW * 0.008).rounded())
        ctx.saveGState()
        ctx.addPath(path(x, y, spec.slotW, spec.slotH, radius)); ctx.clip()
        ctx.setFillColor(CGColor(gray: 0.105, alpha: 1)); ctx.fill(slot)
        let iw = Double(f.image.width), ih = Double(f.image.height)
        // Cover-fit; a rotated photo is turned 90 degrees clockwise first.
        let sw = f.rotate ? ih : iw, sh = f.rotate ? iw : ih
        let scale = max(spec.slotW / sw, spec.slotH / sh)
        ctx.translateBy(x: slot.midX, y: slot.midY)
        if f.rotate { ctx.rotate(by: .pi / 2) }
        ctx.scaleBy(x: 1, y: -1)                                   // CGImage draws upright in a y-up space
        ctx.draw(f.image, in: CGRect(x: -iw * scale / 2, y: -ih * scale / 2, width: iw * scale, height: ih * scale))
        ctx.restoreGState()
        ctx.addPath(path(x + 0.5, y + 0.5, spec.slotW - 1, spec.slotH - 1, radius))
        ctx.setStrokeColor(CGColor(srgbRed: 1, green: 0.84, blue: 0.59, alpha: 0.1)); ctx.setLineWidth(1); ctx.strokePath()
    }

    private static func drawSprockets(_ ctx: CGContext, spec: Spec, x: Double, zoneY: Double, stripW: Double, centered: Bool) {
        let holeH = (spec.sprocketH * 0.76).rounded()
        let holeY = zoneY + ((spec.sprocketH - holeH) / 2).rounded()
        let r = max(2, (spec.holeW * 0.28).rounded())
        let margin = (spec.slotW * 0.04).rounded()
        let count = centered ? max(1, Int((stripW / spec.pitch).rounded()))
                             : max(0, Int(((stripW - 2 * margin - spec.holeW) / spec.pitch).rounded(.down)) + 1)
        let startX = centered ? x + (stripW - (Double(count - 1) * spec.pitch + spec.holeW)) / 2 : x + margin
        for i in 0..<count {
            let hx = startX + Double(i) * spec.pitch
            let hole = path(hx, holeY, spec.holeW, holeH, r)
            ctx.addPath(hole); ctx.setFillColor(holeFill); ctx.fillPath()
            ctx.saveGState()
            ctx.addPath(hole); ctx.clip()
            let dark = CGColor(srgbRed: 0.157, green: 0.118, blue: 0.07, alpha: 0.4)
            let clear = CGColor(srgbRed: 0.157, green: 0.118, blue: 0.07, alpha: 0)
            if let g = gradient([(0, dark), (0.35, clear), (1, CGColor(gray: 1, alpha: 0.25))]) {
                ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: holeY), end: CGPoint(x: 0, y: holeY + holeH), options: [])
            }
            ctx.restoreGState()
        }
    }

    // MARK: edge print

    /// Edge print is a latent image exposed onto the film: a condensed, slightly tracked sans with a soft bloom.
    /// DIN Condensed (built into iOS) is close to the lettering Kodak and Fuji print on the rebate.
    private static func font(_ size: Double) -> CTFont { CTFontCreateWithName("DINCondensed-Bold" as CFString, CGFloat(size), nil) }

    private static func bloom(_ ctx: CGContext, _ size: Double, _ process: Process) {
        ctx.setShadow(offset: .zero, blur: CGFloat(max(1.5, size * 0.22)), color: process.glow)
    }

    /// Draws `s` with its cap height centred on `cy`; squeezed horizontally if wider than `maxWidth`.
    @discardableResult
    private static func text(_ ctx: CGContext, _ s: String, x: Double, cy: Double, size: Double, process: Process,
                             maxWidth: Double? = nil, rightAlign: Bool = false) -> Double {
        let f = font(size)
        let attrs: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): f,
            NSAttributedString.Key(kCTKernAttributeName as String): size * 0.06,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): process.ink]
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: s, attributes: attrs))
        let width = CTLineGetTypographicBounds(line, nil, nil, nil)
        let squeeze = (maxWidth != nil && width > maxWidth!) ? maxWidth! / width : 1
        let drawn = width * squeeze
        ctx.saveGState()
        bloom(ctx, size, process)
        ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)         // upright in the top-down space
        ctx.translateBy(x: rightAlign ? x - drawn : x, y: cy + CTFontGetCapHeight(f) / 2)
        ctx.scaleBy(x: squeeze, y: 1)
        ctx.textPosition = .zero
        CTLineDraw(line, ctx)
        ctx.restoreGState()
        return drawn
    }

    private static func measureEdge(_ s: String, size: Double) -> Double {
        let attrs: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font(size),
            NSAttributedString.Key(kCTKernAttributeName as String): size * 0.06]
        return CTLineGetTypographicBounds(CTLineCreateWithAttributedString(NSAttributedString(string: s, attributes: attrs)), nil, nil, nil)
    }

    @discardableResult
    private static func arrow(_ ctx: CGContext, x: Double, cy: Double, size: Double, process: Process, pointsLeft: Bool = false) -> Double {
        let w = (size * 0.5).rounded(), h = (size * 0.56).rounded()
        ctx.saveGState(); bloom(ctx, size, process); ctx.setFillColor(process.ink)
        let tip = pointsLeft ? x : x + w, back = pointsLeft ? x + w : x
        ctx.move(to: CGPoint(x: back, y: cy - h / 2)); ctx.addLine(to: CGPoint(x: tip, y: cy))
        ctx.addLine(to: CGPoint(x: back, y: cy + h / 2)); ctx.closePath(); ctx.fillPath()
        ctx.restoreGState()
        return w
    }

    /// DX-style bar code: deterministic per frame, thin and thick bars.
    private static func barcode(_ ctx: CGContext, x: Double, width: Double, cy: Double, height: Double, seed: Int, process: Process) {
        ctx.saveGState(); bloom(ctx, height, process); ctx.setFillColor(process.ink)
        let unit = max(1, (height * 0.09).rounded())
        var bx = x, i = 0
        while bx < x + width {
            let r = Double((seed &* 2654435761 &+ i &* 40503) & 0xffff) / 65535
            let bw = unit * (r > 0.62 ? 2 : 1)
            if bx + bw > x + width { break }
            ctx.fill(CGRect(x: bx, y: cy - height / 2, width: bw, height: height))
            bx += bw + unit * (r < 0.3 ? 2 : 1)
            i += 1
        }
        ctx.restoreGState()
    }

    /// Upper rebate: stock name at the start of the frame, capture data right-aligned to the frame's end
    /// (alternating with the process markings on a contact sheet when both don't fit).
    /// Lower rebate: frame number, arrow and half-frame "A" number, a DX-style bar code and the frame marker.
    private static func edgePrint135(_ ctx: CGContext, spec: Spec, x: Double, y: Double, process: Process, rowIndex: Int, frames: [Frame]) {
        let regular = max(11, (spec.textH * 0.9).rounded())
        let small = max(7, (regular * 0.7).rounded())
        let topCY = y + (spec.textH * 0.4).rounded()
        let botCY = y + spec.stripH - (spec.textH * 0.4).rounded()
        let preset = process.presets135[rowIndex % process.presets135.count]
        for (i, f) in frames.enumerated() {
            let mx = x + spec.padX + Double(i) * spec.markPitch
            let name = f.name.uppercased()
            let start = mx + (spec.slotW * 0.02).rounded(), end = mx + spec.slotW - (spec.slotW * 0.02).rounded()
            let room = end - start
            // Name left, capture data right, never overlapping: shrink both uniformly (no squeezing) to keep
            // at least 1.5 em between them; drop the focal length first, and on a contact sheet fall back to
            // alternating name / data when it would get too small.
            var params = f.params
            func fit(_ p: String) -> Double {
                let need = measureEdge(name, size: regular) + measureEdge(p, size: regular) + regular * 1.5
                return min(1, room / need)
            }
            var scale = params.isEmpty ? 1 : fit(params)
            if scale < 0.85, params.hasSuffix("MM"), let cut = params.range(of: "  ", options: .backwards) {
                params = String(params[..<cut.lowerBound]); scale = fit(params)
            }
            if params.isEmpty {
                text(ctx, i % 2 == 0 || frames.count == 1 ? name : preset, x: start, cy: topCY, size: regular, process: process, maxWidth: room)
            } else if scale < 0.7 && frames.count > 1 {
                text(ctx, i % 2 == 0 ? name : params, x: start, cy: topCY, size: regular, process: process, maxWidth: room)
            } else {
                let size = (regular * max(scale, 0.55)).rounded()
                text(ctx, name, x: start, cy: topCY, size: size, process: process, maxWidth: room * 0.5)
                text(ctx, params, x: end, cy: topCY, size: size, process: process, maxWidth: room * 0.5 - regular, rightAlign: true)
            }

            text(ctx, "\(f.number)", x: start, cy: botCY, size: regular, process: process)
            var ax = mx + (spec.slotW * 0.36).rounded()
            ax += arrow(ctx, x: ax, cy: botCY, size: regular, process: process) + regular * 0.25
            let dw = text(ctx, "\(f.number)", x: ax, cy: botCY, size: regular, process: process)
            if process.numberSuffix { text(ctx, "A", x: ax + dw, cy: botCY - (regular - small) * 0.18, size: small, process: process) }
            barcode(ctx, x: mx + spec.slotW * 0.6, width: spec.slotW * 0.26, cy: botCY, height: regular * 0.62,
                    seed: f.number * 197 + rowIndex, process: process)
            ctx.saveGState()
            bloom(ctx, regular, process)
            ctx.setFillColor(process.ink)
            let sq = (regular * 0.34).rounded()
            ctx.fill(CGRect(x: end - sq, y: botCY - sq / 2, width: sq, height: sq))
            ctx.restoreGState()
        }
    }

    /// 120 has a single rebate band per side: stock name and capture data on top, arrow, frame number,
    /// process marking and bar code below.
    /// 120 roll film carries sparse markings: frame number and advance arrow above, stock name (and capture
    /// data when it fits) below. No DX bar code; that belongs to 135 cassettes.
    private static func edgePrint120(_ ctx: CGContext, spec: Spec, x: Double, y: Double, process: Process, rowIndex: Int, frames: [Frame]) {
        let regular = max(1, (spec.textH * 0.78).rounded())
        let topCY = y + (spec.textH * 0.52).rounded()
        let botCY = y + spec.stripH - spec.textH + (spec.textH * 0.52).rounded()
        for (i, f) in frames.enumerated() {
            let mx = x + spec.padX + Double(i) * spec.markPitch
            let start = mx + (spec.slotW * 0.04).rounded(), end = mx + spec.slotW - (spec.slotW * 0.04).rounded()
            let room = end - start
            let nw = text(ctx, "\(f.number)", x: start, cy: topCY, size: regular, process: process)
            arrow(ctx, x: start + nw + regular * 0.35, cy: topCY, size: regular, process: process, pointsLeft: true)
            let name = f.name.uppercased()
            if f.params.isEmpty || i % 2 == 1 && frames.count > 1 {
                text(ctx, i % 2 == 1 && !f.params.isEmpty ? f.params : name, x: start, cy: botCY, size: regular, process: process, maxWidth: room)
            } else {
                let need = measureEdge(name, size: regular) + measureEdge(f.params, size: regular) + regular * 1.5
                let size = (regular * max(0.55, min(1, room / need))).rounded()
                text(ctx, name, x: start, cy: botCY, size: size, process: process, maxWidth: room * 0.5)
                text(ctx, f.params, x: end, cy: botCY, size: size, process: process, maxWidth: room * 0.5 - regular, rightAlign: true)
            }
        }
    }

    // MARK: medium-format scan

    /// A single medium-format frame as it comes off a scanner: a wide, uneven, warm-black rebate with fine grain,
    /// a slightly irregular soft image edge, and sparse, dim edge markings (frame numbers with advance arrows on
    /// top; an emulsion code and the stock name below).
    static func scan120(_ src: PixelBuffer, film: String, category: String, number: Int) -> PixelBuffer? {
        guard let image = src.cgImage() else { return nil }
        let w = Double(src.width), h = Double(src.height), L = max(w, h)
        let left = (L * 0.022).rounded(), right = (L * 0.048).rounded(), top = (L * 0.045).rounded(), bottom = (L * 0.062).rounded()
        let cw = w + left + right, ch = h + top + bottom
        let fw = Int(cw), fh = Int(ch)
        var process = Process(category: category)
        process.ink = process.ink.copy(alpha: 0.7) ?? process.ink
        process.glow = process.glow.copy(alpha: 0.25) ?? process.glow

        func hash(_ a: Int, _ b: Int) -> Double {
            var v = UInt32(truncatingIfNeeded: a &* 73856093 ^ b &* 19349663 ^ number &* 83492791)
            v ^= v >> 13; v = v &* 0x5bd1e995; v ^= v >> 15
            return Double(v & 0xffff) / 65535
        }
        guard var pixels = render(width: fw, height: fh, { ctx in
            ctx.setFillColor(CGColor(srgbRed: 0.055, green: 0.047, blue: 0.043, alpha: 1))
            ctx.fill(CGRect(x: 0, y: 0, width: cw, height: ch))

            // Slightly irregular frame edge: the gate of the camera is never a perfect rectangle.
            let step = L / 48, amp = L * 0.0014
            let edge = CGMutablePath()
            var t = 0.0
            edge.move(to: CGPoint(x: left, y: top))
            while t < w { t = min(w, t + step); edge.addLine(to: CGPoint(x: left + t, y: top + amp * hash(Int(t), 1))) }
            t = 0
            while t < h { t = min(h, t + step); edge.addLine(to: CGPoint(x: left + w - amp * hash(Int(t), 2), y: top + t)) }
            t = w
            while t > 0 { t = max(0, t - step); edge.addLine(to: CGPoint(x: left + t, y: top + h - amp * hash(Int(t), 3))) }
            t = h
            while t > 0 { t = max(0, t - step); edge.addLine(to: CGPoint(x: left + amp * hash(Int(t), 4), y: top + t)) }
            edge.closeSubpath()

            ctx.saveGState()
            ctx.addPath(edge); ctx.clip()
            ctx.translateBy(x: left, y: top + h); ctx.scaleBy(x: 1, y: -1)
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            ctx.restoreGState()
            // Soft inner falloff where the image meets the rebate.
            ctx.saveGState()
            ctx.addPath(edge); ctx.clip()
            ctx.setShadow(offset: .zero, blur: CGFloat(L * 0.004), color: CGColor(gray: 0, alpha: 0.85))
            ctx.addPath(edge); ctx.setStrokeColor(CGColor(gray: 0, alpha: 0.9)); ctx.setLineWidth(CGFloat(L * 0.0015)); ctx.strokePath()
            ctx.restoreGState()

            // Edge markings, small and dim, each nudged a little off a perfect grid.
            let size = (top * 0.34).rounded()
            let tcy = top * 0.5 + (hash(7, 7) - 0.5) * size * 0.15
            for (k, (n, fx)) in [(number + 1, 0.15), (number, 0.74)].enumerated() {
                let tx = left + w * fx + (hash(k, 9) - 0.5) * size
                let nw = text(ctx, "\(n)", x: tx, cy: tcy, size: size, process: process)
                arrow(ctx, x: tx + nw + size * 0.6, cy: tcy, size: size * 1.15, process: process, pointsLeft: true)
            }
            let bcy = top + h + bottom * 0.5 + (hash(8, 8) - 0.5) * size * 0.15
            var code = 5381
            for u in film.unicodeScalars { code = (code &* 33 &+ Int(u.value)) & 0x7fffffff }
            text(ctx, "\(1000 + code % 9000)", x: left + w * 0.035, cy: bcy, size: size, process: process)
            let name = film.uppercased()
            let nameW = measureEdge(name, size: size)
            text(ctx, name, x: left + (w - min(nameW, w * 0.5)) / 2, cy: bcy, size: size, process: process, maxWidth: w * 0.5)
            text(ctx, "\(number + 1)", x: cw - size * 0.35, cy: bcy, size: size, process: process)   // next frame, cut off by the scan
        }) else { return nil }

        // Fine grain in the rebate only; the photograph keeps its own grain.
        pixels.withUnsafeMutableBytes { (buf: UnsafeMutableRawBufferPointer) in
            guard let p = buf.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            let x0 = Int(left), x1 = Int(left + w), y0 = Int(top), y1 = Int(top + h)
            for y in 0..<fh {
                let inside = y >= y0 && y < y1
                var x = 0
                while x < fw {
                    if inside && x == x0 { x = x1; continue }
                    let o = (y * fw + x) * 4
                    var v = UInt32(truncatingIfNeeded: x &* 374761393 &+ y &* 668265263)
                    v = (v ^ (v >> 13)) &* 1274126177
                    let n = Int((v >> 24) & 15) - 7
                    for c in 0..<3 { p[o + c] = UInt8(clamping: Int(p[o + c]) + n) }
                    x += 1
                }
            }
        }
        return PixelBuffer(width: fw, height: fh, data: pixels, is16: false)
    }
}

// MARK: - Watermarks

/// What a watermark can print: film, strength, capture data from EXIF.
struct WatermarkInfo: Sendable {
    var film = "", category = "", strength = 100, number = 1
    var camera = "", lens = "", exposure = ""
    var date = Date()

    init(properties: [String: Any], film: String, category: String, strength: Int, number: Int) {
        self.film = film; self.category = category; self.strength = strength; self.number = number
        let exif = properties[kCGImagePropertyExifDictionary as String] as? [String: Any] ?? [:]
        let tiff = properties[kCGImagePropertyTIFFDictionary as String] as? [String: Any] ?? [:]
        func trimmed(_ v: Double) -> String {
            let t = String(format: "%.1f", v)
            return t.hasSuffix(".0") ? String(t.dropLast(2)) : t
        }
        var parts: [String] = []
        if let iso = (exif[kCGImagePropertyExifISOSpeedRatings as String] as? [Int])?.first { parts.append("ISO \(iso)") }
        if let f = exif[kCGImagePropertyExifFNumber as String] as? Double { parts.append("f/" + trimmed(f)) }
        if let t = exif[kCGImagePropertyExifExposureTime as String] as? Double, t > 0 {
            parts.append(t < 1 ? "1/\(Int((1 / t).rounded()))" : trimmed(t) + "s")
        }
        if let mm = exif[kCGImagePropertyExifFocalLength as String] as? Double { parts.append("\(Int(mm.rounded()))mm") }
        exposure = parts.joined(separator: " · ")
        let make = (tiff[kCGImagePropertyTIFFMake as String] as? String ?? "").trimmingCharacters(in: .whitespaces)
        let model = (tiff[kCGImagePropertyTIFFModel as String] as? String ?? "").trimmingCharacters(in: .whitespaces)
        camera = model.hasPrefix(make) ? model : (make + " " + model).trimmingCharacters(in: .whitespaces)
        lens = exif[kCGImagePropertyExifLensModel as String] as? String ?? ""
        if let text = exif[kCGImagePropertyExifDateTimeOriginal as String] as? String {
            let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy:MM:dd HH:mm:ss"
            if let d = f.date(from: text) { date = d }
        }
    }

    /// Capture data in edge-print style: upper case, separated by double spaces.
    var edgeParams: String { exposure.replacingOccurrences(of: " · ", with: "  ").uppercased() }

    var dateText: String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy.MM.dd"
        return f.string(from: date)
    }
    var stampText: String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "''yy  MM  dd"
        return f.string(from: date)
    }
}

extension PixelBuffer {
    /// 8-bit sRGB copy of a CGImage.
    static func from(cgImage image: CGImage) -> PixelBuffer? {
        let w = image.width, h = image.height
        guard w > 0, h > 0, let cs = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        var data = Data(count: w * h * 4)
        let ok: Bool = data.withUnsafeMutableBytes { p in
            guard let ctx = CGContext(data: p.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            ctx.interpolationQuality = .high
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return ok ? PixelBuffer(width: w, height: h, data: data, is16: false) : nil
    }
}

private func ctFont(_ name: String, _ size: Double) -> CTFont { CTFontCreateWithName(name as CFString, CGFloat(size), nil) }

/// San Francisco (with automatic CJK fallback), optionally with tabular figures for numbers.
private func uiFont(_ size: Double, _ weight: UIFont.Weight, digits: Bool = false) -> CTFont {
    (digits ? UIFont.monospacedDigitSystemFont(ofSize: CGFloat(size), weight: weight)
            : UIFont.systemFont(ofSize: CGFloat(size), weight: weight)) as CTFont
}

/// The orange LCD date imprinted by compact film cameras: slanted seven-segment digits with a red-orange bloom.
private enum SevenSegment {
    private static let map: [Character: String] = [
        "0": "abcdef", "1": "bc", "2": "abged", "3": "abgcd", "4": "fgbc",
        "5": "afgcd", "6": "afgedc", "7": "abc", "8": "abcdefg", "9": "abcdfg"]

    static func draw(_ ctx: CGContext, _ text: String, right: Double, baseline: Double, height h: Double) {
        let w = h * 0.52, t = h * 0.13, g = t * 0.22, gap = h * 0.2
        func advance(_ c: Character) -> Double { c == " " ? w * 0.45 : c == "'" ? t * 2.2 : w + gap }
        let total = text.reduce(0) { $0 + advance($1) } - gap
        var x0 = right - total
        let slant = 0.1
        func pt(_ x: Double, _ y: Double) -> CGPoint { CGPoint(x: x + (baseline - y) * slant, y: y) }
        func seg(_ x: Double, _ y: Double, _ sw: Double, _ sh: Double) {
            let horizontal = sw > sh, k = min(sw, sh) / 2
            if horizontal {   // pointed ends
                ctx.move(to: pt(x, y + k)); ctx.addLine(to: pt(x + k, y)); ctx.addLine(to: pt(x + sw - k, y))
                ctx.addLine(to: pt(x + sw, y + k)); ctx.addLine(to: pt(x + sw - k, y + sh)); ctx.addLine(to: pt(x + k, y + sh))
            } else {
                ctx.move(to: pt(x + k, y)); ctx.addLine(to: pt(x + sw, y + k)); ctx.addLine(to: pt(x + sw, y + sh - k))
                ctx.addLine(to: pt(x + k, y + sh)); ctx.addLine(to: pt(x, y + sh - k)); ctx.addLine(to: pt(x, y + k))
            }
            ctx.closePath()
        }
        let top = baseline - h, mid = baseline - h / 2
        for c in text {
            if c == "'" {
                seg(x0, top, t, h * 0.3)
            } else if let segs = map[c] {
                for s in segs {
                    switch s {
                    case "a": seg(x0 + g, top, w - 2 * g, t)
                    case "g": seg(x0 + g, mid - t / 2, w - 2 * g, t)
                    case "d": seg(x0 + g, baseline - t, w - 2 * g, t)
                    case "f": seg(x0, top + g, t, h / 2 - 2 * g)
                    case "b": seg(x0 + w - t, top + g, t, h / 2 - 2 * g)
                    case "e": seg(x0, mid + g, t, h / 2 - 2 * g)
                    case "c": seg(x0 + w - t, mid + g, t, h / 2 - 2 * g)
                    default: break
                    }
                }
            }
            x0 += advance(c)
        }
        let path = ctx.path
        ctx.saveGState()
        ctx.setShadow(offset: .zero, blur: CGFloat(h * 0.35), color: CGColor(srgbRed: 1, green: 0.32, blue: 0.04, alpha: 0.75))
        ctx.setFillColor(CGColor(srgbRed: 1, green: 0.55, blue: 0.16, alpha: 0.95))
        ctx.fillPath()
        ctx.restoreGState()
        // Hot core: a brighter, tighter pass over the same segments.
        if let path {
            ctx.saveGState()
            ctx.addPath(path)
            ctx.setShadow(offset: .zero, blur: CGFloat(h * 0.08), color: CGColor(srgbRed: 1, green: 0.6, blue: 0.2, alpha: 0.9))
            ctx.setFillColor(CGColor(srgbRed: 1, green: 0.78, blue: 0.45, alpha: 0.55))
            ctx.fillPath()
            ctx.restoreGState()
        }
    }
}

private func measure(_ s: String, _ font: CTFont) -> Double {
    let attrs: [NSAttributedString.Key: Any] = [NSAttributedString.Key(kCTFontAttributeName as String): font]
    return CTLineGetTypographicBounds(CTLineCreateWithAttributedString(NSAttributedString(string: s, attributes: attrs)), nil, nil, nil)
}

/// Draws `s` with its baseline at `baseline` in a top-down context. `anchor`: 0 = left, 0.5 = centre, 1 = right of `x`.
private func drawLabel(_ ctx: CGContext, _ s: String, x: Double, baseline: Double, font: CTFont, color: CGColor,
                       anchor: Double = 0, glow: CGColor? = nil) {
    guard !s.isEmpty else { return }
    let attrs: [NSAttributedString.Key: Any] = [
        NSAttributedString.Key(kCTFontAttributeName as String): font,
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): color]
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: s, attributes: attrs))
    let width = CTLineGetTypographicBounds(line, nil, nil, nil)
    ctx.saveGState()
    if let glow { ctx.setShadow(offset: .zero, blur: 3, color: glow) }
    ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
    ctx.textPosition = CGPoint(x: x - width * anchor, y: baseline)
    CTLineDraw(line, ctx)
    ctx.restoreGState()
}

extension Encoder {
    static func watermarked(_ src: PixelBuffer, style: WatermarkStyle, info: WatermarkInfo,
                            orientation: StripOrientation = .auto) -> PixelBuffer {
        switch style {
        case .none: return src
        case .film135: return framed(src, is120: false, category: info.category, title: info.film, params: info.edgeParams,
                                     frame: info.number, orientation: orientation)
        case .film120:
            return FilmStripArt.scan120(src, film: info.film, category: info.category, number: info.number) ?? src
        case .infoBar, .dateStamp, .print, .cornerTag: return decorated(src, style, info) ?? src
        }
    }

    private static func decorated(_ src: PixelBuffer, _ style: WatermarkStyle, _ info: WatermarkInfo) -> PixelBuffer? {
        guard let image = src.cgImage() else { return nil }
        let w = Double(src.width), h = Double(src.height)
        let long = max(w, h), short = min(w, h)
        var cw = w, ch = h, ox = 0.0, oy = 0.0
        var bar = 0.0, side = 0.0, bottom = 0.0
        switch style {
        case .infoBar: bar = (long * 0.085).rounded(); ch = h + bar
        case .print:
            side = (long * 0.045).rounded(); bottom = (long * 0.15).rounded()
            cw = w + 2 * side; ch = h + side + bottom; ox = side; oy = side
        default: break
        }
        let fw = Int(cw.rounded()), fh = Int(ch.rounded())
        guard let pixels = FilmStripArt.render(width: fw, height: fh, { ctx in
            if style == .infoBar || style == .print {
                ctx.setFillColor(style == .infoBar ? CGColor(gray: 1, alpha: 1) : CGColor(srgbRed: 0.984, green: 0.980, blue: 0.965, alpha: 1))
                ctx.fill(CGRect(x: 0, y: 0, width: cw, height: ch))
            }
            ctx.saveGState()
            ctx.translateBy(x: ox, y: oy + h); ctx.scaleBy(x: 1, y: -1)
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            ctx.restoreGState()

            switch style {
            case .infoBar:
                let pad = bar * 0.45
                let ink = CGColor(gray: 0.11, alpha: 1), mid = CGColor(gray: 0.23, alpha: 1), soft = CGColor(gray: 0.54, alpha: 1)
                drawLabel(ctx, info.film, x: pad, baseline: h + bar * 0.47, font: uiFont(bar * 0.30, .semibold), color: ink)
                drawLabel(ctx, "\(info.category) · 强度 \(info.strength)%", x: pad, baseline: h + bar * 0.78,
                          font: uiFont(bar * 0.2, .regular), color: soft)
                let top = info.exposure.isEmpty ? info.dateText : info.exposure
                drawLabel(ctx, top, x: w - pad, baseline: h + bar * 0.47, font: uiFont(bar * 0.24, .medium, digits: true), color: mid, anchor: 1)
                let sub = [info.camera, info.exposure.isEmpty ? "" : info.dateText].filter { !$0.isEmpty }.joined(separator: " · ")
                drawLabel(ctx, sub, x: w - pad, baseline: h + bar * 0.78, font: uiFont(bar * 0.2, .regular, digits: true), color: soft, anchor: 1)
            case .dateStamp:
                SevenSegment.draw(ctx, info.stampText, right: w - long * 0.035, baseline: h - long * 0.035, height: short * 0.042)
            case .print:
                let text = [info.film, info.dateText].filter { !$0.isEmpty }.joined(separator: " · ")
                drawLabel(ctx, text, x: cw / 2, baseline: side + h + bottom * 0.58, font: ctFont("SnellRoundhand", bottom * 0.3),
                          color: CGColor(gray: 0.29, alpha: 1), anchor: 0.5)
            case .cornerTag:
                let size = short * 0.03
                let font = uiFont(size, .medium)
                let text = "\(info.film) · 强度 \(info.strength)%"
                let padX = size * 0.9, padY = size * 0.55
                let pw = measure(text, font) + 2 * padX, ph = size + 2 * padY
                let rect = CGRect(x: long * 0.03, y: h - long * 0.03 - ph, width: pw, height: ph)
                ctx.addPath(CGPath(roundedRect: rect, cornerWidth: ph / 2, cornerHeight: ph / 2, transform: nil))
                ctx.setFillColor(CGColor(gray: 0, alpha: 0.45)); ctx.fillPath()
                drawLabel(ctx, text, x: rect.minX + padX, baseline: rect.minY + padY + size * 0.82, font: font, color: CGColor(gray: 1, alpha: 1))
            default: break
            }
        }) else { return nil }
        return PixelBuffer(width: fw, height: fh, data: pixels, is16: false)
    }
}
