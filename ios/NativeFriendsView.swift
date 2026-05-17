import SwiftUI

struct NativeFriendsView: View {
    @ObservedObject var store: NativeFriendStore
    @State private var deletingFriend: NativeFriendUser?

    var body: some View {
        List {
            Section {
                TextField(NativeAuthLabels.searchPlaceholder, text: Binding(
                    get: { store.searchQuery },
                    set: { store.search(query: $0) }
                ))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            }

            if !store.searchQuery.isEmpty {
                Section("搜索结果") {
                    if store.searchResults.isEmpty {
                        Text(store.isSearching ? "搜索中" : "没有结果")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(store.searchResults) { result in
                            NativeFriendSearchResultRow(result: result, store: store)
                        }
                    }
                }
            }

            Section("我的好友") {
                NavigationLink {
                    NativeFriendRequestsView(store: store)
                } label: {
                    LabeledContent(
                        NativeAuthLabels.friendRequestsTitle,
                        value: store.incomingRequests.isEmpty ? "" : "\(store.incomingRequests.count)"
                    )
                }

                if store.friends.isEmpty {
                    Text(NativeAuthLabels.noFriendsHint)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(store.friends) { friend in
                        NativeFriendUserRow(friend: friend) {
                            Button(NativeAuthLabels.removeFriendButton, role: .destructive) {
                                deletingFriend = friend
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(NativeAuthLabels.friendsEntry)
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
            Button("取消", role: .cancel) {}
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
            } else {
                Text(result.relationship.label)
                    .font(.subheadline)
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
            Label {
                VStack(alignment: .leading, spacing: 3) {
                    Text(friend.displayName)
                        .font(.body)
                        .lineLimit(1)
                    Text(friend.presence.subtitleLabel)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            } icon: {
                Image(systemName: "person.crop.circle")
                    .foregroundStyle(.tint)
            }
            Spacer()
            trailing
        }
        .padding(.vertical, 2)
    }
}
