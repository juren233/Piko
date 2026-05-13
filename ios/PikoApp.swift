import SwiftUI
import UIKit

@main
struct PikoApp: App {
    init() {
        let tabBarAppearance = UITabBarAppearance()
        tabBarAppearance.configureWithTransparentBackground()
        tabBarAppearance.backgroundColor = .clear
        tabBarAppearance.shadowColor = .clear

        UITabBar.appearance().standardAppearance = tabBarAppearance
        UITabBar.appearance().scrollEdgeAppearance = tabBarAppearance
        UITabBar.appearance().isTranslucent = true

        let navigationBarAppearance = UINavigationBarAppearance()
        navigationBarAppearance.configureWithTransparentBackground()
        navigationBarAppearance.backgroundColor = .clear
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
            toolbarBackground(.hidden, for: .navigationBar, .tabBar)
        } else {
            self
        }
    }
}
