import Foundation
import Network

final class NativeTransferClient {
    typealias ProgressUpdate = (Double) -> Void
    typealias ActiveConnectionUpdate = (NWConnection?) -> Void

    func send(
        _ payloadItems: [NativeTransferItem],
        to target: NativeSendDevice,
        sender: NativeDeviceNickname,
        localInfo: NativeLocalSendDeviceInfo,
        totalCompletedBeforeTarget: Int,
        totalBytes: Int,
        progressUpdate: @escaping ProgressUpdate,
        activeConnectionUpdate: @escaping ActiveConnectionUpdate
    ) async -> Int? {
        if let localSendBytes = await sendLocalSendItems(
            payloadItems,
            to: target,
            localInfo: localInfo,
            totalCompletedBeforeTarget: totalCompletedBeforeTarget,
            totalBytes: totalBytes,
            progressUpdate: progressUpdate
        ) {
            return localSendBytes
        }

        return await sendLegacyItems(
            payloadItems,
            to: target,
            senderName: sender.title,
            totalCompletedBeforeTarget: totalCompletedBeforeTarget,
            totalBytes: totalBytes,
            progressUpdate: progressUpdate,
            activeConnectionUpdate: activeConnectionUpdate
        )
    }

    private func sendLocalSendItems(
        _ payloadItems: [NativeTransferItem],
        to target: NativeSendDevice,
        localInfo: NativeLocalSendDeviceInfo,
        totalCompletedBeforeTarget: Int,
        totalBytes: Int,
        progressUpdate: @escaping ProgressUpdate
    ) async -> Int? {
        let indexedItems = payloadItems.enumerated().map { index, item in
            NativeLocalSendIndexedItem(
                fileId: "file-\(index)",
                item: item,
                metadata: NativeLocalSendFileMetadata(
                    id: "file-\(index)",
                    fileName: item.displayName,
                    size: item.sizeBytes,
                    fileType: item.fileType.mimeType,
                    sha256: nil,
                    preview: item.previewData?.base64EncodedString(),
                    relativePath: item.displayName
                )
            )
        }
        let prepareBody = NativeLocalSendProtocol.prepareUploadRequest(
            info: localInfo,
            files: indexedItems.map(\.metadata)
        )
        guard let prepareResponse = await sendHttpRequest(
            to: target.endpoint,
            method: "POST",
            path: "/api/localsend/v2/prepare-upload",
            body: prepareBody
        ), prepareResponse.statusCode == 200,
            let session = NativeLocalSendProtocol.decodePrepareUploadResponse(prepareResponse.body) else {
            return nil
        }

        var sentBytes = 0
        for indexed in indexedItems {
            guard !Task.isCancelled, let token = session.fileTokens[indexed.fileId] else {
                return nil
            }
            let path = "/api/localsend/v2/upload" +
                "?sessionId=\(session.sessionId.urlEncoded)" +
                "&fileId=\(indexed.fileId.urlEncoded)" +
                "&token=\(token.urlEncoded)"
            guard let uploadResponse = await sendHttpFileRequest(
                to: target.endpoint,
                method: "POST",
                path: path,
                fileURL: indexed.item.fileURL,
                contentLength: indexed.item.sizeBytes,
                contentType: indexed.metadata.fileType
            ), (200..<300).contains(uploadResponse.statusCode) else {
                return nil
            }
            sentBytes += indexed.item.sizeBytes
            progressUpdate(Double(totalCompletedBeforeTarget + sentBytes) / Double(totalBytes))
        }
        return sentBytes
    }

    private func sendLegacyItems(
        _ payloadItems: [NativeTransferItem],
        to target: NativeSendDevice,
        senderName: String,
        totalCompletedBeforeTarget: Int,
        totalBytes: Int,
        progressUpdate: @escaping ProgressUpdate,
        activeConnectionUpdate: @escaping ActiveConnectionUpdate
    ) async -> Int? {
        let connection = NWConnection(to: target.endpoint, using: .tcp)
        DispatchQueue.main.async {
            activeConnectionUpdate(connection)
        }
        defer {
            connection.cancel()
            DispatchQueue.main.async {
                activeConnectionUpdate(nil)
            }
        }

        guard await waitUntilReady(connection, queueLabel: "piko.native.connection.\(UUID().uuidString)") else {
            return nil
        }

        let header = NativeTransferProtocol.encodeHeader(items: payloadItems, senderName: senderName)
        guard await send(header, over: connection) else {
            return nil
        }

        var sentBytes = 0
        for item in payloadItems {
            guard !Task.isCancelled, await sendFile(item.fileURL, over: connection) else {
                return nil
            }
            sentBytes += item.sizeBytes
            progressUpdate(Double(totalCompletedBeforeTarget + sentBytes) / Double(totalBytes))
        }
        return sentBytes
    }

