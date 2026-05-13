import SwiftUI

struct NativeFriendsView: View {
    @ObservedObject var store: NativeFriendStore
    @StateObject private var titleCollapseState = PikoTitleCollapseState()
    @State private var deletingFriend: NativeFriendUser?

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                PikoCollapsingPageHeroHeader(
                    title: NativeAuthLabels.friendsEntry,
                    subtitle: "搜索、申请、管理",
                    metric: "\(store.friends.count) 人",
                    collapseState: titleCollapseState
                )
                TextField(NativeAuthLabels.searchPlaceholder, text: Binding(
                    get: { store.searchQuery },
                    set: { store.search(query: $0) }
                ))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textFieldStyle(.roundedBorder)

                if !store.searchQuery.isEmpty {
                    PikoSectionPanel(title: "搜索结果") {
                        ForEach(store.searchResults) { result in
                            NativeFriendSearchResultRow(result: result, store: store)
                        }
                    }
                }

                PikoSectionPanel(title: "我的好友") {
                    NavigationLink {
                        NativeFriendRequestsView(store: store)
                    } label: {
                        Text(store.incomingRequests.isEmpty ? NativeAuthLabels.friendRequestsTitle : "\(NativeAuthLabels.friendRequestsTitle) \(store.incomingRequests.count)")
                            .font(PikoFont.pill)
                    }
                } content: {
                    if store.friends.isEmpty {
                        Text(NativeAuthLabels.noFriendsHint)
                            .font(PikoFont.rowSubtitle)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(store.friends) { friend in
                            NativeFriendUserRow(friend: friend) {
                                Button(NativeAuthLabels.removeFriendButton, role: .destructive) {
                                    deletingFriend = friend
                                }
                                .font(PikoFont.button)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 68)
            .padding(.bottom, 136)
            .background(alignment: .top) {
                PikoScrollProgressObserver { progress in
                    titleCollapseState.update(progress)
                }
                    .frame(width: 0, height: 0)
            }
        }
        .background(PikoPalette.pageBackground)
        .systemBarBackgrounds()
        .task {
            await store.refreshAll()
        }
        .confirmationDialog(
            "删除好友",
            isPresented: Binding(
                get: { deletingFriend != nil },
                set: { if !$0 { deletingFriend = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let deletingFriend {
                Button(NativeAuthLabels.removeFriendButton, role: .destructive) {
                    store.removeFriend(deletingFriend)
                    self.deletingFriend = nil
                }
            }
        }
    }
}

private struct NativeFriendSearchResultRow: View {
    let result: NativeFriendSearchResult
    @ObservedObject var store: NativeFriendStore

    var body: some View {
        NativeFriendUserRow(friend: result.user) {
            if result.relationship == .none {
                Button(NativeAuthLabels.addFriendButton) {
                    store.sendRequest(to: result.user.userId)
                }
                .font(PikoFont.button)
            } else {
                Text(result.relationship.label)
                    .font(PikoFont.rowSubtitle)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

struct NativeFriendUserRow<Trailing: View>: View {
    let friend: NativeFriendUser
    let trailing: Trailing

    init(friend: NativeFriendUser, @ViewBuilder trailing: () -> Trailing = { EmptyView() }) {
        self.friend = friend
        self.trailing = trailing()
    }

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(friend.displayName)
                    .font(PikoFont.rowTitle)
                    .lineLimit(1)
                    .minimumScaleFactor(0.88)
                    .truncationMode(.tail)
                Text(friend.presence.subtitleLabel)
                    .font(PikoFont.rowSubtitle)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            Spacer(minLength: 12)
            trailing
        }
        .padding(.vertical, 6)
    }
}
