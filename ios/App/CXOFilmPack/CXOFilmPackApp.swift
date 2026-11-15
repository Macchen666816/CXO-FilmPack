import SwiftUI

@main
struct CXOFilmPackApp: App {
    @State private var model = EditorModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
        }
    }
}
