import SwiftUI

/// Lightroom-for-iPad style: full-bleed canvas, a vertical tool rail on the right that opens a side panel,
/// a quick film strip along the bottom, and a slim top bar.
struct PadLayout: View {
    enum Tab: String, CaseIterable, Identifiable {
        case films = "胶片", customize = "调整"
        var id: String { rawValue }
    }

    @Environment(EditorModel.self) private var model
    @State private var tab: Tab = .films
    @State private var showPanel = true
    @State private var showStrip = true

    var body: some View {
        ZStack {
            PhotoCanvas()
            VStack(spacing: 0) {
                topBar
                MessageToast().padding(.top, 8)
                Spacer(minLength: 0)
                if showStrip {
                    PhotoStrip(height: 76)
                        .padding(12)
                        .glass(in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                        .padding(.trailing, rightInset)
                        .padding(.bottom, 8)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .padding(.horizontal, 16)

            HStack(alignment: .center, spacing: 12) {
                Spacer(minLength: 0)
                if showPanel { panel.transition(.move(edge: .trailing).combined(with: .opacity)) }
            }
            .padding(.trailing, 16)
            .padding(.top, 76).padding(.bottom, showStrip ? 150 : 24)
        }
        .animation(.smooth(duration: 0.25), value: showPanel)
        .animation(.smooth(duration: 0.25), value: showStrip)
    }

    private var rightInset: CGFloat { showPanel ? 352 : 0 }

    private var topBar: some View {
        GlassGroup {
            HStack(spacing: 10) {
                OpenMenu()
                Spacer(minLength: 0)
                HStack(spacing: 10) {
                    Text(model.selectedFilm?.name ?? "").font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text("\(model.look.strength)%").font(.footnote).foregroundStyle(.secondary).monospacedDigit()
                    if model.isRendering { ProgressView().controlSize(.mini) }
                }
                .padding(.horizontal, 18).frame(height: 44).glass()
                Spacer(minLength: 0)
                GlassIconButton(systemName: showStrip ? "rectangle.bottomhalf.inset.filled" : "rectangle") { showStrip.toggle() }
                CompareButton()
                GlassIconButton(systemName: "sidebar.right") { showPanel.toggle() }
                GlassIconButton(systemName: "square.and.arrow.up", prominent: true) { model.sheet = .export }
                GlassIconButton(systemName: "gearshape") { model.sheet = .settings }
            }
        }
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker("", selection: $tab) {
                ForEach(Tab.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            switch tab {
            case .films:
                FilmLibraryView(columns: 2)
            case .customize:
                ScrollView {
                    CustomizeView().padding(.bottom, 12)
                }
                .scrollIndicators(.hidden)
            }
        }
        .padding(14)
        .frame(width: 340)
        .frame(maxHeight: .infinity)
        .glass(in: RoundedRectangle(cornerRadius: 32, style: .continuous))
    }
}
