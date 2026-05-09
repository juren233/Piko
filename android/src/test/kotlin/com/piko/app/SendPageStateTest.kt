package com.piko.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendPageStateTest {
    @Test
    fun initialStateDoesNotExposeCurrentDeviceAsSelectableTarget() {
        val state = SendPageState.initial(currentDeviceName = "Pixel")

        assertEquals(emptyList(), state.myDevices)
        assertFalse(state.selectedDeviceIds.contains("current-device"))
    }

    @Test
    fun updateLanDevicesFiltersCurrentDevicePlaceholderAndClearsSelection() {
        val currentDevice = SendDevice(
            id = "current-device",
            name = "Pixel",
            group = SendDeviceGroup.MyDevice,
            host = "192.168.1.2",
            port = 42001,
        )
        val remoteDevice = SendDevice(
            id = "remote-device",
            name = "Cavan 的 iPhone",
            group = SendDeviceGroup.Lan,
            host = "192.168.1.3",
            port = 42001,
        )

        val state = SendPageState.initial(currentDeviceName = "Pixel")
            .copy(selectedDeviceIds = setOf(currentDevice.id, remoteDevice.id))
            .updateLanDevices(listOf(currentDevice, remoteDevice))

        assertEquals(listOf(remoteDevice), state.lanDevices)
        assertEquals(setOf(remoteDevice.id), state.selectedDeviceIds)
    }

    @Test
    fun updateLanDevicesKeepsRandomNicknameTitleAndCodeSubtitle() {
        val remoteDevice = SendDevice(
            id = "lan-1",
            name = "清亮竹影",
            group = SendDeviceGroup.Lan,
            subtitle = "4971",
            host = "192.168.1.8",
            port = 42001,
        )

        val state = SendPageState.initial(currentDeviceName = "赤色星河@0007")
            .updateLanDevices(listOf(remoteDevice))

        assertEquals("清亮竹影", state.lanDevices.single().name)
        assertEquals("4971", state.lanDevices.single().subtitle)
        assertFalse(state.lanDevices.single().subtitle == remoteDevice.host)
    }

    @Test
    fun toggleDeviceSelectionAddsAndRemovesDeviceId() {
        val device = SendDevice(
            id = "device-mac",
            name = "MacBook",
            group = SendDeviceGroup.Lan,
        )

        val selected = SendPageState.initial(currentDeviceName = "Pixel")
            .copy(lanDevices = listOf(device))
            .toggleDeviceSelection(device.id)

        assertTrue(device.id in selected.selectedDeviceIds)

        val unselected = selected.toggleDeviceSelection(device.id)

        assertFalse(device.id in unselected.selectedDeviceIds)
    }

    @Test
    fun collapsedImageRowShowsSelectedImagesFirst() {
        val first = SendImageItem(id = "image-1", displayName = "IMG_1.jpg", uri = "content://image/1")
        val second = SendImageItem(id = "image-2", displayName = "IMG_2.jpg", uri = "content://image/2")

        val state = SendPageState.initial(currentDeviceName = "Pixel")
            .copy(recentImages = listOf(first, second))
            .toggleImageSelection(second.id)

        assertEquals(listOf(second), state.visibleImages)
    }

    @Test
    fun expandedImageRowShowsRecentImagesEvenWhenSelectionExists() {
        val first = SendImageItem(id = "image-1", displayName = "IMG_1.jpg", uri = "content://image/1")
        val second = SendImageItem(id = "image-2", displayName = "IMG_2.jpg", uri = "content://image/2")

        val state = SendPageState.initial(currentDeviceName = "Pixel")
            .copy(recentImages = listOf(first, second))
            .toggleImageSelection(second.id)
            .toggleImageSectionExpanded()

        assertEquals(listOf(first, second), state.visibleImages)
    }

    @Test
    fun addSelectedFilesDeduplicatesAndRemoveFileUpdatesList() {
        val file = SendFileItem(
            id = "content://file/report.pdf",
            displayName = "report.pdf",
            sizeBytes = 1024,
            fileType = SendFileType.Document,
            sourceUri = "content://file/report.pdf",
        )

        val state = SendPageState.initial(currentDeviceName = "Pixel")
            .addSelectedFiles(listOf(file, file))

        assertEquals(listOf(file), state.selectedFiles)
        assertEquals(emptyList(), state.removeSelectedFile(file.id).selectedFiles)
    }

    @Test
    fun canSendRequiresTargetAndAtLeastOneItemAndNoActiveTransfer() {
        val device = SendDevice(
            id = "device-mac",
            name = "MacBook",
            group = SendDeviceGroup.Lan,
            host = "192.168.1.8",
            port = 42001,
        )
        val file = SendFileItem(
            id = "content://file/report.pdf",
            displayName = "report.pdf",
            sizeBytes = 1024,
            fileType = SendFileType.Document,
            sourceUri = "content://file/report.pdf",
        )
        val ready = SendPageState.initial(currentDeviceName = "Pixel")
            .copy(lanDevices = listOf(device))
            .toggleDeviceSelection(device.id)
            .addSelectedFiles(listOf(file))

        assertTrue(ready.canSend)
        assertFalse(ready.startTransfer("transfer-1").canSend)
    }

    @Test
    fun transferSummaryUsesFirstFileNameCountAndTotalSize() {
        val image = SendImageItem(
            id = "content://image/1",
            displayName = "IMG_1.jpg",
            uri = "content://image/1",
            sizeBytes = 2048,
        )
        val file = SendFileItem(
            id = "content://file/report.pdf",
            displayName = "report.pdf",
            sizeBytes = 1024,
            fileType = SendFileType.Document,
            sourceUri = "content://file/report.pdf",
        )
        val state = SendPageState.initial(currentDeviceName = "Pixel")
            .copy(recentImages = listOf(image))
            .toggleImageSelection(image.id)
            .addSelectedFiles(listOf(file))

        assertEquals("IMG_1.jpg + 1 个文件", state.transferSummaryTitle)
        assertEquals(3072, state.transferTotalBytes)
    }

    @Test
    fun transferProgressEventsUpdateActiveStateAndCancelClearsSendingBlock() {
        val file = SendFileItem(
            id = "content://file/report.pdf",
            displayName = "report.pdf",
            sizeBytes = 100,
            fileType = SendFileType.Document,
            sourceUri = "content://file/report.pdf",
        )
        val started = SendPageState.initial(currentDeviceName = "Pixel")
            .addSelectedFiles(listOf(file))
            .startTransfer("transfer-1")
            .applyTransferEvent(SendTransferEvent.Progress("transfer-1", 40, 100))

        assertEquals(SendTransferStatus.Transferring, started.activeTransfer.status)
        assertEquals(40, started.activeTransfer.completedBytes)

        val canceled = started.applyTransferEvent(SendTransferEvent.Canceled("transfer-1"))

        assertEquals(SendTransferStatus.Canceled, canceled.activeTransfer.status)
        assertFalse(canceled.activeTransfer.isBlockingSend)
    }

    @Test
    fun transferRequestAggregatesTotalBytesAcrossTargets() {
        val devices = listOf(
            SendDevice(id = "device-a", name = "A", group = SendDeviceGroup.Lan, host = "192.168.1.2", port = 42001),
            SendDevice(id = "device-b", name = "B", group = SendDeviceGroup.Lan, host = "192.168.1.3", port = 42001),
        )
        val files = listOf(
            SendFileItem(
                id = "content://file/a.txt",
                displayName = "a.txt",
                sizeBytes = 10,
                fileType = SendFileType.Other,
                sourceUri = "content://file/a.txt",
            ),
            SendFileItem(
                id = "content://file/b.txt",
                displayName = "b.txt",
                sizeBytes = 30,
                fileType = SendFileType.Other,
                sourceUri = "content://file/b.txt",
            ),
        )

        val request = SendPageState.initial(currentDeviceName = "Pixel")
            .copy(lanDevices = devices)
            .toggleDeviceSelection("device-a")
            .toggleDeviceSelection("device-b")
            .addSelectedFiles(files)
            .buildTransferRequest()

        assertEquals(80, request?.totalBytes)
    }

    @Test
    fun pausedTransferRestoresSendButtonAvailability() {
        val file = SendFileItem(
            id = "content://file/report.pdf",
            displayName = "report.pdf",
            sizeBytes = 100,
            fileType = SendFileType.Document,
            sourceUri = "content://file/report.pdf",
        )
        val device = SendDevice(
            id = "device-mac",
            name = "MacBook",
            group = SendDeviceGroup.Lan,
            host = "192.168.1.8",
            port = 42001,
        )
        val started = SendPageState.initial(currentDeviceName = "Pixel")
            .copy(lanDevices = listOf(device))
            .toggleDeviceSelection(device.id)
            .addSelectedFiles(listOf(file))
            .startTransfer("transfer-1")

        val paused = started.applyTransferEvent(SendTransferEvent.Paused("transfer-1"))

        assertEquals(SendTransferStatus.Paused, paused.activeTransfer.status)
        assertTrue(paused.canSend)
    }
}
