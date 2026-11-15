import SwiftUI

// Liquid Glass on iOS 26, translucent material before that.
extension View {
    @ViewBuilder
    func glass<S: Shape>(in shape: S, interactive: Bool = false, tint: Color? = nil) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(Self.makeGlass(interactive: interactive, tint: tint), in: shape)
        } else {
            self.background(.ultraThinMaterial, in: shape)
                .overlay(shape.stroke(Color.white.opacity(0.12), lineWidth: 0.5))
        }
    }

    func glass(interactive: Bool = false) -> some View {
        glass(in: Capsule(), interactive: interactive)
    }

    @available(iOS 26.0, *)
    private static func makeGlass(interactive: Bool, tint: Color?) -> Glass {
        var g = Glass.regular
        if let tint { g = g.tint(tint) }
        if interactive { g = g.interactive() }
        return g
    }

    @ViewBuilder
    func glassSheetBackground() -> some View {
        if #available(iOS 26.0, *) { self } else { self.presentationBackground(.ultraThinMaterial) }
    }
}

struct GlassGroup<Content: View>: View {
    var spacing: CGFloat = 12
    @ViewBuilder var content: () -> Content

    var body: some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { content() }
        } else {
            content()
        }
    }
}

struct GlassIconButton: View {
    let systemName: String
    var prominent = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 44, height: 44)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .glass(in: Circle(), interactive: true, tint: prominent ? Color.accentColor : nil)
    }
}

struct GlassTextButton: View {
    let title: String
    var systemName: String?
    var prominent = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemName { Image(systemName: systemName) }
                Text(title)
            }
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 16).padding(.vertical, 11)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .glass(in: Capsule(), interactive: true, tint: prominent ? Color.accentColor : nil)
    }
}

struct ParamSlider: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    var step: Double = 1
    var defaultValue: Double = 0
    var format: (Double) -> String = { String(Int($0.rounded())) }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Text(title)
                Spacer()
                Text(format(value)).monospacedDigit().foregroundStyle(.secondary)
                // Touch has no double-click: a visible reset button appears once the value moves off default.
                Button { value = defaultValue } label: {
                    Image(systemName: "arrow.counterclockwise").font(.footnote.weight(.semibold))
                        .frame(width: 32, height: 32).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .opacity(abs(value - defaultValue) > 0.0001 ? 1 : 0)
                .disabled(abs(value - defaultValue) <= 0.0001)
            }
            .font(.footnote)
            Slider(value: $value, in: range, step: step)
                .frame(minHeight: 44)   // comfortable finger target
        }
    }
}

struct PanelSection<Content: View>: View {
    let title: String
    var action: (title: String, run: () -> Void)?
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(title).font(.subheadline.weight(.semibold))
                Spacer()
                if let action { Button(action.title, action: action.run).font(.footnote) }
            }
            content()
        }
        .padding(14)
        .background(Color.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}
