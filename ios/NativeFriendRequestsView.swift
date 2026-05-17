import SwiftUI

struct NativeFriendRequestsView: View {
    @ObservedObject var store: NativeFriendStore
    @State private var selectedSegment = 0

    var body: some View {
        List {
            Section {
                Picker(NativeAuthLabels.friendRequestsTitle, selection: $selectedSegment) {
                    Text("收到的 \(store.incomingRequests.count)").tag(0)
                    Text("我发出的 \(store.outgoingRequests.count)").tag(1)
                }
                .pickerStyle(.segmented)
            }
            Section(selectedSegment == 0 ? "收到的" : "我发出的") {
                ForEach(selectedSegment == 0 ? store.incomingRequests : store.outgoingRequests) { request in
                    NativeFriendRequestRow(request: request, store: store)
                }
            }
        }
        .navigationTitle(NativeAuthLabels.friendRequestsTitle)
        .task {
            await store.refreshAll()
        }
    }
}

private struct NativeFriendRequestRow: View {
    let request: NativeFriendRequest
    @ObservedObject var store: NativeFriendStore

    var body: some View {
        NativeFriendUserRow(friend: request.otherUser) {
            if request.status != .pending {
                Text(request.status.label)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else if request.direction == .incoming {
                HStack {
                    Button(NativeAuthLabels.acceptButton) { store.accept(request) }
                    Button(NativeAuthLabels.rejectButton, role: .destructive) { store.reject(request) }
                }
            } else {
                Button(NativeAuthLabels.cancelRequestButton, role: .destructive) {
                    store.cancel(request)
                }
            }
        }
    }
}
