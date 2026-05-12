import Foundation
import Network

struct NativeTransferStateSnapshot {
    let transferLabel: String
    let transferProgress: Double?
    let activeReceive: NativeReceiveTransferState?
}

final class NativeTransferStateMachine {
    private var activeSendConnection: NWConnection?
    private var activeReceiveConnection: NWConnection?
    private var transferLabel = "等待发送"
    private var transferProgress: Double?
    private var activeReceive: NativeReceiveTransferState?
    private let onChange: (NativeTransferStateSnapshot) -> Void

    init(onChange: @escaping (NativeTransferStateSnapshot) -> Void) {
        self.onChange = onChange
    }

    func beginSend(title: String) {
        transferLabel = title
        transferProgress = 0
        publish()
    }

    func updateSendProgress(_ progress: Double) {
        transferProgress = progress
        publish()
    }

    func setActiveSendConnection(_ connection: NWConnection?) {
        activeSendConnection = connection
    }

    func finishSend() {
        activeSendConnection = nil
        transferLabel = "等待发送"
        transferProgress = nil
        publish()
    }

    func pauseSend() {
        activeSendConnection?.cancel()
        finishSend()
    }

    func cancelSend() {
        activeSendConnection?.cancel()
        finishSend()
    }

    func setActiveReceiveConnection(_ connection: NWConnection?) {
        activeReceiveConnection = connection
    }

    func clearActiveReceiveConnection(ifSame connection: NWConnection) {
        if activeReceiveConnection === connection {
            activeReceiveConnection = nil
        }
    }

    func updateActiveReceive(_ receive: NativeReceiveTransferState) {
        activeReceive = receive
        publish()
    }

    func incrementActiveReceive(id: String, receivedBytes: Int) {
        guard let activeReceive, activeReceive.id == id else {
            return
        }
        self.activeReceive = NativeReceiveTransferState(
            id: activeReceive.id,
            senderName: activeReceive.senderName,
            files: activeReceive.files,
            totalBytes: activeReceive.totalBytes,
            receivedBytes: min(activeReceive.receivedBytes + receivedBytes, activeReceive.totalBytes)
        )
        publish()
    }

    func clearActiveReceive(id: String? = nil) {
        guard id == nil || activeReceive?.id == id else {
            return
        }
        activeReceive = nil
        publish()
    }

    func cancelReceive() {
        activeReceiveConnection?.cancel()
        activeReceiveConnection = nil
        activeReceive = nil
        publish()
    }

    private func publish() {
        let snapshot = NativeTransferStateSnapshot(
            transferLabel: transferLabel,
            transferProgress: transferProgress,
            activeReceive: activeReceive
        )
        if Thread.isMainThread {
            onChange(snapshot)
        } else {
            DispatchQueue.main.async {
                self.onChange(snapshot)
            }
        }
    }
}
