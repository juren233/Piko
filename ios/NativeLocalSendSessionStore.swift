import Foundation

final class NativeLocalSendSessionStore {
    private var sessions: [String: NativeLocalSendSession] = [:]

    func prepare(_ request: NativeLocalSendPrepareUploadRequest) -> NativeLocalSendPrepareUploadResponse {
        let sessionId = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        var fileTokens: [String: String] = [:]
        var files: [String: NativeLocalSendSessionFile] = [:]
        for file in request.files {
            let token = UUID().uuidString.replacingOccurrences(of: "-", with: "")
            fileTokens[file.id] = token
            files[file.id] = NativeLocalSendSessionFile(metadata: file, token: token)
        }
        sessions[sessionId] = NativeLocalSendSession(sender: request.info, files: files)
        return NativeLocalSendPrepareUploadResponse(sessionId: sessionId, fileTokens: fileTokens)
    }

    func sessionFile(sessionId: String, fileId: String, token: String) -> NativeLocalSendSessionFile? {
        guard let file = sessions[sessionId]?.files[fileId], file.token == token else {
            return nil
        }
        return file
    }

    func cancel(_ sessionId: String) {
        sessions.removeValue(forKey: sessionId)
    }
}
