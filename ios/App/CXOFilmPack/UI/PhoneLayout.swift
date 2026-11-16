import SwiftUI

struct PhoneLayout: View {
    @Environment(EditorModel.self) private var model

    var body: some View {
        ZStack {
            PhotoCanvas()
            VStack(spacing: 0) {
                topBar
                MessageToast().padding(.top, 8)
                Spacer()
                bottomPanel
            }
            .padding(.horizontal, 12)
        }
    }

    private var topBar: some View {
        GlassGroup {
            HStack(spacing: 10) {
                OpenMenu()
                Spacer(minLength: 0)
                HStack(spacing: 8) {
                    Text(model.selectedFilm?.name ?? "").font(.subheadline.weight(.semibold)).lineLimit(1)
                    if model.isRendering { ProgressView().controlSize(.mini) }
                }
                .padding(.horizontal, 16).frame(height: 44).glass()
                Spacer(minLength: 0)
                CompareButton()
                GlassIconButton(systemName: "square.and.arrow.up", prominent: true) { model.sheet = .export }
            }
        }
    }

    private var bottomPanel: some View {
        VStack(spacing: 10) {
            VStack(spacing: 8) {
                if model.photos.count > 1 { PhotoStrip(height: 56) }
                FilmStrip(cardWidth: 88)
                HStack(spacing: 10) {
                    Text("强度").font(.footnote.weight(.medium))
                    Slider(value: model.intBinding(\.strength), in: 0...200, step: 1)
                    Text("\(model.look.strength)").font(.footnote).monospacedDigit().frame(width: 34, alignment: .trailing)
                }
                .padding(.horizontal, 6)
            }
            .padding(12)
            .glass(in: RoundedRectangle(cornerRadius: 28, style: .continuous))

            GlassGroup {
                HStack(spacing: 8) {
                    tool("film.stack", "胶片库", .library)
                    tool("slider.horizontal.3", "调整", .adjust)
                    tool("circle.dotted", "颗粒", .grain)
                    tool("square.dashed", "边缘", .edge)
                    tool("gearshape", "设置", .settings)
                }
            }
        }
        .padding(.bottom, 6)
    }

    private func tool(_ icon: String, _ title: String, _ kind: SheetKind) -> some View {
        Button { model.sheet = kind } label: {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.system(size: 18, weight: .medium))
                Text(title).font(.caption2)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 9)
            .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        }
        .buttonStyle(.plain)
        .glass(in: RoundedRectangle(cornerRadius: 22, style: .continuous), interactive: true)
    }
}
