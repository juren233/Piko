import SwiftUI

struct NativeSettingsView: View {
    @ObservedObject var model: NativePikoModel
    let onScrollProgressChange: (CGFloat) -> Void

    var body: some View {
        Form {
            Section("传输") {
                LabeledContent("自动接收", value: "可信设备")
                Picker(
                    "图片视频保存位置",
                    selection: Binding(
                        get: { model.mediaSaveLocation },
                        set: { model.updateMediaSaveLocation($0) }
                    )
                ) {
                    ForEach(NativeMediaSaveLocation.allCases) { location in
                        Text(location.label).tag(location)
                    }
                }
                LabeledContent("传输策略", value: "局域网优先")
            }

            Section(NativeAuthLabels.accountSectionTitle) {
                NativeFriendSettingsEntry(authStore: model.authStore, friendStore: model.friendStore)
                NativeAuthSection(authStore: model.authStore)
            }
        }
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            onScrollProgressChange(1)
        }
    }
}

private struct NativeFriendSettingsEntry: View {
    @ObservedObject var authStore: NativeAuthStore
    @ObservedObject var friendStore: NativeFriendStore

    var body: some View {
        switch authStore.state {
        case .authenticated:
            NavigationLink {
                NativeFriendsView(store: friendStore)
            } label: {
                LabeledContent(
                    NativeAuthLabels.friendsEntry,
                    value: friendStore.incomingRequests.isEmpty
                        ? "\(friendStore.friends.count) 人"
                        : "\(friendStore.friends.count) 人 · \(friendStore.incomingRequests.count) 个申请"
                )
            }
        case .loading, .unauthenticated:
            LabeledContent(NativeAuthLabels.friendsEntry, value: NativeAuthLabels.friendsLoginHint)
                .foregroundStyle(.secondary)
        }
    }
}

private enum NativeAuthMode: Identifiable {
    case login
    case register

    var id: String {
        switch self {
        case .login:
            return "login"
        case .register:
            return "register"
        }
    }
}

private struct NativeAuthSection: View {
    @ObservedObject var authStore: NativeAuthStore
    @State private var mode: NativeAuthMode?

    var body: some View {
        Group {
            LabeledContent("登录方式", value: "邮箱账号")
            switch authStore.state {
            case .authenticated(let user):
                LabeledContent(NativeAuthLabels.email, value: user.email)
                LabeledContent(NativeAuthLabels.username, value: "@\(user.username)")
                LabeledContent(
                    NativeAuthLabels.nickname,
                    value: user.nickname ?? NativeAuthLabels.unsetNicknamePlaceholder
                )
                Button(NativeAuthLabels.signOut, role: .destructive) {
                    Task { await authStore.logout() }
                }
            case .loading, .unauthenticated:
                Button(NativeAuthLabels.signInOrSignUp) {
                    mode = .login
                }
                .disabled(authStore.state == .loading)
            }
        }
        .sheet(item: $mode) { currentMode in
            switch currentMode {
            case .login:
                NativeLoginView(authStore: authStore) {
                    mode = .register
                }
            case .register:
                NativeRegisterView(authStore: authStore) {
                    mode = .login
                }
            }
        }
        .onChange(of: authStore.state) { state in
            if case .authenticated = state {
                mode = nil
            }
        }
    }
}
