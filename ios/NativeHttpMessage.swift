import Foundation

struct NativeHttpRequest {
    let method: String
    let path: String
    let query: [String: String]
    let contentLength: Int

    static func parse(_ headerData: Data) -> NativeHttpRequest? {
        guard let header = String(data: headerData, encoding: .utf8) else {
            return nil
        }
        let lines = header.components(separatedBy: "\r\n").filter { !$0.isEmpty }
        guard let requestLine = lines.first else {
            return nil
        }
        let parts = requestLine.split(separator: " ", maxSplits: 2).map(String.init)
        guard parts.count >= 2 else {
            return nil
        }
        let target = parts[1]
        let path = target.components(separatedBy: "?").first ?? target
        let queryString = target.contains("?") ? String(target.split(separator: "?", maxSplits: 1).last ?? "") : ""
        let headers = Dictionary(uniqueKeysWithValues: lines.dropFirst().compactMap { line -> (String, String)? in
            let parts = line.split(separator: ":", maxSplits: 1).map(String.init)
            guard parts.count == 2 else {
                return nil
            }
            return (parts[0].trimmingCharacters(in: .whitespaces).lowercased(), parts[1].trimmingCharacters(in: .whitespaces))
        })
        return NativeHttpRequest(
            method: parts[0].uppercased(),
            path: path,
            query: parseQuery(queryString),
            contentLength: Int(headers["content-length"] ?? "0") ?? 0
        )
    }

    private static func parseQuery(_ query: String) -> [String: String] {
        guard !query.isEmpty else {
            return [:]
        }
        return Dictionary(uniqueKeysWithValues: query.split(separator: "&").map { item in
            let parts = item.split(separator: "=", maxSplits: 1).map(String.init)
            let key = parts.first?.removingPercentEncoding ?? ""
            let value = (parts.count > 1 ? parts[1] : "").removingPercentEncoding ?? ""
            return (key, value)
        })
    }
}

struct NativeHttpResponse {
    let statusCode: Int
    let body: Data

    static func parse(_ data: Data) -> NativeHttpResponse? {
        guard let range = data.range(of: Data("\r\n\r\n".utf8)),
              let header = String(data: data.subdata(in: 0..<range.lowerBound), encoding: .utf8) else {
            return nil
        }
        let statusLine = header.components(separatedBy: "\r\n").first ?? ""
        let code = Int(statusLine.split(separator: " ").dropFirst().first ?? "") ?? 0
        let body = data.subdata(in: range.upperBound..<data.count)
        return NativeHttpResponse(statusCode: code, body: body)
    }
}
