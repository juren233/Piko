import SwiftUI
import UIKit

struct PikoRootView: View {
    @StateObject private var model = NativePikoModel()

    var body: some View {
        ZStack {
            PikoPalette.pageBackground
                .ignoresSafeArea()

            TabView {
                NativeReceiveView(model: model)
                    .systemBarBackgrounds()
                    .tabItem {
                        Label {
                            Text("接收")
                                .font(PikoFont.tabLabel)
                                .lineLimit(1)
                                .minimumScaleFactor(0.88)
                                .truncationMode(.tail)
                        } icon: {
                            Image(uiImage: LucideTabIcon.download.image)
                        }
                    }

                NativeSendView(model: model)
                    .systemBarBackgrounds()
                    .tabItem {
                        Label {
                            Text("发送")
                                .font(PikoFont.tabLabel)
                                .lineLimit(1)
                                .minimumScaleFactor(0.88)
                                .truncationMode(.tail)
                        } icon: {
                            Image(uiImage: LucideTabIcon.send.image)
                        }
                    }

                NativeSettingsView(model: model)
                    .systemBarBackgrounds()
                    .tabItem {
                        Label {
                            Text("设置")
                                .font(PikoFont.tabLabel)
                                .lineLimit(1)
                                .minimumScaleFactor(0.88)
                                .truncationMode(.tail)
                        } icon: {
                            Image(uiImage: LucideTabIcon.settings.image)
                        }
                    }
            }
            .tint(PikoPalette.accent)
            .background(PikoPalette.pageBackground.ignoresSafeArea())
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
