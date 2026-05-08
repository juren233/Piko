import ComposeApp
import SwiftUI
import UIKit

@main
struct PikoIOSApp: App {
    var body: some Scene {
        WindowGroup {
            TabView {
                ComposeView(tabName: "Receive")
                    .tabItem {
                        Label("接收", systemImage: "tray.and.arrow.down")
                    }

                ComposeView(tabName: "Send")
                    .tabItem {
                        Label("发送", systemImage: "paperplane")
                    }

                ComposeView(tabName: "Settings")
                    .tabItem {
                        Label("设置", systemImage: "gearshape")
                    }
            }
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    let tabName: String

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(tabName: tabName)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
