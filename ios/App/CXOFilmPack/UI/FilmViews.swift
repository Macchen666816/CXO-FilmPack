import SwiftUI

struct FilmCard: View {
    @Environment(EditorModel.self) private var model
    let film: FilmEntry
    @State private var thumb: UIImage?
    var showDetail: (FilmEntry) -> Void = { _ in }

    private var selected: Bool { model.selectedFilm?.id == film.id }

    var body: some View {
        Button { model.select(film) } label: {
            VStack(spacing: 6) {
                Color.white.opacity(0.06)
                    .aspectRatio(4.0 / 3.0, contentMode: .fit)
                    .overlay {
                        if let thumb {
                            Image(uiImage: thumb).resizable().scaledToFill()
                        } else {
                            ProgressView().controlSize(.small)
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(selected ? Color.accentColor : Color.clear, lineWidth: 2.5)
                    )
                    .overlay(alignment: .topTrailing) {
                        if model.favorites.contains(film.id) {
                            Image(systemName: "star.fill").font(.caption2).foregroundStyle(.yellow).padding(6)
                        }
                    }
                Text(film.name).font(.caption2.weight(selected ? .semibold : .regular)).lineLimit(1)
            }
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(model.favorites.contains(film.id) ? "取消收藏" : "收藏",
                   systemImage: model.favorites.contains(film.id) ? "star.slash" : "star") { model.toggleFavorite(film) }
            Button("胶片详情", systemImage: "info.circle") { showDetail(film) }
        }
        .task(id: model.photoToken) {
            thumb = nil
            let image = await model.thumbnail(for: film)
            if !Task.isCancelled { thumb = image }
        }
    }
}

struct FilmDetailView: View {
    @Environment(EditorModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let film: FilmEntry

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(film.name).font(.title2.weight(.bold))
                    if let d = model.description(for: film) {
                        let rows: [(String, String?)] = [("厂商", d.manufacturer), ("类型", d.type), ("发布", d.introduced), ("停产", d.discontinued)]
                        ForEach(rows.filter { ($0.1 ?? "").isEmpty == false }, id: \.0) { row in
                            HStack { Text(row.0).foregroundStyle(.secondary); Spacer(); Text(row.1 ?? "") }.font(.subheadline)
                        }
                        if let text = d.description?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty {
                            Text(text).font(.body).padding(.top, 6)
                        }
                    } else {
                        Text("暂无介绍").foregroundStyle(.secondary)
                    }
                    if film.experimental { Text("实验性胶片").font(.footnote).foregroundStyle(.orange) }
                }
                .padding()
            }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("完成") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Searchable, categorised film grid (the "Presets" panel).
struct FilmLibraryView: View {
    @Environment(EditorModel.self) private var model
    @State private var query = ""
    @State private var category = "全部"
    @State private var detail: FilmEntry?
    var columns: Int = 2

    private var categories: [String] { ["全部", "收藏"] + (model.runtime?.categories ?? []) }

    private var filtered: [FilmEntry] {
        guard let runtime = model.runtime else { return [] }
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return runtime.films.filter { film in
            switch category {
            case "全部": break
            case "收藏": if !model.favorites.contains(film.id) { return false }
            default: if film.category != category { return false }
            }
            return q.isEmpty || film.name.lowercased().contains(q) || film.token.lowercased().contains(q)
        }
    }

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("搜索胶片", text: $query).textInputAutocapitalization(.never).autocorrectionDisabled()
                if !query.isEmpty {
                    Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }.buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .glass()

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(categories, id: \.self) { c in
                        Button { category = c } label: {
                            Text(c).font(.footnote.weight(.semibold))
                                .padding(.horizontal, 12).padding(.vertical, 7)
                                .background(category == c ? Color.accentColor : Color.white.opacity(0.08), in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            ScrollView {
                if filtered.isEmpty {
                    Text(category == "收藏" ? "长按胶片可以收藏" : "没有匹配的胶片")
                        .foregroundStyle(.secondary).padding(.top, 40)
                } else {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: columns), spacing: 12) {
                        ForEach(filtered) { film in
                            FilmCard(film: film, showDetail: { detail = $0 })
                        }
                    }
                    .padding(.bottom, 8)
                }
            }
        }
        .sheet(item: $detail) { [model] film in
            FilmDetailView(film: film).environment(model)
        }
    }
}

/// Horizontal quick-switch strip.
struct FilmStrip: View {
    @Environment(EditorModel.self) private var model
    var cardWidth: CGFloat = 92

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 10) {
                    ForEach(model.runtime?.films ?? []) { film in
                        FilmCard(film: film).frame(width: cardWidth).id(film.id)
                    }
                }
                .padding(.horizontal, 4)
            }
            // A horizontal ScrollView is greedy vertically; pin it to one row of cards.
            .frame(height: cardWidth * 0.75 + 30)
            .onChange(of: model.selectedFilm) { _, film in
                if let film { withAnimation { proxy.scrollTo(film.id, anchor: .center) } }
            }
        }
    }
}

/// Lightroom-style strip of every open photograph (shows each photo with its own look).
struct PhotoStrip: View {
    @Environment(EditorModel.self) private var model
    var height: CGFloat = 72

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(model.photos) { photo in
                        PhotoThumb(photo: photo, height: height).id(photo.id)
                    }
                    Button { model.showPhotoPicker = true } label: {
                        Image(systemName: "plus").font(.title3.weight(.semibold))
                            .frame(width: height, height: height)
                            .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 4)
            }
            .frame(height: height)
            .onChange(of: model.currentID) { _, id in
                if let id { withAnimation { proxy.scrollTo(id, anchor: .center) } }
            }
        }
    }
}

struct PhotoThumb: View {
    @Environment(EditorModel.self) private var model
    let photo: Photo
    let height: CGFloat

    var body: some View {
        let selected = model.currentID == photo.id
        Button { model.selectPhoto(photo.id) } label: {
            Color.white.opacity(0.06)
                .frame(width: height * 4 / 3, height: height)
                .overlay {
                    if let image = photo.stripImage ?? photo.originalThumb {
                        Image(uiImage: image).resizable().scaledToFill()
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(selected ? Color.accentColor : Color.clear, lineWidth: 2.5)
                )
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button("复制设置", systemImage: "doc.on.doc") { model.copySettings(from: photo.id) }
            Button("粘贴设置", systemImage: "doc.on.clipboard") { model.pasteSettings(to: photo.id) }
                .disabled(model.clipboard == nil)
            if model.photos.count > 1 {
                Button("同步到所有照片", systemImage: "arrow.triangle.2.circlepath") { model.syncAll(from: photo.id) }
            }
            Button("移除", systemImage: "trash", role: .destructive) { model.removePhoto(photo.id) }
        }
    }
}
