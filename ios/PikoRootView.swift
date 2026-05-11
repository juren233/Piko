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
        controller.applyImmersiveConfiguration()
        return controller
    }

    func updateUIViewController(_ controller: ImmersiveHostingController<Content>, context: Context) {
        controller.rootView = content
        controller.applyImmersiveConfiguration()
        controller.setNeedsStatusBarAppearanceUpdate()
        controller.setNeedsUpdateOfHomeIndicatorAutoHidden()
        controller.setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
    }
}

final class ImmersiveHostingController<Content: View>: UIHostingController<Content> {
    override func viewDidLoad() {
        super.viewDidLoad()
        applyImmersiveConfiguration()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        applyImmersiveConfiguration()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        applyImmersiveBackground()
    }

    override var prefersHomeIndicatorAutoHidden: Bool {
        true
    }

    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        .all
    }

    func applyImmersiveConfiguration() {
        if #available(iOS 16.4, *) {
            safeAreaRegions = []
        }
        view.isOpaque = true
        edgesForExtendedLayout = .all
        extendedLayoutIncludesOpaqueBars = true
        applyImmersiveBackground()
    }

    private func applyImmersiveBackground() {
        view.backgroundColor = PikoPalette.pageBackgroundUIColor
        view.superview?.backgroundColor = PikoPalette.pageBackgroundUIColor
        view.window?.backgroundColor = PikoPalette.pageBackgroundUIColor
    }
}
