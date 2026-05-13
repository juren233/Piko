import CryptoKit
import Foundation

enum NativeTransferProtocolV3 {
    static let chunkSize = 64 * 1024
    private static let magic = Data([0x50, 0x49, 0x4B, 0x33])
    private static let frameManifest = 0x01
    private static let frameChunk = 0x03
    private static let frameAck = 0x04
    private static let frameRetry = 0x05

    static func generateEphemeralKeyPair() -> NativeTransferV3EphemeralKeyPair {
        let privateKey = Curve25519.KeyAgreement.PrivateKey()
        return NativeTransferV3EphemeralKeyPair(
            privateKeyB64: privateKey.rawRepresentation.base64EncodedString(),
            publicKeyB64: privateKey.publicKey.rawRepresentation.base64EncodedString()
        )
    }

    static func deriveSessionKey(
        sessionId: String,
        transferId: String,
        localEphemeralPrivateKeyB64: String,
        peerEphemeralPublicKeyB64: String,
        localStaticPrivateKeyB64: String,
        peerStaticPublicKeyB64: String,
        role: NativeTransferV3KeyAgreementRole
    ) throws -> Data {
        let firstSecret: Data
        let secondSecret: Data
        switch role {
        case .sender:
            firstSecret = try x25519(privateKeyB64: localEphemeralPrivateKeyB64, peerPublicKeyB64: peerStaticPublicKeyB64)
            secondSecret = try x25519(privateKeyB64: localStaticPrivateKeyB64, peerPublicKeyB64: peerEphemeralPublicKeyB64)
        case .receiver:
            firstSecret = try x25519(privateKeyB64: localStaticPrivateKeyB64, peerPublicKeyB64: peerEphemeralPublicKeyB64)
            secondSecret = try x25519(privateKeyB64: localEphemeralPrivateKeyB64, peerPublicKeyB64: peerStaticPublicKeyB64)
        }
        var ikm = Data()
        ikm.append(firstSecret)
        ikm.append(secondSecret)
        ikm.append(try x25519(privateKeyB64: localEphemeralPrivateKeyB64, peerPublicKeyB64: peerEphemeralPublicKeyB64))
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm),
            salt: SHA256.hashData(Data(sessionId.utf8)),
            info: Data("piko-session-v3|\(transferId)".utf8),
            outputByteCount: 32
        )
        return key.withUnsafeBytes { Data($0) }
    }

    static func signInvite(
        transferId: String,
        manifestHashB64: String,
        senderEphemeralPublicKeyB64: String,
        ed25519PrivateKeyB64: String
    ) throws -> String {
        try signEd25519(
            privateKeyB64: ed25519PrivateKeyB64,
            payload: inviteSignaturePayload(
                transferId: transferId,
                manifestHashB64: manifestHashB64,
                senderEphemeralPublicKeyB64: senderEphemeralPublicKeyB64
            )
        ).base64EncodedString()
    }

    static func verifyInviteSignature(
        transferId: String,
        manifestHashB64: String,
        senderEphemeralPublicKeyB64: String,
        signatureB64: String,
        senderEd25519PublicKeyB64: String
    ) -> Bool {
        guard let signature = Data(base64Encoded: signatureB64),
              let payload = try? inviteSignaturePayload(
                transferId: transferId,
                manifestHashB64: manifestHashB64,
                senderEphemeralPublicKeyB64: senderEphemeralPublicKeyB64
              ) else {
            return false
        }
        return verifyEd25519(publicKeyB64: senderEd25519PublicKeyB64, payload: payload, signature: signature)
    }

    static func signAccept(
        sessionId: String,
        transferId: String,
        manifestHashB64: String,
        senderEphemeralPublicKeyB64: String,
        receiverEphemeralPublicKeyB64: String,
        ed25519PrivateKeyB64: String
    ) throws -> String {
        try signEd25519(
            privateKeyB64: ed25519PrivateKeyB64,
            payload: acceptSignaturePayload(
                sessionId: sessionId,
                transferId: transferId,
                manifestHashB64: manifestHashB64,
                senderEphemeralPublicKeyB64: senderEphemeralPublicKeyB64,
                receiverEphemeralPublicKeyB64: receiverEphemeralPublicKeyB64
            )
        ).base64EncodedString()
    }

    static func verifyAcceptSignature(
        sessionId: String,
        transferId: String,
        manifestHashB64: String,
        senderEphemeralPublicKeyB64: String,
        receiverEphemeralPublicKeyB64: String,
        signatureB64: String,
        receiverEd25519PublicKeyB64: String
    ) -> Bool {
        guard let signature = Data(base64Encoded: signatureB64),
              let payload = try? acceptSignaturePayload(
                sessionId: sessionId,
                transferId: transferId,
                manifestHashB64: manifestHashB64,
                senderEphemeralPublicKeyB64: senderEphemeralPublicKeyB64,
                receiverEphemeralPublicKeyB64: receiverEphemeralPublicKeyB64
              ) else {
            return false
        }
        return verifyEd25519(publicKeyB64: receiverEd25519PublicKeyB64, payload: payload, signature: signature)
    }

    static func encodeManifest(
        sessionKey: Data,
        sessionId: String,
        transferId: String,
        files: [NativeTransferV3ManifestInput],
        senderName: String
    ) throws -> Data {
        var plain = Data()
        plain.appendInt32(3)
        plain.appendString(senderName)
        plain.appendInt32(files.count)
        for (index, item) in files.enumerated() {
            plain.appendInt32(index)
            plain.appendString(item.displayName)
            plain.appendInt32(item.fileType.v3Code)
            plain.appendInt64(item.sizeBytes)
            plain.appendInt32(chunkSize)
            plain.appendInt32(chunkCount(sizeBytes: item.sizeBytes))
            plain.append(item.fileHash)
        }
        return try encodeEncryptedFrame(
            sessionKey: sessionKey,
            sessionId: sessionId,
            frameType: frameManifest,
            fileIndex: 0,
            chunkIndex: 0,
            plain: plain
        )
    }

    static func encodeChunk(
        sessionKey: Data,
        sessionId: String,
        fileIndex: Int,
        chunkIndex: Int,
        plain: Data
    ) throws -> Data {
        try encodeEncryptedFrame(
            sessionKey: sessionKey,
            sessionId: sessionId,
            frameType: frameChunk,
            fileIndex: fileIndex,
            chunkIndex: chunkIndex,
            plain: plain
        )
    }

    static func encodeAck(fileIndex: Int, chunkIndex: Int) -> Data {
        encodeControlFrame(frameType: frameAck, fileIndex: fileIndex, chunkIndex: chunkIndex)
    }

    static func encodeRetry(fileIndex: Int, chunkIndex: Int) -> Data {
        encodeControlFrame(frameType: frameRetry, fileIndex: fileIndex, chunkIndex: chunkIndex)
    }

    static func decodeFrame(sessionKey: Data, sessionId: String, transferId: String, frame: Data) throws -> NativeTransferV3Frame {
        var offset = 0
        guard frame.readData(count: 4, offset: &offset) == magic,
              let type = frame.readUInt8(offset: &offset),
              let fileIndex = frame.readInt32(offset: &offset),
              let chunkIndex = frame.readInt32(offset: &offset),
              let plainLength = frame.readInt32(offset: &offset),
              let hash = frame.readData(count: 32, offset: &offset),
              let cipherLength = frame.readInt32(offset: &offset),
              let cipherBytes = frame.readData(count: cipherLength, offset: &offset) else {
            throw NativeTransferProtocolV3Error.invalidFrame
        }
        switch Int(type) {
        case frameManifest:
            let plain = try decrypt(
                sessionKey: sessionKey,
                nonce: nonce(sessionId: sessionId, fileIndex: fileIndex, chunkIndex: chunkIndex),
                aad: aad(frameType: Int(type), fileIndex: fileIndex, chunkIndex: chunkIndex, plainLength: plainLength, hash: hash),
                cipherBytes: cipherBytes
            )
            guard SHA256.hashData(plain) == hash else {
                throw NativeTransferProtocolV3Error.hashMismatch
            }
            return .manifest(try parseManifest(plain))
        case frameChunk:
            let plain = try decrypt(
                sessionKey: sessionKey,
                nonce: nonce(sessionId: sessionId, fileIndex: fileIndex, chunkIndex: chunkIndex),
                aad: aad(frameType: Int(type), fileIndex: fileIndex, chunkIndex: chunkIndex, plainLength: plainLength, hash: hash),
                cipherBytes: cipherBytes
            )
            guard plain.count == plainLength, SHA256.hashData(plain) == hash else {
                throw NativeTransferProtocolV3Error.hashMismatch
            }
            return .chunk(fileIndex: fileIndex, chunkIndex: chunkIndex, bytes: plain)
        case frameAck:
            return .ack(fileIndex: fileIndex, chunkIndex: chunkIndex)
        case frameRetry:
            return .retry(fileIndex: fileIndex, chunkIndex: chunkIndex)
        default:
            throw NativeTransferProtocolV3Error.invalidFrame
        }
    }

    static func peekFrameHeader(_ frame: Data) -> NativeTransferV3FrameHeader? {
        var offset = 0
        guard frame.readData(count: 4, offset: &offset) == magic,
              let type = frame.readUInt8(offset: &offset),
              let fileIndex = frame.readInt32(offset: &offset),
              let chunkIndex = frame.readInt32(offset: &offset) else {
            return nil
        }
        return NativeTransferV3FrameHeader(type: Int(type), fileIndex: fileIndex, chunkIndex: chunkIndex)
    }

    static func chunkCount(sizeBytes: Int) -> Int {
        guard sizeBytes > 0 else {
            return 0
        }
        return (sizeBytes + chunkSize - 1) / chunkSize
    }

    private static func encodeEncryptedFrame(
        sessionKey: Data,
        sessionId: String,
        frameType: Int,
        fileIndex: Int,
        chunkIndex: Int,
        plain: Data
    ) throws -> Data {
        let hash = SHA256.hashData(plain)
        let aad = aad(frameType: frameType, fileIndex: fileIndex, chunkIndex: chunkIndex, plainLength: plain.count, hash: hash)
        let cipherBytes = try encrypt(
            sessionKey: sessionKey,
            nonce: nonce(sessionId: sessionId, fileIndex: fileIndex, chunkIndex: chunkIndex),
            aad: aad,
            plain: plain
        )
        var frame = Data()
        frame.append(magic)
        frame.append(UInt8(frameType))
        frame.appendInt32(fileIndex)
        frame.appendInt32(chunkIndex)
        frame.appendInt32(plain.count)
        frame.append(hash)
        frame.appendInt32(cipherBytes.count)
        frame.append(cipherBytes)
        return frame
    }

    private static func encodeControlFrame(frameType: Int, fileIndex: Int, chunkIndex: Int) -> Data {
        var frame = Data()
        frame.append(magic)
        frame.append(UInt8(frameType))
        frame.appendInt32(fileIndex)
        frame.appendInt32(chunkIndex)
        frame.appendInt32(0)
        frame.append(Data(repeating: 0, count: 32))
        frame.appendInt32(0)
        return frame
    }

    private static func parseManifest(_ data: Data) throws -> NativeTransferV3Manifest {
        var offset = 0
        guard let version = data.readInt32(offset: &offset), version == 3,
              let senderName = data.readString(offset: &offset),
              let fileCount = data.readInt32(offset: &offset) else {
            throw NativeTransferProtocolV3Error.invalidFrame
        }
        var files: [NativeTransferV3File] = []
        for _ in 0..<fileCount {
            guard let index = data.readInt32(offset: &offset),
                  let displayName = data.readString(offset: &offset),
                  let fileTypeRaw = data.readInt32(offset: &offset),
                  let sizeBytes = data.readInt64(offset: &offset),
                  let chunkSize = data.readInt32(offset: &offset),
                  let chunkCount = data.readInt32(offset: &offset),
                  let fileHash = data.readData(count: 32, offset: &offset) else {
                throw NativeTransferProtocolV3Error.invalidFrame
            }
            files.append(
                NativeTransferV3File(
                    index: index,
                    displayName: displayName,
                    fileType: NativeFileType(v3Code: fileTypeRaw),
                    sizeBytes: sizeBytes,
                    chunkSize: chunkSize,
                    chunkCount: chunkCount,
                    fileHash: fileHash
                )
            )
        }
        return NativeTransferV3Manifest(senderName: senderName, files: files)
    }

    private static func aad(frameType: Int, fileIndex: Int, chunkIndex: Int, plainLength: Int, hash: Data) -> Data {
        var data = Data()
        data.append(magic)
        data.append(UInt8(frameType))
        data.appendInt32(fileIndex)
        data.appendInt32(chunkIndex)
        data.appendInt32(plainLength)
        data.append(hash)
        return data
    }

    private static func nonce(sessionId: String, fileIndex: Int, chunkIndex: Int) -> Data {
        let seed = SHA256.hashData(Data(sessionId.utf8))
        var nonce = Data(seed.prefix(4))
        nonce.appendInt32(fileIndex)
        nonce.appendInt32(chunkIndex)
        return nonce
    }

    private static func encrypt(sessionKey: Data, nonce: Data, aad: Data, plain: Data) throws -> Data {
        let sealed = try AES.GCM.seal(
            plain,
            using: SymmetricKey(data: sessionKey),
            nonce: try AES.GCM.Nonce(data: nonce),
            authenticating: aad
        )
        guard let combined = sealed.combined else {
            throw NativeTransferProtocolV3Error.invalidFrame
        }
        return combined
    }

    private static func decrypt(sessionKey: Data, nonce: Data, aad: Data, cipherBytes: Data) throws -> Data {
        let sealedBox = try AES.GCM.SealedBox(combined: cipherBytes)
        return try AES.GCM.open(
            sealedBox,
            using: SymmetricKey(data: sessionKey),
            authenticating: aad
        )
    }

    private static func x25519(privateKeyB64: String, peerPublicKeyB64: String) throws -> Data {
        guard let privateData = Data(base64Encoded: privateKeyB64),
              let peerPublicData = Data(base64Encoded: peerPublicKeyB64) else {
            throw NativeTransferProtocolV3Error.invalidFrame
        }
        let privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateData)
        let publicKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublicData)
        let secret = try privateKey.sharedSecretFromKeyAgreement(with: publicKey)
        return secret.withUnsafeBytes { Data($0) }
    }

    private static func signEd25519(privateKeyB64: String, payload: Data) throws -> Data {
        guard let privateData = Data(base64Encoded: privateKeyB64) else {
            throw NativeTransferProtocolV3Error.invalidFrame
        }
        let privateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: privateData)
        return try privateKey.signature(for: payload)
    }

    private static func verifyEd25519(publicKeyB64: String, payload: Data, signature: Data) -> Bool {
        guard let publicData = Data(base64Encoded: publicKeyB64),
              let publicKey = try? Curve25519.Signing.PublicKey(rawRepresentation: publicData) else {
            return false
        }
        return publicKey.isValidSignature(signature, for: payload)
    }

    private static func inviteSignaturePayload(
        transferId: String,
        manifestHashB64: String,
        senderEphemeralPublicKeyB64: String
    ) throws -> Data {
        guard let manifestHash = Data(base64Encoded: manifestHashB64),
              let senderEphemeralPublicKey = Data(base64Encoded: senderEphemeralPublicKeyB64) else {
            throw NativeTransferProtocolV3Error.invalidFrame
        }
        return signaturePayload(
            domain: "piko-invite-v3",
            parts: [Data(transferId.utf8), manifestHash, senderEphemeralPublicKey]
        )
    }

    private static func acceptSignaturePayload(
        sessionId: String,
        transferId: String,
        manifestHashB64: String,
        senderEphemeralPublicKeyB64: String,
        receiverEphemeralPublicKeyB64: String
    ) throws -> Data {
        guard let manifestHash = Data(base64Encoded: manifestHashB64),
              let senderEphemeralPublicKey = Data(base64Encoded: senderEphemeralPublicKeyB64),
              let receiverEphemeralPublicKey = Data(base64Encoded: receiverEphemeralPublicKeyB64) else {
            throw NativeTransferProtocolV3Error.invalidFrame
        }
        return signaturePayload(
            domain: "piko-accept-v3",
            parts: [Data(sessionId.utf8), Data(transferId.utf8), manifestHash, senderEphemeralPublicKey, receiverEphemeralPublicKey]
        )
    }

    private static func signaturePayload(domain: String, parts: [Data]) -> Data {
        var data = Data()
        data.appendString(domain)
        for part in parts {
            data.appendInt32(part.count)
            data.append(part)
        }
        return data
    }
}

