import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct RootView: View {
    @Environment(EditorModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var pickerItems: [PhotosPickerItem] = []

    var body: some View {
        @Bindable var m = model
        ZStack {
            Color.black.ignoresSafeArea()
            if let error = model.startupError {
                VStack(spacing: 8) {
                    Text("启动失败").font(.title3.weight(.semibold))
                    Text(error).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }.padding()
            } else if model.photos.isEmpty {
                EmptyStateView()
            } else if sizeClass == .regular {
                PadLayout()
            } else {
                PhoneLayout()
            }
        }
        .photosPicker(isPresented: $m.showPhotoPicker, selection: $pickerItems, matching: .images)
        .onChange(of: pickerItems) { _, items in
            guard !items.isEmpty else { return }
            Task {
                var imports: [(url: URL, name: String)] = []
                var index = model.photos.count
                for item in items {
                    index += 1
                    guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
                    let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
                    let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).\(ext)")
                    if (try? data.write(to: url)) != nil { imports.append((url, "照片\(index)")) }
                }
                if imports.isEmpty { model.message = "无法读取所选照片" } else { model.importPhotos(imports) }
                pickerItems = []
            }
        }
        .fileImporter(isPresented: $m.showFileImporter, allowedContentTypes: [.image, .rawImage], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            var imports: [(url: URL, name: String)] = []
            for url in urls {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                // Security-scoped access ends here, so keep a private copy.
                let copy = FileManager.default.temporaryDirectory
                    .appendingPathComponent("\(UUID().uuidString).\(url.pathExtension)")
                if (try? FileManager.default.copyItem(at: url, to: copy)) != nil {
                    imports.append((copy, url.deletingPathExtension().lastPathComponent))
                }
            }
            if imports.isEmpty { model.message = "无法读取所选文件" } else { model.importPhotos(imports) }
        }
        .sheet(item: $m.sheet) { [model] kind in
            SheetContent(kind: kind, model: model)
                .environment(model)
                .presentationDetents((kind == .settings || kind == .export) ? [.large] : [.fraction(0.42), .large])
                .presentationBackgroundInteraction(.enabled(upThrough: .fraction(0.42)))
                .presentationDragIndicator(.visible)
                .glassSheetBackground()
        }
    }
}

struct SheetContent: View {
    let kind: SheetKind
    let model: EditorModel

    var body: some View {
        switch kind {
        case .settings:
            SettingsView()
        case .export:
            ExportView(model: model)
        case .library:
            FilmLibraryView(columns: 3).padding([.horizontal, .top]).padding(.top, 8)
        default:
            ScrollView {
                VStack(spacing: 12) {
                    switch kind {
                    case .adjust: AdjustView()
                    case .grain: GrainView()
                    case .edge: EdgeView()
                    default: EmptyView()
                    }
                }
                .padding([.horizontal, .top]).padding(.top, 14).padding(.bottom, 24)
            }
        }
    }
}

struct EmptyStateView: View {
    @Environment(EditorModel.self) private var model

    var body: some View {
        VStack(spacing: 22) {
            Image(systemName: "film.stack").font(.system(size: 56, weight: .light))
            VStack(spacing: 6) {
                Text("CXO FilmPack").font(.largeTitle.weight(.bold))
                Text("选一张照片，套上胶片的味道").foregroundStyle(.secondary)
            }
            GlassGroup {
                VStack(spacing: 12) {
                    GlassTextButton(title: "从相册选取", systemName: "photo.on.rectangle", prominent: true) { model.showPhotoPicker = true }
                    GlassTextButton(title: "从文件选取（含 RAW）", systemName: "folder") { model.showFileImporter = true }
                }
            }
            if let message = model.message { Text(message).font(.footnote).foregroundStyle(.orange) }
        }
    }
}

/// Shared floating message pill.
struct MessageToast: View {
    @Environment(EditorModel.self) private var model
    var body: some View {
        if let message = model.message {
            Text(message)
                .font(.footnote.weight(.medium))
                .padding(.horizontal, 14).padding(.vertical, 9)
                .glass()
                .onTapGesture { model.message = nil }
                .transition(.opacity)
        }
    }
}

struct OpenMenu: View {
    @Environment(EditorModel.self) private var model
    var body: some View {
        Menu {
            Button("从相册选取", systemImage: "photo.on.rectangle") { model.showPhotoPicker = true }
            Button("从文件选取（含 RAW）", systemImage: "folder") { model.showFileImporter = true }
        } label: {
            Image(systemName: "plus").font(.system(size: 17, weight: .semibold)).frame(width: 44, height: 44).contentShape(Circle())
        }
        .glass(in: Circle(), interactive: true)
    }
}

struct CompareButton: View {
    @Environment(EditorModel.self) private var model
    var body: some View {
        Image(systemName: model.showOriginal ? "eye.fill" : "eye")
            .font(.system(size: 17, weight: .semibold))
            .frame(width: 44, height: 44)
            .contentShape(Circle())
            .glass(in: Circle(), interactive: true, tint: model.showOriginal ? Color.accentColor : nil)
            .onLongPressGesture(minimumDuration: .infinity, maximumDistance: 60, perform: {}, onPressingChanged: { model.showOriginal = $0 })
    }
}

struct PhotoCanvas: View {
    @Environment(EditorModel.self) private var model
    var body: some View {
        ZoomableImageView(image: model.showOriginal ? model.original : (model.result ?? model.original),
                          token: model.photoToken, onHold: { model.showOriginal = $0 })
            .ignoresSafeArea()
            .overlay(alignment: .topLeading) {
                if model.showOriginal {
                    Text("原图").font(.caption.weight(.semibold)).padding(.horizontal, 10).padding(.vertical, 5)
                        .glass().padding(.top, 70).padding(.leading, 16)
                }
            }
    }
}
