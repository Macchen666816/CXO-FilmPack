import Foundation
import CxoShared

enum Renderer {
    /// Builds the (immutable) Kotlin render plan for one photograph. Must run on the render worker.
    static func prepare(runtime: FilmRuntime, source: PixelBuffer, film: FilmEntry?, look rawLook: Look) throws -> RenderJob {
        var look = rawLook
        if !look.lightOn { look.resetLight() }
        if !look.colorOn { look.resetColor() }
        if !look.edgeOn { look.edgeID = "" }
        var grainID = look.grainID
        if look.grainOn, grainID.isEmpty, let film, !runtime.grainIdentifiers.contains(film.token) {
            grainID = runtime.grainIdentifiers.first ?? ""
        }
        let grain = try IosBridgeKt.makeGrainSettings(
            enabled: film != nil && look.grainOn && runtime.grainAvailable, identifier: grainID,
            intensity: Int32(look.grainIntensity), format: Int32(look.grainFormat),
            manualSize: look.grainSize, seed: look.grainSeed)
        let post = try IosBridgeKt.makePostSettings(
            exposureEv: look.exposure, brightness: Int32(look.brightness), contrast: Int32(look.contrast),
            temperature: Int32(look.temperature), tint: Int32(look.tint), hue: Int32(look.hue),
            saturation: Int32(look.saturation), vibrance: Int32(look.vibrance),
            highlights: Int32(look.highlights), shadows: Int32(look.shadows),
            whites: Int32(look.whites), blacks: Int32(look.blacks), curve: Int32(look.curve),
            edgeId: look.edgeID, edgeIntensity: Int32(look.edgeIntensity),
            edgePosition: Int32(look.edgePosition), edgeSeed: look.edgeSeed)
        let root = runtime.assetsRoot
        if let raw = source.cameraRaw {
            return try IosBridgeKt.prepareRawJob(
                engine: runtime.engine, path: raw.cacheURL.path, fullW: Int32(raw.width), fullH: Int32(raw.height),
                model: raw.model, make: raw.make, rawBps: raw.rawBps, maximumAfterProcess: raw.maximumAfterProcess,
                camMul0: raw.camMul[0], camMul1: raw.camMul[1], camMul2: raw.camMul[2], camMul3: raw.camMul[3],
                width: Int32(source.width), height: Int32(source.height), film: film?.choice,
                intensity: Int32(look.strength), grain: grain, post: post,
                loadTexture: { path in TextureLoader.load(path: path, root: root) })
        }
        guard let film else { throw AppError.message("缺少胶片") }
        return try IosBridgeKt.prepareJob(
            engine: runtime.engine, width: Int32(source.width), height: Int32(source.height), film: film.choice,
            intensity: Int32(look.strength), grain: grain, post: post,
            loadTexture: { path in TextureLoader.load(path: path, root: root) })
    }
}

// MARK: - Render queue
//
// One worker, strictly serial: nothing in the app renders in parallel. Work is cut into bands of
// rows ("steps"); after every step the scheduler picks the most urgent pending job, so a preview
// can interleave with a long export without ever running alongside it.
//
//   priority:  preview (0)  >  strip (1)  >  film thumbnail (2)  >  export (3)
//   key:       a new job with the same key supersedes the older one (latest-wins, e.g. slider drags)
//   cancel:    cancelling the awaiting Task drops the job at the next step boundary

enum JobPriority: Int { case preview = 0, strip = 1, thumbnail = 2, export = 3 }

final class RenderTicket: @unchecked Sendable {
    private let lock = NSLock()
    private var flag = false
    func cancel() { lock.lock(); flag = true; lock.unlock() }
    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return flag }
}

final class QueuedJob: @unchecked Sendable {
    let priority: JobPriority
    let key: String?
    let ticket: RenderTicket
    var seq = 0

    private let runtime: FilmRuntime
    private let film: FilmEntry?
    private let look: Look
    private let input: () throws -> PixelBuffer
    private let completion: (Result<PixelBuffer?, Error>) -> Void

    // Worker-thread state
    private(set) var hasStarted = false
    private var source: PixelBuffer?
    private var plan: RenderJob?
    private var out = Data()
    private var nextRow = 0
    private let chunk = 16

    init(priority: JobPriority, key: String?, ticket: RenderTicket, runtime: FilmRuntime, film: FilmEntry?, look: Look,
         input: @escaping () throws -> PixelBuffer, completion: @escaping (Result<PixelBuffer?, Error>) -> Void) {
        self.priority = priority; self.key = key; self.ticket = ticket
        self.runtime = runtime; self.film = film; self.look = look
        self.input = input; self.completion = completion
    }

    func start() throws {
        hasStarted = true
        let src = try input()
        source = src
        if film == nil && src.cameraRaw == nil { return }
        plan = try Renderer.prepare(runtime: runtime, source: src, film: film, look: look)
        out = Data(count: src.width * src.height * 4)
    }

