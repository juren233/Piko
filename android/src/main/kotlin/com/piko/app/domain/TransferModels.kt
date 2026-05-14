package com.piko.app.domain

enum class TransferRelationship {
    SameAccount,
    Friend,
}

internal const val ReceivePreparingPlaceholder = "对方"

enum class TransferStatus {
    PendingConfirmation,
    Queued,
    Transferring,
    Paused,
    Failed,
    Completed,
}

data class TransferItem(
    val relativePath: String,
    val sizeBytes: Long,
    val chunkCount: Int,
    val hash: String,
)

data class TransferTask(
    val id: String,
    val relationship: TransferRelationship,
    val receiverDeviceId: String,
    val totalBytes: Long,
    val completedBytes: Long,
    val status: TransferStatus,
    val items: List<TransferItem>,
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else completedBytes.toFloat() / totalBytes.toFloat()
}
