import Darwin
import Foundation

private let nativeLocalSendMulticastAddress = "224.0.0.167"
private let nativeLocalSendPort: UInt16 = 53317

final class NativeLocalSendMulticast {
    private let queue: DispatchQueue
    private let localInfo: () -> NativeLocalSendDeviceInfo
    private let onDevice: (String, NativeLocalSendDeviceInfo) -> Void
    private var socketFd: Int32 = -1
    private var listenSource: DispatchSourceRead?

    init(
        queue: DispatchQueue,
        localInfo: @escaping () -> NativeLocalSendDeviceInfo,
        onDevice: @escaping (String, NativeLocalSendDeviceInfo) -> Void
    ) {
        self.queue = queue
        self.localInfo = localInfo
        self.onDevice = onDevice
    }

    func start() {
        guard socketFd < 0 else {
            return
        }
        let fd = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else {
            return
        }

        var reuse: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))

        var bindAddress = sockaddr_in()
        bindAddress.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        bindAddress.sin_family = sa_family_t(AF_INET)
        bindAddress.sin_port = nativeLocalSendPort.bigEndian
        bindAddress.sin_addr = in_addr(s_addr: INADDR_ANY.bigEndian)
        let bound = withUnsafePointer(to: &bindAddress) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else {
            Darwin.close(fd)
            return
        }

        var membership = ip_mreq()
        inet_aton(nativeLocalSendMulticastAddress, &membership.imr_multiaddr)
        membership.imr_interface = in_addr(s_addr: INADDR_ANY)
        setsockopt(fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, &membership, socklen_t(MemoryLayout<ip_mreq>.size))

        socketFd = fd
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        source.setEventHandler { [weak self] in
            self?.receiveAvailableDatagram()
        }
        source.setCancelHandler {
            Darwin.close(fd)
        }
        listenSource = source
        source.resume()
    }

    func announce() {
        send(announce: true, host: nativeLocalSendMulticastAddress, port: nativeLocalSendPort)
    }

    func stop() {
        listenSource?.cancel()
        listenSource = nil
        socketFd = -1
    }

    private func receiveAvailableDatagram() {
        var buffer = [UInt8](repeating: 0, count: 64 * 1024)
        var sender = sockaddr_in()
        var senderLength = socklen_t(MemoryLayout<sockaddr_in>.size)
        let count = withUnsafeMutablePointer(to: &sender) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                recvfrom(socketFd, &buffer, buffer.count, 0, $0, &senderLength)
            }
        }
        guard count > 0 else {
            return
        }
        let data = Data(buffer.prefix(count))
        guard let announcement = NativeLocalSendProtocol.decodeAnnouncement(data),
              announcement.info.fingerprint != localInfo().fingerprint else {
            return
        }
        let host = String(cString: inet_ntoa(sender.sin_addr))
        if announcement.info.port > 0 {
            onDevice(host, announcement.info)
        }
        if announcement.announce {
            send(announce: false, host: host, port: UInt16(bigEndian: sender.sin_port))
        }
    }

    private func send(
        announce: Bool,
        host: String,
        port: UInt16
    ) {
        guard socketFd >= 0 else {
            return
        }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        inet_aton(host, &address.sin_addr)
        let data = NativeLocalSendProtocol.announcement(info: localInfo(), announce: announce)
        data.withUnsafeBytes { bytes in
            guard let baseAddress = bytes.baseAddress else {
                return
            }
            withUnsafePointer(to: &address) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    _ = sendto(socketFd, baseAddress, data.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }
}
