package com.piko.app

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketException
import kotlin.concurrent.thread

internal class AndroidLocalSendMulticast(
    private val localInfo: () -> LocalSendDeviceInfo,
    private val onDevice: (InetAddress, LocalSendDeviceInfo) -> Unit,
) {
    private var socket: MulticastSocket? = null
    private var listenThread: Thread? = null

    fun start() {
        if (socket != null) {
            return
        }
        val group = InetAddress.getByName(LOCALSEND_MULTICAST_ADDRESS)
        val multicastSocket = MulticastSocket(LOCALSEND_PORT).apply {
            reuseAddress = true
            joinGroup(group)
        }
        socket = multicastSocket
        listenThread = thread(name = "PikoLocalSendMulticast", isDaemon = true) {
            val buffer = ByteArray(64 * 1024)
            while (!multicastSocket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                val announcement = runCatching {
                    multicastSocket.receive(packet)
                    val message = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    LocalSendProtocol.decodeAnnouncement(message)
                }.getOrElse { error ->
                    if (error is SocketException) {
                        break
                    }
                    null
                } ?: continue

                val info = announcement.info
                if (info.fingerprint == localInfo().fingerprint) {
                    continue
                }
                if (info.port > 0) {
                    onDevice(packet.address, info)
                }
                if (announcement.announce) {
                    send(announce = false, address = packet.address, port = packet.port)
                }
            }
        }
    }

    fun announce() {
        send(announce = true, address = InetAddress.getByName(LOCALSEND_MULTICAST_ADDRESS), port = LOCALSEND_PORT)
    }

    fun stop() {
        runCatching { socket?.leaveGroup(InetAddress.getByName(LOCALSEND_MULTICAST_ADDRESS)) }
        socket?.close()
        listenThread?.interrupt()
        socket = null
        listenThread = null
    }

    private fun send(
        announce: Boolean,
        address: InetAddress,
        port: Int,
    ) {
        val activeSocket = socket ?: return
        val body = LocalSendProtocol.announcement(localInfo(), announce).toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(body, body.size, address, port)
        runCatching { activeSocket.send(packet) }
    }
}
