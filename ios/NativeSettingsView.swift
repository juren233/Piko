import SwiftUI

struct NativeSettingsView: View {
    @ObservedObject var model: NativePikoModel
    let onScrollProgressChange: (CGFloat) -> Void
    @StateObject private var titleCollapseState = PikoTitleCollapseState()

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                PikoCollapsingPageHeroHeader(
                    title: "设置",
                    subtitle: "设备、账号和传输偏好",
                    metric: "本机",
                    collapseState: titleCollapseState
                )
                PikoSectionPanel(title: "传输") {
                    NativeSettingsRow(title: "自动接收", value: "可信设备")
                    VStack(alignment: .leading, spacing: 10) {
                        Text("图片视频保存位置")
                            .font(PikoFont.rowSubtitle)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.tail)
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
                        .pickerStyle(.segmented)
                    }
                    .padding(.vertical, 6)
                    NativeSettingsRow(title: "传输策略", value: "局域网优先")
                }
                PikoSectionPanel(title: NativeAuthLabels.accountSectionTitle) {
                    NativeFriendSettingsEntry(authStore: model.authStore, friendStore: model.friendStore)
                    NativeAuthSection(authStore: model.authStore)
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 68)
            .padding(.bottom, 136)
            .background(alignment: .top) {
                PikoScrollProgressObserver { progress in
                    titleCollapseState.update(progress)
                    onScrollProgressChange(progress)
                }
                    .frame(width: 0, height: 0)
            }
        }
        .background(PikoPalette.pageBackground)
        .systemBarBackgrounds()
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
                NativeSettingsRow(
                    title: NativeAuthLabels.friendsEntry,
                    value: friendStore.incomingRequests.isEmpty
                        ? "\(friendStore.friends.count) 人"
                        : "\(friendStore.friends.count) 人 · \(friendStore.incomingRequests.count) 个申请"
                )
            }
            .buttonStyle(.plain)
        case .loading, .unauthenticated:
            NativeSettingsRow(title: NativeAuthLabels.friendsEntry, value: NativeAuthLabels.friendsLoginHint)
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
        VStack(alignment: .leading, spacing: 10) {
            NativeSettingsRow(title: "登录方式", value: "邮箱账号")
            switch authStore.state {
            case .authenticated(let user):
                NativeSettingsRow(title: NativeAuthLabels.email, value: user.email)
                NativeSettingsRow(title: NativeAuthLabels.username, value: "@\(user.username)")
                NativeSettingsRow(
                    title: NativeAuthLabels.nickname,
                    value: user.nickname ?? NativeAuthLabels.unsetNicknamePlaceholder
                )
                Button(role: .destructive) {
                    Task { await authStore.logout() }
                } label: {
                    Text(NativeAuthLabels.signOut)
                        .font(PikoFont.button)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .padding(.top, 2)
            case .loading, .unauthenticated:
                Button {
                    mode = .login
                } label: {
                    Text(NativeAuthLabels.signInOrSignUp)
                        .font(PikoFont.rowTitle)
                        .foregroundStyle(PikoPalette.accent)
                        .lineLimit(1)
                        .minimumScaleFactor(0.88)
                        .truncationMode(.tail)
                        .padding(.vertical, 6)
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

private struct NativeSettingsRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack {
            Text(title)
                .font(PikoFont.rowSubtitle)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer()
            Text(value)
                .font(PikoFont.settingsValue)
                .lineLimit(1)
                .minimumScaleFactor(0.88)
                .truncationMode(.tail)
        }
        .padding(.vertical, 6)
    }
}
