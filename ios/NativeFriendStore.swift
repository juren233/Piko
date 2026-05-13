import Foundation

@MainActor
final class NativeFriendStore: ObservableObject {
    @Published private(set) var friends: [NativeFriendUser] = []
    @Published private(set) var incomingRequests: [NativeFriendRequest] = []
    @Published private(set) var outgoingRequests: [NativeFriendRequest] = []
    @Published private(set) var searchResults: [NativeFriendSearchResult] = []
    @Published var searchQuery: String = ""
    @Published private(set) var isSearching = false
    @Published private(set) var lastError: NativeAccountError?

    private let api: NativeFriendApiClient
    private let authStore: NativeAuthStore
    private var searchTask: Task<Void, Never>?

    init(api: NativeFriendApiClient, authStore: NativeAuthStore) {
        self.api = api
        self.authStore = authStore
    }

    func refreshAll() async {
        guard let token = authStore.currentToken() else {
            clear()
            return
        }
        async let friendsResult = api.friends(token: token)
        async let incomingResult = api.requests(direction: .incoming, token: token)
        async let outgoingResult = api.requests(direction: .outgoing, token: token)
        let results = await (friendsResult, incomingResult, outgoingResult)
        if case .success(let friends) = results.0,
           case .success(let incoming) = results.1,
           case .success(let outgoing) = results.2 {
            self.friends = friends
            self.incomingRequests = incoming
            self.outgoingRequests = outgoing
            self.lastError = nil
            return
        }
        self.lastError = firstError(results.0, results.1, results.2)
    }

    func search(query: String) {
        searchQuery = query
        searchTask?.cancel()
        searchTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 300_000_000)
            await self?.performSearch(query: query)
        }
    }

    func sendRequest(to userId: String) {
        Task {
            guard let token = authStore.currentToken() else { return }
            switch await api.sendRequest(to: userId, token: token) {
            case .success:
                await refreshAll()
                await performSearch(query: searchQuery)
            case .failure(let error):
                lastError = error
            }
        }
    }

    func accept(_ request: NativeFriendRequest) {
        Task {
            guard let token = authStore.currentToken() else { return }
            switch await api.accept(request.id, token: token) {
            case .success:
                await refreshAll()
            case .failure(let error):
                lastError = error
            }
        }
    }

    func reject(_ request: NativeFriendRequest) {
        Task {
            guard let token = authStore.currentToken() else { return }
            switch await api.reject(request.id, token: token) {
            case .success:
                incomingRequests = incomingRequests.map {
                    $0.id == request.id ? NativeFriendRequest(
                        id: $0.id,
                        direction: $0.direction,
                        otherUser: $0.otherUser,
                        status: .rejected,
                        createdAt: $0.createdAt
                    ) : $0
                }
            case .failure(let error):
                lastError = error
            }
        }
    }

    func cancel(_ request: NativeFriendRequest) {
        Task {
            guard let token = authStore.currentToken() else { return }
            switch await api.cancel(request.id, token: token) {
            case .success:
                outgoingRequests = outgoingRequests.map {
                    $0.id == request.id ? NativeFriendRequest(
                        id: $0.id,
                        direction: $0.direction,
                        otherUser: $0.otherUser,
                        status: .canceled,
                        createdAt: $0.createdAt
                    ) : $0
                }
            case .failure(let error):
                lastError = error
            }
        }
    }

    func removeFriend(_ friend: NativeFriendUser) {
        Task {
            guard let token = authStore.currentToken() else { return }
            switch await api.removeFriend(friend.userId, token: token) {
            case .success:
                friends.removeAll { $0.userId == friend.userId }
            case .failure(let error):
                lastError = error
            }
        }
    }

    func heartbeat() async {
        guard let token = authStore.currentToken() else { return }
        if case .failure(let error) = await api.heartbeat(token: token) {
            lastError = error
        }
    }

    func clear() {
        friends = []
        incomingRequests = []
        outgoingRequests = []
        searchResults = []
        lastError = nil
    }

    private func performSearch(query: String) async {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2, let token = authStore.currentToken() else {
            searchResults = []
            isSearching = false
            return
        }
        isSearching = true
        switch await api.search(query: trimmed, token: token) {
        case .success(let results):
            searchResults = results
            lastError = nil
        case .failure(let error):
            lastError = error
        }
        isSearching = false
    }
}

private func firstError<T, U, V>(
    _ a: Result<T, NativeAccountError>,
    _ b: Result<U, NativeAccountError>,
    _ c: Result<V, NativeAccountError>
) -> NativeAccountError? {
    for result in [a.map { _ in () }, b.map { _ in () }, c.map { _ in () }] {
        if case .failure(let error) = result {
            return error
        }
    }
    return nil
}
