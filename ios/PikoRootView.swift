import SwiftUI
import UIKit

struct PikoRootView: View {
    @StateObject private var model = NativePikoModel()

    var body: some View {
        ZStack {
            PikoPalette.pageGradient
                .ignoresSafeArea()

            TabView {
                NativeReceiveView(model: model)
                    .systemBarBackgrounds()
                    .tabItem {
                        Label {
                            Text("接收")
                        } icon: {
                            Image(uiImage: LucideTabIcon.download.image)
                        }
                    }

                NativeSendView(model: model)
                    .systemBarBackgrounds()
                    .tabItem {
                        Label {
                            Text("发送")
                        } icon: {
                            Image(uiImage: LucideTabIcon.send.image)
                        }
                    }

                NativeSettingsView(model: model)
                    .systemBarBackgrounds()
                    .tabItem {
                        Label {
                            Text("设置")
                        } icon: {
                            Image(uiImage: LucideTabIcon.settings.image)
                        }
                    }
            }
            .tint(PikoPalette.accent)
            .background(PikoPalette.pageGradient.ignoresSafeArea())
        }
        .onAppear {
            model.startPresence()
            model.startDiscovery()
        }
    }
}

struct ImmersiveRootView<Content: View>: UIViewControllerRepresentable {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    func makeUIViewController(context: Context) -> ImmersiveHostingController<Content> {
        let controller = ImmersiveHostingController(rootView: content)
        controller.view.backgroundColor = PikoPalette.surfaceUIColor
        controller.view.isOpaque = true
        controller.edgesForExtendedLayout = .all
        controller.extendedLayoutIncludesOpaqueBars = true
        return controller
    }

    func updateUIViewController(_ controller: ImmersiveHostingController<Content>, context: Context) {
        controller.rootView = content
        controller.view.backgroundColor = PikoPalette.surfaceUIColor
        controller.setNeedsStatusBarAppearanceUpdate()
        controller.setNeedsUpdateOfHomeIndicatorAutoHidden()
        controller.setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
    }
}

final class ImmersiveHostingController<Content: View>: UIHostingController<Content> {
    override var prefersHomeIndicatorAutoHidden: Bool {
        true
    }

    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        .all
    }
}
