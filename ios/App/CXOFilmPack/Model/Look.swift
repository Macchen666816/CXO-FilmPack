import Foundation

/// Everything the user can change about the look. Equatable so edits can be coalesced.
struct Look: Equatable {
    var strength = 100                 // 0...200
    var lightOn = true                 // module switches: off = bypassed, values are kept
    var colorOn = true
    var edgeOn = false
    var grainOn = false
    var grainID = ""                   // "" = follow the film
    var grainIntensity = 100           // 0...200
    var grainFormat = 0                // index into grainFormatNames()
    var grainSize: Float = 1           // manual format only, 0.5...5
    var grainSeed: Int64 = 17
    var exposure: Float = 0            // -3...3 EV
    var brightness = 0
    var contrast = 0
    var highlights = 0
    var shadows = 0
    var whites = 0
    var blacks = 0
    var temperature = 0
    var tint = 0
    var hue = 0                        // -180...180
    var saturation = 0
    var vibrance = 0
    var curve = 0                      // index into toneCurveNames()
    var edgeID = ""
    var edgeIntensity = 50
    var edgePosition = 0
    var edgeSeed: Int64 = 0

    mutating func resetLight() {
        exposure = 0; brightness = 0; contrast = 0; highlights = 0; shadows = 0; whites = 0; blacks = 0; curve = 0
    }
    mutating func resetColor() {
        temperature = 0; tint = 0; hue = 0; saturation = 0; vibrance = 0
    }
}

final class Counter: @unchecked Sendable {
    private var storage = 0
    private let lock = NSLock()
    @discardableResult func next() -> Int { lock.lock(); defer { lock.unlock() }; storage += 1; return storage }
    var value: Int { lock.lock(); defer { lock.unlock() }; return storage }
}

enum OutputFormat: String, CaseIterable, Identifiable {
    case jpeg = "JPEG", tiff = "TIFF"
    var id: String { rawValue }
    var fileExtension: String { self == .jpeg ? "jpg" : "tif" }
}

/// Watermark drawn on an exported photo.
enum WatermarkStyle: String, CaseIterable, Identifiable {
    case none = "无", film135 = "胶片 135", film120 = "中画幅扫描", infoBar = "参数信息条"
    case dateStamp = "日期戳", print = "相纸白边", cornerTag = "角标"
    var id: String { rawValue }
    var symbol: String {
        switch self {
        case .none: "nosign"
        case .film135: "film"
        case .film120: "film.stack"
        case .infoBar: "rectangle.bottomthird.inset.filled"
        case .dateStamp: "calendar"
        case .print: "photo.on.rectangle"
        case .cornerTag: "tag"
        }
    }
}

enum ExportMode: Hashable { case single, sheet }

/// Which way the film strip runs around a single photo. A portrait photo in a horizontal strip is turned 90°
/// into the frame (as on a contact sheet) so the edge print reads upright.
enum StripOrientation: String, CaseIterable, Identifiable {
    case auto = "自动", horizontal = "横排", vertical = "竖排"
    var id: String { rawValue }
}

enum SheetFormat: String, CaseIterable, Identifiable {
    case f135 = "135", f120 = "120"
    var id: String { rawValue }
}

struct ExportOptions: Equatable {
    var sheetFormat: SheetFormat = .f135
    var sheetColumns = 4
    var style: WatermarkStyle = .none
    var orientation: StripOrientation = .auto
    var format: OutputFormat = .jpeg
    var quality = 98
    var maxEdge = 0                    // 0 = original size
}

enum RawPipeline: String, CaseIterable, Identifiable {
    case apple, original
    var id: String { rawValue }
}
