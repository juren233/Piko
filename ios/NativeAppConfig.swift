import Foundation

enum NativeAppConfig {
    static var apiBaseURL: URL {
        let rawValue = Bundle.main.object(forInfoDictionaryKey: "PikoApiBaseURL") as? String
        let normalized = rawValue?.replacingOccurrences(of: "$(PIKO_API_BASE_URL)", with: "").nilIfBlank
        return URL(string: normalized ?? "https://piko-api.juren233.top")!
    }
}
