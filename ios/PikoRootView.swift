import SwiftUI
import UIKit

struct PikoRootView: View {
    private enum Tab: Hashable {
        case receive
        case send
        case settings

        var title: String {
            switch self {
            case .receive:
                return "Piko"
            case .send:
                return "发送"
            case .settings:
                return "设置"
            }
        }
    }

    @StateObject private var model = NativePikoModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var selection: Tab = .receive
    @State private var receiveTopBarProgress: CGFloat = 0
    @State private var sendTopBarProgress: CGFloat = 0
    @State private var settingsTopBarProgress: CGFloat = 0

    private var currentTopBarProgress: CGFloat {
        switch selection {
        case .receive:
            return receiveTopBarProgress
        case .send:
            return sendTopBarProgress
        case .settings:
            return settingsTopBarProgress
        }
    }

    var body: some View {
        ZStack {
            PikoPalette.pageBackground
                .ignoresSafeArea()

            TabView(selection: $selection) {
                NativeReceiveView(model: model) { progress in
                    receiveTopBarProgress = progress
                }
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
                    .tag(Tab.receive)

                NativeSendView(model: model) { progress in
                    sendTopBarProgress = progress
                }
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
                    .tag(Tab.send)

                NavigationStack {
                    NativeSettingsView(model: model) { progress in
                        settingsTopBarProgress = progress
                    }
                }
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
                    .tag(Tab.settings)
            }
            .tint(PikoPalette.accent)
            .background(PikoPalette.pageBackground.ignoresSafeArea())
            .overlay(alignment: .top) {
                PikoCollapsingTopBar(title: selection.title, progress: currentTopBarProgress)
            }
        }
        .onAppear {
            model.startPresence()
            model.startDiscovery()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                model.refreshFriendsPresence()
            }
        }
        .onChange(of: selection) { tab in
            if tab == .send {
                model.refreshFriendsPresence()
            }
        }
    }
}

private struct PikoCollapsingTopBar: View {
    let title: String
    let progress: CGFloat

    var body: some View {
        GeometryReader { proxy in
            let barHeight = proxy.safeAreaInsets.top + 52
            let blurHeight = proxy.safeAreaInsets.top + 86
            ZStack(alignment: .top) {
                PikoGradientBlurView(
                    style: .systemUltraThinMaterial,
                    tintColor: PikoPalette.pageBackgroundUIColor.withAlphaComponent(0.18)
                )
                    .opacity(progress)
                    .frame(height: blurHeight)

                Text(title)
                    .font(.headline.weight(.bold))
                    .lineLimit(1)
                    .opacity(progress)
                    .frame(maxWidth: .infinity)
                    .padding(.top, proxy.safeAreaInsets.top + 15)
            }
            .frame(width: proxy.size.width, height: barHeight, alignment: .top)
            .ignoresSafeArea(edges: .top)
            .allowsHitTesting(false)
        }
        .frame(height: 140, alignment: .top)
        .allowsHitTesting(false)
    }
}

private struct PikoGradientBlurView: UIViewRepresentable {
    let style: UIBlurEffect.Style
    let tintColor: UIColor

    func makeUIView(context: Context) -> PikoGradientBlurContainerView {
        let view = PikoGradientBlurContainerView()
        view.configure(style: style, tintColor: tintColor)
        return view
    }

    func updateUIView(_ view: PikoGradientBlurContainerView, context: Context) {
        view.configure(style: style, tintColor: tintColor)
    }
}

private final class PikoGradientBlurContainerView: UIView {
    private let effectView = UIVisualEffectView(effect: nil)
    private let tintView = UIView()
    private let gradientMask = CAGradientLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        effectView.isUserInteractionEnabled = false
        tintView.isUserInteractionEnabled = false
        addSubview(effectView)
        addSubview(tintView)
        layer.mask = gradientMask
        updateGradientMask()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        effectView.frame = bounds
        tintView.frame = bounds
        gradientMask.frame = bounds
    }

    func configure(style: UIBlurEffect.Style, tintColor: UIColor) {
        effectView.effect = UIBlurEffect(style: style)
        tintView.backgroundColor = tintColor
    }

    private func updateGradientMask() {
        gradientMask.startPoint = CGPoint(x: 0.5, y: 0)
        gradientMask.endPoint = CGPoint(x: 0.5, y: 1)
        gradientMask.locations = [0, 0.58, 0.82, 1]
        gradientMask.colors = [
            UIColor.black.cgColor,
            UIColor.black.cgColor,
            UIColor.black.withAlphaComponent(0.28).cgColor,
            UIColor.black.withAlphaComponent(0).cgColor
        ]
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
        view.isOpaque = true
        applyImmersiveBackground()
    }

    private func applyImmersiveBackground() {
        view.backgroundColor = PikoPalette.pageBackgroundUIColor
        view.superview?.backgroundColor = PikoPalette.pageBackgroundUIColor
        view.window?.backgroundColor = PikoPalette.pageBackgroundUIColor
    }
}
