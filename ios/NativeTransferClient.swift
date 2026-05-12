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
    ) -> Int? {
        if let localSendBytes = sendLocalSendItems(
            payloadItems,
            to: target,
            localInfo: localInfo,
            totalCompletedBeforeTarget: totalCompletedBeforeTarget,
            totalBytes: totalBytes,
            progressUpdate: progressUpdate
        ) {
            return localSendBytes
        }

        return sendLegacyItems(
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
    ) -> Int? {
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
        guard let prepareResponse = sendHttpRequest(
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
            guard let token = session.fileTokens[indexed.fileId] else {
                return nil
            }
            let path = "/api/localsend/v2/upload" +
                "?sessionId=\(session.sessionId.urlEncoded)" +
                "&fileId=\(indexed.fileId.urlEncoded)" +
                "&token=\(token.urlEncoded)"
            guard let uploadResponse = sendHttpFileRequest(
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
    ) -> Int? {
        let connection = NWConnection(to: target.endpoint, using: .tcp)
        DispatchQueue.main.async {
            activeConnectionUpdate(connection)
        }
        let ready = DispatchSemaphore(value: 0)
        var failed = false

        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                ready.signal()
            case .failed, .cancelled:
                failed = true
                ready.signal()
            default:
                break
            }
        }
        let connectionQueue = DispatchQueue(label: "piko.native.connection.\(UUID().uuidString)")
        connection.start(queue: connectionQueue)
        ready.wait()

        guard !failed else {
            connection.cancel()
            DispatchQueue.main.async {
                activeConnectionUpdate(nil)
            }
            return nil
        }

        let header = NativeTransferProtocol.encodeHeader(items: payloadItems, senderName: senderName)
        guard send(header, over: connection) else {
            connection.cancel()
            DispatchQueue.main.async {
                activeConnectionUpdate(nil)
            }
            return nil
        }

        var sentBytes = 0
        for item in payloadItems {
            guard sendFile(item.fileURL, over: connection) else {
                connection.cancel()
                DispatchQueue.main.async {
                    activeConnectionUpdate(nil)
                }
                return nil
            }
            sentBytes += item.sizeBytes
            progressUpdate(Double(totalCompletedBeforeTarget + sentBytes) / Double(totalBytes))
        }
        connection.cancel()
        DispatchQueue.main.async {
            activeConnectionUpdate(nil)
        }
        return sentBytes
    }

    private func send(_ data: Data, over connection: NWConnection) -> Bool {
        let finished = DispatchSemaphore(value: 0)
        var succeeded = true
        connection.send(content: data, completion: .contentProcessed { error in
            succeeded = error == nil
            finished.signal()
        })
        finished.wait()
        return succeeded
    }

    private func sendFile(_ fileURL: URL, over connection: NWConnection) -> Bool {
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
            let count = stream.read(&buffer, maxLength: bufferSize)
            if count < 0 {
                return false
            }
            if count == 0 {
                break
            }
            guard send(Data(buffer[0..<count]), over: connection) else {
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
    ) -> NativeHttpResponse? {
        let connection = NWConnection(to: endpoint, using: .tcp)
        let ready = DispatchSemaphore(value: 0)
        var failed = false
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                ready.signal()
            case .failed, .cancelled:
                failed = true
                ready.signal()
            default:
                break
            }
        }
        let connectionQueue = DispatchQueue(label: "piko.native.http.\(UUID().uuidString)")
        connection.start(queue: connectionQueue)
        ready.wait()
        guard !failed else {
            connection.cancel()
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

        guard send(request, over: connection) else {
            connection.cancel()
            return nil
        }

        let finished = DispatchSemaphore(value: 0)
        var response = Data()
        func receiveNext() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, _ in
                if let data {
                    response.append(data)
                }
                if isComplete {
                    finished.signal()
                } else {
                    receiveNext()
                }
            }
        }
        receiveNext()
        finished.wait()
        connection.cancel()
        return NativeHttpResponse.parse(response)
    }

    private func sendHttpFileRequest(
        to endpoint: NWEndpoint,
        method: String,
        path: String,
        fileURL: URL,
        contentLength: Int,
        contentType: String
    ) -> NativeHttpResponse? {
        let connection = NWConnection(to: endpoint, using: .tcp)
        let ready = DispatchSemaphore(value: 0)
        var failed = false
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                ready.signal()
            case .failed, .cancelled:
                failed = true
                ready.signal()
            default:
                break
            }
        }
        let connectionQueue = DispatchQueue(label: "piko.native.http.\(UUID().uuidString)")
        connection.start(queue: connectionQueue)
        ready.wait()
        guard !failed else {
            connection.cancel()
            return nil
        }

        var request = Data()
        request.append("\(method) \(path) HTTP/1.1\r\n")
        request.append("Host: piko.local\r\n")
        request.append("Content-Type: \(contentType)\r\n")
        request.append("Content-Length: \(contentLength)\r\n")
        request.append("Connection: close\r\n")
        request.append("\r\n")

        guard send(request, over: connection), sendFile(fileURL, over: connection) else {
            connection.cancel()
            return nil
        }

        let finished = DispatchSemaphore(value: 0)
        var response = Data()
        func receiveNext() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, _ in
                if let data {
                    response.append(data)
                }
                if isComplete {
                    finished.signal()
                } else {
                    receiveNext()
                }
            }
        }
        receiveNext()
        finished.wait()
        connection.cancel()
        return NativeHttpResponse.parse(response)
    }
}
