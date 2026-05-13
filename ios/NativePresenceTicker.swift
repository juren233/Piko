import Foundation

@MainActor
final class NativePresenceTicker {
    init(authStore: NativeAuthStore, api: NativeFriendApiClient) {
    }

    func start(friendStore: NativeFriendStore) {
        Task {
            await friendStore.refreshAll()
        }
    }

    func stop() {
    }
}
