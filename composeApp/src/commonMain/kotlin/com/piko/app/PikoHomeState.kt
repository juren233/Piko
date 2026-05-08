package com.piko.app

import com.piko.app.domain.TransferStatus

data class PikoHomeState(
    val currentDeviceName: String,
    val trustedDeviceCount: Int,
    val pendingReceiveCount: Int,
    val transfers: List<TransferListItem>,
) {
    fun withSampleTransfer(): PikoHomeState {
        val sample = TransferListItem(
            id = "sample-transfer",
            name = "未命名文件",
            sizeLabel = "0 B",
            progress = 0f,
            status = TransferStatus.Queued,
        )

        return copy(
            transfers = listOf(sample) + transfers.filterNot { it.id == sample.id },
        )
    }

    companion object {
        fun initial(): PikoHomeState {
            return PikoHomeState(
                currentDeviceName = "当前设备",
                trustedDeviceCount = 0,
                pendingReceiveCount = 0,
                transfers = emptyList(),
            )
        }
    }
}

data class TransferListItem(
    val id: String,
    val name: String,
    val sizeLabel: String,
    val progress: Float,
    val status: TransferStatus,
)

