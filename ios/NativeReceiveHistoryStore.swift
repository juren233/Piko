import Foundation

enum NativeReceiveHistoryStore {
    private static let fileName = "receive_history.json"

    static func load() -> [NativeReceiveHistoryItem] {
        guard let data = try? Data(contentsOf: storeURL()) else {
            return []
        }
        return (try? JSONDecoder().decode([NativeReceiveHistoryItem].self, from: data)) ?? []
    }

    static func save(_ receiveHistory: [NativeReceiveHistoryItem]) {
        let url = storeURL()
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard let data = try? JSONEncoder().encode(receiveHistory) else {
            return
        }
        try? data.write(to: url, options: .atomic)
    }

    private static func storeURL() -> URL {
        let baseURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return baseURL.appendingPathComponent(fileName, isDirectory: false)
    }
}
