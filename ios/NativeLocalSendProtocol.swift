import Foundation

private let nativeLocalSendVersion = "2.0"

struct NativeLocalSendDeviceInfo: Equatable {
    let alias: String
    let version: String
    let deviceModel: String
    let deviceType: String
    let fingerprint: String
    let port: Int
    let protocolName: String
    let download: Bool

    var jsonObject: [String: Any] {
        [
            "alias": alias,
            "version": version,
            "deviceModel": deviceModel,
            "deviceType": deviceType,
            "fingerprint": fingerprint,
            "port": port,
            "protocol": protocolName,
            "download": download,
        ]
    }

    var jsonData: Data {
        (try? JSONSerialization.data(withJSONObject: jsonObject)) ?? Data(#"{}"#.utf8)
    }
}

struct NativeLocalSendAnnouncement {
    let info: NativeLocalSendDeviceInfo
    let announce: Bool
}

struct NativeLocalSendFileMetadata {
    let id: String
    let fileName: String
    let size: Int
    let fileType: String
    let sha256: String?
    let preview: String?
    let relativePath: String?

    var jsonObject: [String: Any] {
        [
            "id": id,
            "fileName": fileName,
            "size": size,
            "fileType": fileType,
            "sha256": sha256 ?? NSNull(),
            "preview": preview ?? NSNull(),
            "metadata": [
                "relativePath": relativePath.map { $0 as Any } ?? NSNull(),
            ],
        ]
    }
}

struct NativeLocalSendPrepareUploadRequest {
    let info: NativeLocalSendDeviceInfo
    let files: [NativeLocalSendFileMetadata]
}

struct NativeLocalSendPrepareUploadResponse {
    let sessionId: String
    let fileTokens: [String: String]

    var jsonData: Data {
        let object: [String: Any] = [
            "sessionId": sessionId,
            "files": fileTokens,
        ]
        return (try? JSONSerialization.data(withJSONObject: object)) ?? Data(#"{}"#.utf8)
    }
}

struct NativeLocalSendSession {
    let sender: NativeLocalSendDeviceInfo
    let files: [String: NativeLocalSendSessionFile]
}

struct NativeLocalSendSessionFile {
    let metadata: NativeLocalSendFileMetadata
    let token: String
}

struct NativeLocalSendIndexedItem {
    let fileId: String
    let item: NativeTransferItem
    let metadata: NativeLocalSendFileMetadata
}

enum NativeLocalSendProtocol {
    static func announcement(
        info: NativeLocalSendDeviceInfo,
        announce: Bool
    ) -> Data {
        var object = info.jsonObject
        object["announce"] = announce
        return (try? JSONSerialization.data(withJSONObject: object)) ?? Data(#"{}"#.utf8)
    }

    static func decodeAnnouncement(_ data: Data) -> NativeLocalSendAnnouncement? {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let info = decodeInfo(object) else {
            return nil
        }
        return NativeLocalSendAnnouncement(info: info, announce: object["announce"] as? Bool ?? false)
    }

    static func prepareUploadRequest(
        info: NativeLocalSendDeviceInfo,
        files: [NativeLocalSendFileMetadata]
    ) -> Data {
        let filesObject = Dictionary(uniqueKeysWithValues: files.map { ($0.id, $0.jsonObject) })
        let object: [String: Any] = [
            "info": info.jsonObject,
            "files": filesObject,
        ]
        return (try? JSONSerialization.data(withJSONObject: object)) ?? Data(#"{}"#.utf8)
    }

    static func decodePrepareUploadRequest(_ data: Data) -> NativeLocalSendPrepareUploadRequest? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let infoObject = root["info"] as? [String: Any],
              let info = decodeInfo(infoObject),
              let filesObject = root["files"] as? [String: Any] else {
            return nil
        }
        let files = filesObject.compactMap { key, value -> NativeLocalSendFileMetadata? in
            guard let object = value as? [String: Any] else {
                return nil
            }
            return decodeFile(object, defaultId: key)
        }
        return NativeLocalSendPrepareUploadRequest(info: info, files: files.sorted { $0.id < $1.id })
    }

    static func decodePrepareUploadResponse(_ data: Data) -> NativeLocalSendPrepareUploadResponse? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let sessionId = root["sessionId"] as? String,
              let files = root["files"] as? [String: String] else {
            return nil
        }
        return NativeLocalSendPrepareUploadResponse(sessionId: sessionId, fileTokens: files)
    }

    private static func decodeInfo(_ object: [String: Any]) -> NativeLocalSendDeviceInfo? {
        guard let alias = object["alias"] as? String else {
            return nil
        }
        return NativeLocalSendDeviceInfo(
            alias: alias,
            version: object["version"] as? String ?? nativeLocalSendVersion,
            deviceModel: object["deviceModel"] as? String ?? "",
            deviceType: object["deviceType"] as? String ?? "desktop",
            fingerprint: object["fingerprint"] as? String ?? "",
            port: object["port"] as? Int ?? 0,
            protocolName: object["protocol"] as? String ?? "http",
            download: object["download"] as? Bool ?? false
        )
    }

    private static func decodeFile(_ object: [String: Any], defaultId: String) -> NativeLocalSendFileMetadata? {
        guard let fileName = object["fileName"] as? String else {
            return nil
        }
        let metadata = object["metadata"] as? [String: Any]
        return NativeLocalSendFileMetadata(
            id: object["id"] as? String ?? defaultId,
            fileName: fileName,
            size: object["size"] as? Int ?? 0,
            fileType: object["fileType"] as? String ?? "application/octet-stream",
            sha256: object["sha256"] as? String,
            preview: object["preview"] as? String,
            relativePath: metadata?["relativePath"] as? String
        )
    }
}