    /// Renders the next band of rows; returns true once the whole image is done.
    func step() throws -> Bool {
        guard let src = source, let plan else { return true }
        let w = src.width, h = src.height
        let rows = min(chunk, h - nextRow)
        var slice = try src.bandData(top: nextRow, rows: rows)
        // RGBX contexts leave the fourth byte undefined. The bridge expects real alpha.
        if !src.is16 {
            slice.withUnsafeMutableBytes { (bytes: UnsafeMutableRawBufferPointer) in
                for i in stride(from: 3, to: bytes.count, by: 4) { bytes[i] = 255 }
            }
        }
        let band = src.is16
            ? try IosBridgeKt.renderBand16(job: plan, rgba16: slice, top: Int32(nextRow), rows: Int32(rows))
            : try IosBridgeKt.renderBand(job: plan, rgba: slice, top: Int32(nextRow), rows: Int32(rows))
        guard band.count == rows * w * 4 else { throw AppError.message("渲染结果大小不符") }
        out.replaceSubrange((nextRow * w * 4)..<((nextRow + rows) * w * 4), with: band)
        nextRow += rows
        return nextRow >= h
    }

    func result() -> PixelBuffer {
        if plan == nil, let source { return source }
        return PixelBuffer(width: source?.width ?? 0, height: source?.height ?? 0, data: out, is16: false)
    }

    /// Always called exactly once, on the worker thread.
    func finish(_ r: Result<PixelBuffer?, Error>) { completion(r) }
}

final class RenderQueue: @unchecked Sendable {
    private let worker = DispatchQueue(label: "cxo.render", qos: .userInitiated)
    private let lock = NSLock()
    private var pending: [QueuedJob] = []
    private var running = false
    private var counter = 0

    /// Renders `film`+`look` over `input()` and hands the result to `finish`, all on the single worker.
    /// Returns .success(nil) when cancelled or superseded.
    func run<T>(priority: JobPriority, key: String? = nil, runtime: FilmRuntime, film: FilmEntry?, look: Look,
                input: @escaping () throws -> PixelBuffer,
                finish: @escaping (PixelBuffer) throws -> T) async -> Result<T?, Error> {
        let ticket = RenderTicket()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Result<T?, Error>, Never>) in
                let job = QueuedJob(priority: priority, key: key, ticket: ticket, runtime: runtime, film: film, look: look, input: input) { result in
                    switch result {
                    case .success(let buffer?):
                        do { continuation.resume(returning: .success(try finish(buffer))) }
                        catch { continuation.resume(returning: .failure(error)) }
                    case .success(nil): continuation.resume(returning: .success(nil))
                    case .failure(let error): continuation.resume(returning: .failure(error))
                    }
                }
                submit(job)
            }
        } onCancel: { ticket.cancel() }
    }

    /// Drops every queued job whose key matches (e.g. when the photo changes).
    func cancel(keyPrefix: String) {
        lock.lock(); defer { lock.unlock() }
        for job in pending { if let k = job.key, k.hasPrefix(keyPrefix) { job.ticket.cancel() } }
    }

    private func submit(_ job: QueuedJob) {
        lock.lock()
        if let key = job.key { for old in pending where old.key == key { old.ticket.cancel() } }
        counter += 1; job.seq = counter
        pending.append(job)
        let start = !running
        running = true
        lock.unlock()
        if start { worker.async { self.loop() } }
    }

    /// Cancelled jobs are retired first; otherwise the most urgent job (then oldest) gets the next step.
    private func next() -> (job: QueuedJob?, dead: [QueuedJob]) {
        lock.lock(); defer { lock.unlock() }
        // Sample cancellation once: a second read can remove a newly cancelled job
        // without including it in `dead`, leaking its continuation forever.
        var dead: [QueuedJob] = []
        pending.removeAll { job in
            guard job.ticket.isCancelled else { return false }
            dead.append(job)
            return true
        }
        guard let best = pending.min(by: { ($0.priority.rawValue, $0.seq) < ($1.priority.rawValue, $1.seq) }) else {
            running = false
            return (nil, dead)
        }
        return (best, dead)
    }

    private func remove(_ job: QueuedJob) {
        lock.lock(); pending.removeAll { $0 === job }; lock.unlock()
    }

    private func loop() {
        while true {
            let (job, dead) = next()
            for d in dead { d.finish(.success(nil)) }
            guard let job else { return }
            do {
                if !job.hasStarted { try job.start() }
                if try job.step() {
                    remove(job)
                    job.finish(job.ticket.isCancelled ? .success(nil) : .success(job.result()))
                }
            } catch {
                remove(job)
                job.finish(.failure(error))
            }
        }
    }
}
