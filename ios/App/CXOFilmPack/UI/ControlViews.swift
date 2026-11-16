import SwiftUI
import CxoShared

private let curveTitles = ["线性", "柔和对比", "强对比", "哑光"]
private let grainFormatTitles = ["35mm", "中画幅", "大画幅", "手动"]
private let edgePositionTitles = ["全部", "左", "上", "右", "下"]

struct StrengthSection: View {
    @Environment(EditorModel.self) private var model
    var body: some View {
        PanelSection(title: "胶片强度") {
            ParamSlider(title: model.selectedFilm?.name ?? "强度", value: model.intBinding(\.strength),
                        range: 0...200, defaultValue: 100)
        }
    }
}

/// A switchable module: header with on/off switch and reset button; the body only shows while it is on.
struct ModuleSection<Content: View>: View {
    let title: String
    @Binding var isOn: Bool
    var enabled = true
    var reset: (() -> Void)?
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Toggle(isOn: $isOn) { Text(title).font(.subheadline.weight(.semibold)) }
                    .disabled(!enabled)
                if let reset, isOn {
                    Button(action: reset) { Image(systemName: "arrow.counterclockwise").frame(width: 44, height: 44) }
                        .buttonStyle(.plain).foregroundStyle(.secondary)
                }
            }
            if isOn { content() }
        }
        .padding(.horizontal, 14).padding(.vertical, 8)
        .background(Color.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

private struct GroupHeader: View {
    let title: String
    init(_ title: String) { self.title = title }
    var body: some View {
        Text(title).font(.title3.weight(.semibold)).padding(.horizontal, 6).padding(.top, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct LightSection: View {
    @Environment(EditorModel.self) private var model
    var body: some View {
        @Bindable var m = model
        ModuleSection(title: "光线", isOn: $m.look.lightOn, reset: { model.look.resetLight() }) {
            ParamSlider(title: "曝光", value: model.floatBinding(\.exposure), range: -3...3, step: 0.05,
                        format: { String(format: "%+.2f EV", $0) })
            ParamSlider(title: "亮度", value: model.intBinding(\.brightness), range: -100...100)
            ParamSlider(title: "对比度", value: model.intBinding(\.contrast), range: -100...100)
            ParamSlider(title: "高光", value: model.intBinding(\.highlights), range: -100...100)
            ParamSlider(title: "阴影", value: model.intBinding(\.shadows), range: -100...100)
            ParamSlider(title: "白色", value: model.intBinding(\.whites), range: -100...100)
            ParamSlider(title: "黑色", value: model.intBinding(\.blacks), range: -100...100)
            Picker("色调曲线", selection: $m.look.curve) {
                ForEach(0..<curveTitles.count, id: \.self) { Text(curveTitles[$0]).tag($0) }
            }
            .pickerStyle(.segmented)
        }
    }
}

struct ColorSection: View {
    @Environment(EditorModel.self) private var model
    var body: some View {
        @Bindable var m = model
        ModuleSection(title: "色彩", isOn: $m.look.colorOn, reset: { model.look.resetColor() }) {
            ParamSlider(title: "色温", value: model.intBinding(\.temperature), range: -100...100)
            ParamSlider(title: "色调", value: model.intBinding(\.tint), range: -100...100)
            ParamSlider(title: "色相", value: model.intBinding(\.hue), range: -180...180)
            ParamSlider(title: "饱和度", value: model.intBinding(\.saturation), range: -100...100)
            ParamSlider(title: "自然饱和度", value: model.intBinding(\.vibrance), range: -100...100)
        }
    }
}

/// The single scrolling "Customize" list: Film / Development / Graphical effects.
struct CustomizeView: View {
    var body: some View {
        VStack(spacing: 12) {
            GroupHeader("胶片")
            StrengthSection()
            GrainView()
            GroupHeader("显影")
            LightSection()
            ColorSection()
            GroupHeader("图形效果")
            EdgeView()
        }
    }
}

struct AdjustView: View {
    var body: some View { CustomizeView() }
}

struct GrainView: View {
    @Environment(EditorModel.self) private var model

    var body: some View {
        @Bindable var m = model
        let runtime = model.runtime
        ModuleSection(title: "颗粒", isOn: $m.look.grainOn, enabled: runtime?.grainAvailable ?? false,
                      reset: { model.look.grainID = ""; model.look.grainIntensity = 100; model.look.grainFormat = 0; model.look.grainSize = 1 }) {
            let follows = runtime?.grainIdentifiers.contains(model.selectedFilm?.token ?? "") ?? false
            Picker("颗粒样式", selection: $m.look.grainID) {
                if follows { Text("跟随胶片").tag("") }
                ForEach(runtime?.grainIdentifiers ?? [], id: \.self) { Text($0).tag($0) }
            }
            .pickerStyle(.menu)
            if !follows && model.look.grainID.isEmpty {
                Text("该胶片没有对应颗粒，已使用第一种颗粒").font(.footnote).foregroundStyle(.secondary)
            }
            Picker("画幅", selection: $m.look.grainFormat) {
                ForEach(0..<grainFormatTitles.count, id: \.self) { Text(grainFormatTitles[$0]).tag($0) }
            }
            .pickerStyle(.segmented)
            ParamSlider(title: "强度", value: model.intBinding(\.grainIntensity), range: 0...200, defaultValue: 100)
            if model.look.grainFormat == 3 {
                ParamSlider(title: "颗粒大小", value: model.floatBinding(\.grainSize), range: 0.5...5, step: 0.1,
                            defaultValue: 1, format: { String(format: "%.1f", $0) })
            }
            HStack {
                Text("随机种子").font(.footnote)
                Spacer()
                Text("\(model.look.grainSeed)").monospacedDigit().foregroundStyle(.secondary).font(.footnote)
                Button { model.look.grainSeed = Int64.random(in: 0...999_999) } label: { Image(systemName: "dice") }
                    .buttonStyle(.bordered)
            }
        }
        if !(runtime?.grainAvailable ?? false) {
            Text("内置颗粒包未找到").font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 6)
        }
    }
}

struct EdgeView: View {
    @Environment(EditorModel.self) private var model

    var body: some View {
        @Bindable var m = model
        let choices = model.runtime?.edgeDefects ?? []
        let current = choices.first { $0.id == model.look.edgeID }
        ModuleSection(title: "边缘与漏光", isOn: $m.look.edgeOn,
                      reset: { model.look.edgeID = ""; model.look.edgeIntensity = 50; model.look.edgePosition = 0 }) {
            Picker("效果", selection: $m.look.edgeID) {
                Text("无").tag("")
                ForEach(choices, id: \.id) { Text($0.name).tag($0.id) }
            }
            .pickerStyle(.menu)
            if current != nil {
                ParamSlider(title: "强度", value: model.intBinding(\.edgeIntensity), range: 0...100, defaultValue: 50)
                if current?.isNotEdge == false {
                    Picker("位置", selection: $m.look.edgePosition) {
                        ForEach(0..<edgePositionTitles.count, id: \.self) { Text(edgePositionTitles[$0]).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
                HStack {
                    Text("随机种子").font(.footnote)
                    Spacer()
                    Text("\(model.look.edgeSeed)").monospacedDigit().foregroundStyle(.secondary).font(.footnote)
                    Button { model.look.edgeSeed = Int64.random(in: 0...999_999) } label: { Image(systemName: "dice") }
                        .buttonStyle(.bordered)
                }
            }
        }
    }
}

/// Full-screen export: a live preview of the result next to the options.
struct ExportView: View {
    // The export sheet owns an explicit reference to the existing editor model.
    let model: EditorModel
    @Environment(\.dismiss) private var dismiss
    @State private var mode: ExportMode = .single
    @State private var exportAll = false
    @State private var preview: UIImage?

    private struct PreviewKey: Hashable {
        let mode: ExportMode, style: WatermarkStyle, orientation: StripOrientation, format: SheetFormat, columns: Int, photo: UUID?, count: Int
    }

    var body: some View {
        let o = model.exportOptions
        NavigationStack {
            GeometryReader { geo in
                let wide = geo.size.width > 760
                let layout = wide ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
                layout {
                    previewPane
                        .frame(maxWidth: .infinity, maxHeight: wide ? .infinity : geo.size.height * 0.4)
                    ScrollView { options.padding(14) }
                        .frame(width: wide ? 380 : nil)
                }
            }
            .navigationTitle("导出")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("完成") { dismiss() } } }
        }
        .task(id: PreviewKey(mode: mode, style: o.style, orientation: o.orientation, format: o.sheetFormat, columns: o.sheetColumns,
                             photo: model.currentID, count: model.photos.count)) {
            try? await Task.sleep(nanoseconds: 80_000_000)
            if Task.isCancelled { return }
            let image = await model.exportPreview(mode: mode, options: model.exportOptions)
            if !Task.isCancelled { preview = image }
        }
    }

    private var previewPane: some View {
        ZStack {
            Color.black.opacity(0.25)
            if let preview {
                Image(uiImage: preview).resizable().scaledToFit().padding(16)
            } else if model.photos.isEmpty {
                Text("没有照片").foregroundStyle(.secondary)
            } else {
                ProgressView()
            }
        }
    }

    private var options: some View {
        @Bindable var m = model
        return VStack(spacing: 12) {
            Picker("类型", selection: $mode) {
                Text("单张").tag(ExportMode.single)
                Text("索引图").tag(ExportMode.sheet)
            }
            .pickerStyle(.segmented)

            if mode == .single {
                PanelSection(title: "水印") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 8)], spacing: 8) {
                        ForEach(WatermarkStyle.allCases) { style in
                            let selected = m.exportOptions.style == style
                            Button { m.exportOptions.style = style } label: {
                                VStack(spacing: 4) {
                                    Image(systemName: style.symbol).font(.system(size: 18))
                                    Text(style.rawValue).font(.caption)
                                }
                                .frame(maxWidth: .infinity, minHeight: 56)
                                .foregroundStyle(selected ? Color.accentColor : Color.primary)
                                .background(selected ? Color.accentColor.opacity(0.15) : Color.white.opacity(0.06),
                                            in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                                .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                if m.exportOptions.style == .film135 {
                    Picker("方向", selection: $m.exportOptions.orientation) {
                        ForEach(StripOrientation.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
                if m.photos.count > 1 {
                    Picker("范围", selection: $exportAll) {
                        Text("当前照片").tag(false)
                        Text("全部 \(m.photos.count) 张").tag(true)
                    }
                    .pickerStyle(.segmented)
                }
            } else {
                PanelSection(title: "索引图") {
                    Picker("画幅", selection: $m.exportOptions.sheetFormat) {
                        ForEach(SheetFormat.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    Stepper("每行 \(m.exportOptions.sheetColumns) 张", value: $m.exportOptions.sheetColumns, in: 2...8)
                    Text("全部 \(m.photos.count) 张，各自套用当前的胶片和调整。").font(.footnote).foregroundStyle(.secondary)
                }
            }

            PanelSection(title: "输出") {
                Picker("格式", selection: $m.exportOptions.format) {
                    ForEach(OutputFormat.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                if m.exportOptions.format == .jpeg {
                    ParamSlider(title: "JPEG 质量", value: Binding(
                        get: { Double(m.exportOptions.quality) }, set: { m.exportOptions.quality = Int($0.rounded()) }),
                        range: 50...100, defaultValue: 98)
                }
                if mode == .single {
                    Picker("长边", selection: $m.exportOptions.maxEdge) {
                        Text("原尺寸").tag(0)
                        ForEach([4096, 3072, 2048, 1080], id: \.self) { Text("\($0) px").tag($0) }
                    }
                    .pickerStyle(.menu)
                }
            }

            PanelSection(title: "导出") {
                if m.isExporting {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(m.exportProgress.isEmpty ? "正在处理全尺寸照片…" : "正在处理 \(m.exportProgress)").font(.footnote)
                    }
                } else {
                    GlassTextButton(title: m.exportedURLs.isEmpty ? "渲染并导出" : "重新渲染", systemName: "wand.and.stars", prominent: true) {
                        Task {
                            if mode == .sheet { await m.exportSheet() }
                            else { await m.export(all: exportAll && m.photos.count > 1) }
                        }
                    }
                    .disabled(m.photos.isEmpty)
                }
                if !m.exportedURLs.isEmpty, !m.isExporting {
                    HStack(spacing: 10) {
                        ShareLink(items: m.exportedURLs) { Label("分享 / 存到文件", systemImage: "square.and.arrow.up").font(.subheadline.weight(.semibold)) }
                            .buttonStyle(.bordered)
                        Button { Task { await m.saveToPhotos() } } label: { Label("存入相册", systemImage: "photo.badge.plus").font(.subheadline.weight(.semibold)) }
                            .buttonStyle(.bordered)
                    }
                }
            }
        }
    }
}

struct SettingsView: View {
    @Environment(EditorModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        @Bindable var m = model
        NavigationStack {
            Form {
                Section {
                    LabeledContent("ProRAW", value: "苹果 Core Image RAW")
                    Picker("相机 RAW", selection: $m.cameraRawPipeline) {
                        Text("原版管线（默认）").tag("original")
                        Text("苹果 RAW").tag("apple")
                    }
                } header: { Text("RAW 处理管线") } footer: {
                    Text("原版管线使用 LibRaw 和机身标定；未收录的机身自动使用苹果 RAW。ProRAW 与 Linear DNG 始终使用苹果 Core Image RAW。")
                }
                Section("关于") {
                    Text("LibRaw 0.22.3 · CDDL 1.0。相机 RAW 解码组件，许可与版权声明随应用提供。")
                        .font(.footnote)
                    Text("本应用基于 Macchen666816 的 CXO FilmPack（非商用、源码可见授权），已获作者同意。iOS 版为移植与重写界面，保留原授权与修改说明。")
                        .font(.footnote)
                    Text("胶片边框与索引图的片基、齿孔和边字比例参照 film-index-generator（MIT 许可，Copyright (c) 2026 Judian99，ohsobrkn 的 fork），以 Core Graphics 重新实现。")
                        .font(.footnote)
                }
            }
            .navigationTitle("设置")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("完成") { dismiss() } } }
        }
    }
}
