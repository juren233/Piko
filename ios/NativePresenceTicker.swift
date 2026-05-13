import Foundation

@MainActor
final class NativePresenceTicker {
    private weak var authStore: NativeAuthStore?
    private let api: NativeFriendApiClient
    private var timer: DispatchSourceTimer?

    init(authStore: NativeAuthStore, api: NativeFriendApiClient) {
        self.authStore = authStore
        self.api = api
    }

    func start(friendStore: NativeFriendStore) {
        stop()
        Task {
            await friendStore.heartbeat()
            await friendStore.refreshAll()
        }
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
        timer.schedule(deadline: .now() + 30, repeating: 30)
        timer.setEventHandler { [weak self, weak friendStore] in
            guard let self, let friendStore, self.authStore?.currentToken() != nil else {
                self?.stop()
                return
            }
            Task { @MainActor in
                await friendStore.heartbeat()
                await friendStore.refreshAll()
            }
        }
        self.timer = timer
        timer.resume()
    }

    func stop() {
        timer?.cancel()
        timer = nil
    }
}
