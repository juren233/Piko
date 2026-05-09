import Foundation

enum NativeTransferProtocol {
    static let magic = Data([0x50, 0x49, 0x4B, 0x4F])
    private static let version = 2

    static func encodeHeader(items: [NativeTransferItem], senderName: String) -> Data {
        var data = Data()
        data.append(magic)
        data.appendInt32(version)
        let senderNameBytes = Data(senderName.utf8)
        data.appendInt32(senderNameBytes.count)
        data.append(senderNameBytes)
        data.appendInt32(items.count)
        for item in items {
            let nameBytes = Data(item.displayName.utf8)
            data.appendInt32(nameBytes.count)
            data.append(nameBytes)
            data.appendInt32(item.fileType.rawValue)
            data.appendInt64(item.data.count)
        }
        return data
    }

    static func decodeTransfer(_ data: Data) -> NativeReceivedTransfer? {
        guard let envelope = inspectTransfer(data), envelope.isComplete else {
            return nil
        }
        return envelope.transfer
    }

    static func inspectTransfer(_ data: Data) -> NativeTransferEnvelope? {
        var offset = 0
        guard data.count >= 12, data.readData(count: magic.count, offset: &offset) == magic else {
            return nil
        }
        guard let decodedVersion = data.readInt32(offset: &offset), decodedVersion >= 1, decodedVersion <= version else {
            return nil
        }
        let senderName: String
        if decodedVersion >= 2 {
            guard let senderNameLength = data.readInt32(offset: &offset), senderNameLength >= 0 else {
                return nil
            }
            guard let senderNameData = data.readData(count: senderNameLength, offset: &offset),
                  let decodedSenderName = String(data: senderNameData, encoding: .utf8) else {
                return nil
            }
            senderName = decodedSenderName
        } else {
            senderName = "局域网设备"
        }
        guard let count = data.readInt32(offset: &offset), count >= 0 else {
            return nil
        }

        var metadata: [NativeTransferFileMetadata] = []
        for _ in 0..<count {
            guard let nameLength = data.readInt32(offset: &offset), nameLength >= 0 else {
                return nil
            }
            guard let nameData = data.readData(count: nameLength, offset: &offset) else {
                return nil
            }
            guard let name = String(data: nameData, encoding: .utf8) else {
                return nil
            }
            guard let rawFileType = data.readInt32(offset: &offset) else {
                return nil
            }
            guard let size = data.readInt64(offset: &offset), size >= 0 else {
                return nil
            }
            let fileType = NativeFileType(rawValue: rawFileType) ?? .other
            metadata.append(NativeTransferFileMetadata(displayName: name, fileType: fileType, sizeBytes: size))
        }

        let payloadOffset = offset
        let totalBytes = metadata.reduce(0) { $0 + $1.sizeBytes }
        let receivedBytes = min(max(data.count - payloadOffset, 0), totalBytes)
        guard receivedBytes >= totalBytes else {
            return NativeTransferEnvelope(
                senderName: senderName,
                files: metadata,
                totalBytes: totalBytes,
                receivedBytes: receivedBytes,
                transfer: nil
            )
        }

        var files: [NativeReceivedFile] = []
        for file in metadata {
            guard let bytes = data.readData(count: file.sizeBytes, offset: &offset) else {
                return nil
            }
            files.append(NativeReceivedFile(displayName: file.displayName, fileType: file.fileType, data: bytes))
        }

        return NativeTransferEnvelope(
            senderName: senderName,
            files: metadata,
            totalBytes: totalBytes,
            receivedBytes: receivedBytes,
            transfer: NativeReceivedTransfer(senderName: senderName, files: files)
        )
    }
}

struct NativeTransferFileMetadata {
    let displayName: String
    let fileType: NativeFileType
    let sizeBytes: Int
}

struct NativeTransferEnvelope {
    let senderName: String
    let files: [NativeTransferFileMetadata]
    let totalBytes: Int
    let receivedBytes: Int
    let transfer: NativeReceivedTransfer?

    var isComplete: Bool {
        transfer != nil
    }
}

extension Data {
    mutating func append(_ string: String) {
        append(Data(string.utf8))
    }

    mutating func appendInt32(_ value: Int) {
        append(contentsOf: [
            UInt8((value >> 24) & 0xFF),
            UInt8((value >> 16) & 0xFF),
            UInt8((value >> 8) & 0xFF),
            UInt8(value & 0xFF),
        ])
    }

    mutating func appendInt64(_ value: Int) {
        for shift in stride(from: 56, through: 0, by: -8) {
            append(UInt8((value >> shift) & 0xFF))
        }
    }

    func readData(count: Int, offset: inout Int) -> Data? {
        guard count >= 0, offset + count <= self.count else {
            return nil
        }
        defer {
            offset += count
        }
        return subdata(in: offset..<(offset + count))
    }

    func readInt32(offset: inout Int) -> Int? {
        guard let bytes = readData(count: 4, offset: &offset) else {
            return nil
        }
        return bytes.reduce(0) { ($0 << 8) | Int($1) }
    }

    func readInt64(offset: inout Int) -> Int? {
        guard let bytes = readData(count: 8, offset: &offset) else {
            return nil
        }
        return bytes.reduce(0) { ($0 << 8) | Int($1) }
    }
}
