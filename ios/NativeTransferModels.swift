import Foundation

struct NativeTransferItem: Identifiable {
    let id: String
    let displayName: String
    let fileType: NativeFileType
    let fileURL: URL
    let sizeBytes: Int
    let previewData: Data?

    init(
        id: String,
        displayName: String,
        fileType: NativeFileType,
        fileURL: URL,
        sizeBytes: Int,
        previewData: Data? = nil
    ) {
        self.id = id
        self.displayName = displayName
        self.fileType = fileType
        self.fileURL = fileURL
        self.sizeBytes = sizeBytes
        self.previewData = previewData
    }

    var sizeLabel: String {
        ByteCountFormatter.string(fromByteCount: Int64(sizeBytes), countStyle: .file)
    }

    var systemImage: String {
        fileType == .image ? "photo" : "doc"
    }
}

struct NativeReceiveHistoryItem: Identifiable, Codable {
    let id: UUID
    let title: String
    let subtitle: String
    let fileCount: Int
    let primaryFileType: NativeFileType
    let mediaPreviewData: Data?
    let files: [NativeReceiveHistoryFile]

    init(
        id: UUID = UUID(),
        title: String,
        subtitle: String,
        fileCount: Int,
        primaryFileType: NativeFileType,
        mediaPreviewData: Data?,
        files: [NativeReceiveHistoryFile]
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.fileCount = fileCount
        self.primaryFileType = primaryFileType
        self.mediaPreviewData = mediaPreviewData
        self.files = files
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        title = try container.decode(String.self, forKey: .title)
        subtitle = try container.decode(String.self, forKey: .subtitle)
        fileCount = try container.decode(Int.self, forKey: .fileCount)
        primaryFileType = try container.decode(NativeFileType.self, forKey: .primaryFileType)
        mediaPreviewData = try container.decodeIfPresent(Data.self, forKey: .mediaPreviewData)
        files = try container.decodeIfPresent([NativeReceiveHistoryFile].self, forKey: .files) ?? [
            NativeReceiveHistoryFile(
                displayName: title,
                fileType: primaryFileType,
                sizeBytes: 0,
                savedURLPath: nil,
                photoAssetIdentifier: nil
            )
        ]
    }

    var deleteConfirmationTitle: String {
        if fileCount == 1 {
            return "真的要删除\(files.first?.displayName ?? title)吗？"
        }
        return "真的要删除这\(fileCount)个吗？"
    }

    var deleteConfirmationBody: String {
        if fileCount == 1 {
            return "此操作不可逆！"
        }
        return "将会删除：\(files.map(\.displayName).joined(separator: "、")) 此操作不可逆！"
    }
}

struct NativeReceiveHistoryFile: Codable {
    let displayName: String
    let fileType: NativeFileType
    let sizeBytes: Int
    let savedURLPath: String?
    let photoAssetIdentifier: String?

    init(
        displayName: String,
        fileType: NativeFileType,
        sizeBytes: Int,
        savedURLPath: String?,
        photoAssetIdentifier: String?
    ) {
        self.displayName = displayName
        self.fileType = fileType
        self.sizeBytes = sizeBytes
        self.savedURLPath = savedURLPath
        self.photoAssetIdentifier = photoAssetIdentifier
    }

    init(file: NativeReceivedFile) {
        self.displayName = file.displayName
        self.fileType = file.fileType
        self.sizeBytes = file.sizeBytes
        self.savedURLPath = file.savedURLPath
        self.photoAssetIdentifier = file.photoAssetIdentifier
    }
}

struct NativeReceiveTransferState: Identifiable {
    let id: String
    let senderName: String
    let files: [NativeTransferFileMetadata]
    let totalBytes: Int
    let receivedBytes: Int

    var title: String {
        "正在从\(senderName.visibleDeviceName)接收\(files.count)个文件"
    }

    var subtitle: String {
        "\(ByteCountFormatter.string(fromByteCount: Int64(receivedBytes), countStyle: .file))/\(ByteCountFormatter.string(fromByteCount: Int64(totalBytes), countStyle: .file))"
    }

    var progress: Double {
        guard totalBytes > 0 else {
            return 0
        }
        return min(max(Double(receivedBytes) / Double(totalBytes), 0), 1)
    }

    var primaryFileType: NativeFileType {
        files.first?.fileType ?? .other
    }
}

enum NativeFileType: Int, Codable {
    case document = 0
    case spreadsheet = 1
    case image = 2
    case video = 3
    case archive = 4
    case other = 5

    init(mimeType: String) {
        if mimeType.hasPrefix("image/") {
            self = .image
        } else if mimeType.hasPrefix("video/") {
            self = .video
        } else if mimeType.contains("zip") || mimeType.contains("archive") {
            self = .archive
        } else if mimeType.contains("spreadsheet") || mimeType.contains("excel") {
            self = .spreadsheet
        } else if mimeType.contains("pdf") || mimeType.contains("document") || mimeType.hasPrefix("text/") {
            self = .document
        } else {
            self = .other
        }
    }

    var mimeType: String {
        switch self {
        case .image:
            return "image/*"
        case .video:
            return "video/*"
        case .archive:
            return "application/zip"
        case .document, .spreadsheet, .other:
            return "application/octet-stream"
        }
    }

    var previewLabel: String {
        switch self {
        case .document:
            return "DOC"
        case .spreadsheet:
            return "XLS"
        case .image:
            return "IMG"
        case .video:
            return "VID"
        case .archive:
            return "ZIP"
        case .other:
            return "FILE"
        }
    }
}

struct NativeReceivedFile {
    let displayName: String
    let fileType: NativeFileType
    let sizeBytes: Int
    let mediaPreviewData: Data?
    let savedURLPath: String?
    let photoAssetIdentifier: String?

    init(
        displayName: String,
        fileType: NativeFileType,
        sizeBytes: Int,
        mediaPreviewData: Data? = nil,
        savedURLPath: String? = nil,
        photoAssetIdentifier: String? = nil
    ) {
        self.displayName = displayName
        self.fileType = fileType
        self.sizeBytes = sizeBytes
        self.mediaPreviewData = mediaPreviewData
        self.savedURLPath = savedURLPath
        self.photoAssetIdentifier = photoAssetIdentifier
    }
}

struct NativeReceivedTransfer {
    let senderName: String
    let files: [NativeReceivedPayloadFile]
}