    private func waitUntilReady(_ connection: NWConnection, queueLabel: String) async -> Bool {
        await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                let waiter = NativeConnectionReadyWaiter(continuation)
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready:
                        waiter.resume(true)
                    case .failed, .cancelled:
                        waiter.resume(false)
                    default:
                        break
                    }
                }
                connection.start(queue: DispatchQueue(label: queueLabel))
            }
        } onCancel: {
            connection.cancel()
        }
    }

    private func send(_ data: Data, over connection: NWConnection) async -> Bool {
        await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                connection.send(content: data, completion: .contentProcessed { error in
                    continuation.resume(returning: error == nil)
                })
            }
        } onCancel: {
            connection.cancel()
        }
    }

    private func sendFile(_ fileURL: URL, over connection: NWConnection) async -> Bool {
        guard let stream = InputStream(url: fileURL) else {
            return false
        }
        stream.open()
        defer {
            stream.close()
        }

        let bufferSize = 64 * 1024
        var buffer = [UInt8](repeating: 0, count: bufferSize)
        while stream.hasBytesAvailable {
            if Task.isCancelled {
                connection.cancel()
                return false
            }
            let count = stream.read(&buffer, maxLength: bufferSize)
            if count < 0 {
                return false
            }
            if count == 0 {
                break
            }
            guard await send(Data(buffer[0..<count]), over: connection) else {
                return false
            }
        }
        return stream.streamError == nil
    }

    private func sendHttpRequest(
        to endpoint: NWEndpoint,
        method: String,
        path: String,
        body: Data,
        contentType: String = "application/json; charset=utf-8"
    ) async -> NativeHttpResponse? {
        let connection = NWConnection(to: endpoint, using: .tcp)
        defer {
            connection.cancel()
        }
        guard await waitUntilReady(connection, queueLabel: "piko.native.http.\(UUID().uuidString)") else {
            return nil
        }

        var request = Data()
        request.append("\(method) \(path) HTTP/1.1\r\n")
        request.append("Host: piko.local\r\n")
        request.append("Content-Type: \(contentType)\r\n")
        request.append("Content-Length: \(body.count)\r\n")
        request.append("Connection: close\r\n")
        request.append("\r\n")
        request.append(body)

        guard await send(request, over: connection),
              let response = await receiveResponse(over: connection) else {
            return nil
        }
        return NativeHttpResponse.parse(response)
    }

    private func sendHttpFileRequest(
        to endpoint: NWEndpoint,
        method: String,
        path: String,
        fileURL: URL,
        contentLength: Int,
        contentType: String
    ) async -> NativeHttpResponse? {
        let connection = NWConnection(to: endpoint, using: .tcp)
        defer {
            connection.cancel()
        }
        guard await waitUntilReady(connection, queueLabel: "piko.native.http.\(UUID().uuidString)") else {
            return nil
        }

        var request = Data()
        request.append("\(method) \(path) HTTP/1.1\r\n")
        request.append("Host: piko.local\r\n")
        request.append("Content-Type: \(contentType)\r\n")
        request.append("Content-Length: \(contentLength)\r\n")
        request.append("Connection: close\r\n")
        request.append("\r\n")

        guard await send(request, over: connection),
              await sendFile(fileURL, over: connection),
              let response = await receiveResponse(over: connection) else {
            return nil
        }
        return NativeHttpResponse.parse(response)
    }

    private func receiveResponse(over connection: NWConnection) async -> Data? {
        var response = Data()
        while !Task.isCancelled {
            let chunk = await receiveChunk(over: connection)
            if let data = chunk.data {
                response.append(data)
            }
            if chunk.isComplete {
                return response
            }
        }
        connection.cancel()
        return nil
    }

    private func receiveChunk(over connection: NWConnection) async -> (data: Data?, isComplete: Bool) {
        await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, _ in
                    continuation.resume(returning: (data, isComplete))
                }
            }
        } onCancel: {
            connection.cancel()
        }
    }
}

private final class NativeConnectionReadyWaiter {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Bool, Never>?

    init(_ continuation: CheckedContinuation<Bool, Never>) {
        self.continuation = continuation
    }

    func resume(_ value: Bool) {
        lock.lock()
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(returning: value)
    }
}
