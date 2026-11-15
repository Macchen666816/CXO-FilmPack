import Foundation
import CxoShared

enum AppError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let m) = self { return m }; return nil }
}

struct FilmDescription: Decodable {
    var title: String?
    var manufacturer: String?
    var type: String?
    var introduced: String?
    var discontinued: String?
    var description: String?
}

struct FilmEntry: Identifiable, Hashable {
    let id: String
    let token: String
    let name: String
    let category: String
    let experimental: Bool
    let choice: FilmChoice

    static func == (a: FilmEntry, b: FilmEntry) -> Bool { a.id == b.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

/// Owns the Kotlin engine and static catalogues. All decoding, plan preparation and bands use RenderQueue.
final class FilmRuntime: @unchecked Sendable {
    let engine: FilmEngine
    let assetsRoot: String
    let films: [FilmEntry]
    let categories: [String]
    let descriptions: [String: FilmDescription]
    let grainIdentifiers: [String]
    let edgeDefects: [EdgeDefectChoice]
    let queue = DispatchQueue(label: "cxo.engine")

    init() throws {
        guard let resources = Bundle.main.resourcePath else { throw AppError.message("找不到应用资源") }
        let root = resources + "/Assets"
        let grainPack = root + "/native-grain8-v1.cxograinpack"
        let hasGrain = FileManager.default.fileExists(atPath: grainPack)
        engine = try IosBridgeKt.makeEngine(assetsRoot: root, grainPackPath: hasGrain ? grainPack : nil)
        assetsRoot = root

        let all = IosBridgeKt.filmsOf(engine: engine)
        films = all.map { FilmEntry(id: $0.id, token: $0.token, name: $0.name, category: $0.category, experimental: $0.experimental, choice: $0) }
        var seen = Set<String>(); var cats: [String] = []
        for f in films where seen.insert(f.category).inserted { cats.append(f.category) }
        categories = cats

        if let data = try? Data(contentsOf: URL(fileURLWithPath: root + "/film-descriptions-zh.json")),
           let d = try? JSONDecoder().decode([String: FilmDescription].self, from: data) { descriptions = d } else { descriptions = [:] }
        grainIdentifiers = engine.grainAvailable ? engine.grainIdentifiers() : []
        edgeDefects = engine.edgeDefects()
    }

    var grainAvailable: Bool { engine.grainAvailable }
}
