import Foundation

enum NativeTransferProtocol {
    private static let magic = Data([0x50, 0x49, 0x4B, 0x4F])
    private static let version = 1

    static func encodeHeader(items: [NativeTransferItem]) -> Data {
        var data = Data()
        data.append(magic)
        data.appendInt32(version)
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
        var offset = 0
        guard data.count >= 12, data.readData(count: magic.count, offset: &offset) == magic else {
            return nil
        }
        guard data.readInt32(offset: &offset) == version else {
            return nil
        }
        guard let count = data.readInt32(offset: &offset), count >= 0 else {
            return nil
        }

        var metadata: [(String, Int)] = []
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
            guard data.readInt32(offset: &offset) != nil else {
                return nil
            }
            guard let size = data.readInt64(offset: &offset), size >= 0 else {
                return nil
            }
            metadata.append((name, size))
        }

        var files: [NativeReceivedFile] = []
        for (name, size) in metadata {
            guard let bytes = data.readData(count: size, offset: &offset) else {
                return nil
            }
            files.append(NativeReceivedFile(displayName: name, data: bytes))
        }

        return NativeReceivedTransfer(files: files)
    }
}

extension Data {
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