struct NativeTransferV3Manifest {
    let senderName: String
    let files: [NativeTransferV3File]
}

struct NativeTransferV3EphemeralKeyPair {
    let privateKeyB64: String
    let publicKeyB64: String
}

enum NativeTransferV3KeyAgreementRole {
    case sender
    case receiver
}

struct NativeTransferV3ManifestInput {
    let displayName: String
    let fileType: NativeFileType
    let sizeBytes: Int
    let fileHash: Data
}

struct NativeTransferV3File {
    let index: Int
    let displayName: String
    let fileType: NativeFileType
    let sizeBytes: Int
    let chunkSize: Int
    let chunkCount: Int
    let fileHash: Data
}

enum NativeTransferV3Frame {
    case manifest(NativeTransferV3Manifest)
    case chunk(fileIndex: Int, chunkIndex: Int, bytes: Data)
    case ack(fileIndex: Int, chunkIndex: Int)
    case retry(fileIndex: Int, chunkIndex: Int)
}

struct NativeTransferV3FrameHeader {
    let type: Int
    let fileIndex: Int
    let chunkIndex: Int

    var isChunk: Bool {
        type == 0x03
    }
}

enum NativeTransferProtocolV3Error: Error {
    case invalidFrame
    case hashMismatch
}

private extension NativeFileType {
    var v3Code: Int {
        switch self {
        case .document: return 0
        case .spreadsheet: return 1
        case .image: return 2
        case .video: return 3
        case .archive: return 4
        case .other: return 5
        }
    }

    init(v3Code: Int) {
        switch v3Code {
        case 0: self = .document
        case 1: self = .spreadsheet
        case 2: self = .image
        case 3: self = .video
        case 4: self = .archive
        default: self = .other
        }
    }
}

private extension Data {
    mutating func appendString(_ value: String) {
        let bytes = Data(value.utf8)
        appendInt32(bytes.count)
        append(bytes)
    }

    func readUInt8(offset: inout Int) -> UInt8? {
        guard let data = readData(count: 1, offset: &offset) else {
            return nil
        }
        return data.first
    }

    func readString(offset: inout Int) -> String? {
        guard let length = readInt32(offset: &offset),
              let data = readData(count: length, offset: &offset) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }
}

extension SHA256 {
    static func hashData(_ data: Data) -> Data {
        Data(hash(data: data))
    }
}
