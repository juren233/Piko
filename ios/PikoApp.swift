import SwiftUI
import UIKit

@main
struct PikoApp: App {
    init() {
        let tabBarAppearance = UITabBarAppearance()
        tabBarAppearance.configureWithTransparentBackground()

        UITabBar.appearance().standardAppearance = tabBarAppearance
        UITabBar.appearance().scrollEdgeAppearance = tabBarAppearance
        UITabBar.appearance().isTranslucent = true
        UITabBar.appearance().shadowImage = UIImage()

        let navigationBarAppearance = UINavigationBarAppearance()
        navigationBarAppearance.configureWithTransparentBackground()
        UINavigationBar.appearance().standardAppearance = navigationBarAppearance
        UINavigationBar.appearance().scrollEdgeAppearance = navigationBarAppearance
        UINavigationBar.appearance().compactAppearance = navigationBarAppearance
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
