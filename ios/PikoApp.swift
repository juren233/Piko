import SwiftUI
import UIKit

@main
struct PikoApp: App {
    init() {
        let tabBarAppearance = UITabBarAppearance()
        tabBarAppearance.configureWithDefaultBackground()
        tabBarAppearance.backgroundEffect = UIBlurEffect(style: .systemUltraThinMaterial)
        tabBarAppearance.backgroundColor = PikoPalette.pageBackgroundUIColor.withAlphaComponent(0.28)
        tabBarAppearance.shadowColor = UIColor.separator.withAlphaComponent(0.18)

        UITabBar.appearance().standardAppearance = tabBarAppearance
        UITabBar.appearance().scrollEdgeAppearance = tabBarAppearance
        UITabBar.appearance().isTranslucent = true

        let navigationBarAppearance = UINavigationBarAppearance()
        navigationBarAppearance.configureWithDefaultBackground()
        navigationBarAppearance.backgroundEffect = UIBlurEffect(style: .systemUltraThinMaterial)
        navigationBarAppearance.backgroundColor = PikoPalette.pageBackgroundUIColor.withAlphaComponent(0.18)
        navigationBarAppearance.shadowColor = .clear

        let navigationBarScrollEdgeAppearance = UINavigationBarAppearance()
        navigationBarScrollEdgeAppearance.configureWithTransparentBackground()
        navigationBarScrollEdgeAppearance.backgroundColor = .clear
        navigationBarScrollEdgeAppearance.shadowColor = .clear

        UINavigationBar.appearance().standardAppearance = navigationBarAppearance
        UINavigationBar.appearance().scrollEdgeAppearance = navigationBarScrollEdgeAppearance
        UINavigationBar.appearance().compactAppearance = navigationBarAppearance
        UINavigationBar.appearance().prefersLargeTitles = true
        UINavigationBar.appearance().isTranslucent = true
    }

    var body: some Scene {
        WindowGroup {
            ImmersiveRootView {
                PikoRootView()
            }
            .ignoresSafeArea()
        }
    }
}

extension View {
    @ViewBuilder
    func systemBarBackgrounds() -> some View {
        if #available(iOS 16.0, *) {
            toolbarBackground(.ultraThinMaterial, for: .tabBar)
                .toolbarBackground(.visible, for: .tabBar)
        } else {
            self
        }
    }
}
