import SwiftUI

struct PikoRootView: View {
    private enum Tab: Hashable {
        case receive
        case send
        case settings
    }

    @StateObject private var model = NativePikoModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var selection: Tab = .receive

    private var receiveConfirmationPresented: Binding<Bool> {
        Binding(
            get: { model.activeReceive?.requiresConfirmation == true },
            set: { _ in }
        )
    }

    var body: some View {
        TabView(selection: $selection) {
            NavigationStack {
                NativeReceiveView(model: model) { _ in }
            }
            .tabItem {
                Label("接收", systemImage: "tray.and.arrow.down")
            }
            .tag(Tab.receive)

            NavigationStack {
                NativeSendView(model: model) { _ in }
            }
            .tabItem {
                Label("发送", systemImage: "paperplane")
            }
            .tag(Tab.send)

            NavigationStack {
                NativeSettingsView(model: model) { _ in }
            }
            .tabItem {
                Label("设置", systemImage: "gearshape")
            }
            .tag(Tab.settings)
        }
        .tint(.accentColor)
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
        .alert("确认接收", isPresented: receiveConfirmationPresented) {
            Button("拒绝", role: .cancel) {
                model.cancelReceiveTransfer()
            }
            Button("接收") {
                model.acceptReceiveTransfer()
            }
        } message: {
            Text(model.activeReceive?.receiveConfirmationMessage ?? "")
        }
    }
}
