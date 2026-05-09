package com.piko.app

private val PIKO_MAGIC = byteArrayOf(0x50, 0x49, 0x4B, 0x4F)
private const val PIKO_PROTOCOL_VERSION = 1

object SendTransferProtocol {
    fun encodeHeader(items: List<SendTransferItem>): ByteArray {
        val writer = ByteWriter()
        writer.writeBytes(PIKO_MAGIC)
        writer.writeInt(PIKO_PROTOCOL_VERSION)
        writer.writeInt(items.size)
        items.forEach { item ->
            val nameBytes = item.displayName.encodeToByteArray()
            writer.writeInt(nameBytes.size)
            writer.writeBytes(nameBytes)
            writer.writeInt(item.fileType.ordinal)
            writer.writeLong(item.sizeBytes)
        }
        return writer.toByteArray()
    }

    fun decodeHeader(bytes: ByteArray): SendTransferHeader {
        val reader = ByteReader(bytes)
        val magic = reader.readBytes(PIKO_MAGIC.size)
        require(magic.contentEquals(PIKO_MAGIC)) { "传输协议标识不匹配" }
        val version = reader.readInt()
        require(version == PIKO_PROTOCOL_VERSION) { "传输协议版本不支持" }
        val count = reader.readInt()
        require(count >= 0) { "文件数量无效" }
        val files = List(count) {
            val nameSize = reader.readInt()
            require(nameSize >= 0) { "文件名长度无效" }
            val name = reader.readBytes(nameSize).decodeToString()
            val type = SendFileType.entries.getOrElse(reader.readInt()) { SendFileType.Other }
            val size = reader.readLong()
            require(size >= 0L) { "文件大小无效" }
            SendTransferHeaderFile(
                displayName = name,
                fileType = type,
                sizeBytes = size,
            )
        }
        return SendTransferHeader(files)
    }
}

data class SendTransferHeader(
    val files: List<SendTransferHeaderFile>,
)

data class SendTransferHeaderFile(
    val displayName: String,
    val fileType: SendFileType,
    val sizeBytes: Long,
)

private class ByteWriter {
    private val bytes = mutableListOf<Byte>()

    fun writeBytes(value: ByteArray) {
        value.forEach { byte -> bytes += byte }
    }

    fun writeInt(value: Int) {
        bytes += ((value ushr 24) and 0xFF).toByte()
        bytes += ((value ushr 16) and 0xFF).toByte()
        bytes += ((value ushr 8) and 0xFF).toByte()
        bytes += (value and 0xFF).toByte()
    }

    fun writeLong(value: Long) {
        for (shift in 56 downTo 0 step 8) {
            bytes += ((value ushr shift) and 0xFF).toByte()
        }
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

private class ByteReader(
    private val bytes: ByteArray,
) {
    private var offset = 0

    fun readBytes(size: Int): ByteArray {
        require(offset + size <= bytes.size) { "传输协议头不完整" }
        return bytes.copyOfRange(offset, offset + size).also {
            offset += size
        }
    }

    fun readInt(): Int {
        val value = readBytes(4)
        return ((value[0].toInt() and 0xFF) shl 24) or
            ((value[1].toInt() and 0xFF) shl 16) or
            ((value[2].toInt() and 0xFF) shl 8) or
            (value[3].toInt() and 0xFF)
    }

    fun readLong(): Long {
        val value = readBytes(8)
        var result = 0L
        value.forEach { byte ->
            result = (result shl 8) or (byte.toLong() and 0xFF)
        }
        return result
    }
}
