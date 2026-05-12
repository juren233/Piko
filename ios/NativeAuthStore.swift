import Foundation

@MainActor
final class NativeAuthStore: ObservableObject {
    @Published private(set) var state: NativeAuthState = .unauthenticated
    @Published private(set) var lastError: NativeAccountError?

    private let api: NativeAccountApiClient
    private let tokenStore: NativeAuthTokenStore

    init(
        api: NativeAccountApiClient = NativeAccountApiClient(),
        tokenStore: NativeAuthTokenStore = NativeAuthTokenStore()
    ) {
        self.api = api
        self.tokenStore = tokenStore
    }

    func bootstrap() async {
        guard let token = tokenStore.load() else {
            state = .unauthenticated
            return
        }
        state = .loading
        switch await api.me(token: token) {
        case .success(let user):
            state = .authenticated(user)
        case .failure(let error):
            if error == .sessionExpired {
                tokenStore.clear()
            }
            lastError = error
            state = .unauthenticated
        }
    }

    func register(email: String, password: String, username: String, nickname: String?) async {
        state = .loading
        switch await api.register(email: email, password: password, username: username, nickname: nickname) {
        case .success(let success):
            tokenStore.save(success.token)
            lastError = nil
            state = .authenticated(success.user)
        case .failure(let error):
            lastError = error
            state = .unauthenticated
        }
    }

    func login(email: String, password: String) async {
        state = .loading
        switch await api.login(email: email, password: password) {
        case .success(let success):
            tokenStore.save(success.token)
            lastError = nil
            state = .authenticated(success.user)
        case .failure(let error):
            lastError = error
            state = .unauthenticated
        }
    }

    func logout() async {
        let token = tokenStore.load()
        if let token {
            _ = await api.logout(token: token)
        }
        tokenStore.clear()
        lastError = nil
        state = .unauthenticated
    }

    func consumeError() {
        lastError = nil
    }
}
