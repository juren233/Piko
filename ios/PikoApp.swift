import SwiftUI

@main
struct PikoApp: App {
    var body: some Scene {
        WindowGroup {
            PikoRootView()
        }
    }
}

extension View {
    @ViewBuilder
    func systemBarBackgrounds() -> some View {
        self
    }
}
