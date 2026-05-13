import CryptoKit
import Foundation
import Security

struct NativeDeviceIdentity: Codable, Equatable {
    let deviceId: String
    let ed25519PublicB64: String
    let x25519PublicB64: String
    let ed25519PrivateB64: String
    let x25519PrivateB64: String
}

struct NativeDeviceIdentityStore {
    private let service = "com.juren233.piko"
    private let account = "device-identity"

    func loadOrCreate() throws -> NativeDeviceIdentity {
        if let existing = load() {
            return existing
        }
        let signingKey = Curve25519.Signing.PrivateKey()
        let agreementKey = Curve25519.KeyAgreement.PrivateKey()
        let identity = NativeDeviceIdentity(
            deviceId: NativeDeviceIdentityStore.newUlidLikeId(),
            ed25519PublicB64: signingKey.publicKey.rawRepresentation.base64EncodedString(),
            x25519PublicB64: agreementKey.publicKey.rawRepresentation.base64EncodedString(),
            ed25519PrivateB64: signingKey.rawRepresentation.base64EncodedString(),
            x25519PrivateB64: agreementKey.rawRepresentation.base64EncodedString()
        )
        try save(identity)
        return identity
    }

    private func load() -> NativeDeviceIdentity? {
        var query = baseQuery()
        query[kSecReturnData] = true
        query[kSecMatchLimit] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess,
              let data = result as? Data,
              let identity = try? JSONDecoder().decode(NativeDeviceIdentity.self, from: data) else {
            return nil
        }
        return identity
    }

    private func save(_ identity: NativeDeviceIdentity) throws {
        let data = try JSONEncoder().encode(identity)
        SecItemDelete(baseQuery() as CFDictionary)
        var query = baseQuery()
        query[kSecValueData] = data
        query[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw NativeAccountError.networkUnavailable
        }
    }

    private func baseQuery() -> [CFString: Any] {
        [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account
        ]
    }

    private static func newUlidLikeId() -> String {
        let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
        var time = UInt64(Date().timeIntervalSince1970 * 1000)
        var chars = Array(repeating: alphabet[0], count: 26)
        for index in stride(from: 9, through: 0, by: -1) {
            chars[index] = alphabet[Int(time & 31)]
            time >>= 5
        }
        var random = [UInt8](repeating: 0, count: 10)
        _ = SecRandomCopyBytes(kSecRandomDefault, random.count, &random)
        var bitBuffer = 0
        var bitCount = 0
        var outputIndex = 10
        for byte in random {
            bitBuffer = (bitBuffer << 8) | Int(byte)
            bitCount += 8
            while bitCount >= 5 && outputIndex < chars.count {
                bitCount -= 5
                chars[outputIndex] = alphabet[(bitBuffer >> bitCount) & 31]
                outputIndex += 1
            }
        }
        return String(chars)
    }
}
