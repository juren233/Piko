package com.piko.app

import com.piko.app.domain.SendDevice
import com.piko.app.domain.SendDeviceGroup
import com.piko.app.domain.SendFileItem
import com.piko.app.domain.SendFileType
import com.piko.app.domain.FriendDevice
import com.piko.app.domain.SendMediaItem
import com.piko.app.domain.SendPageState
import com.piko.app.domain.SendTransferEvent
import com.piko.app.domain.SendTransferStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendPageStateTest {
    @Test
    fun initialStateDoesNotExposeCurrentDeviceAsSelectableTarget() {
        val state = SendPageState.initial(currentDeviceName = "Pixel")

        assertEquals(emptyList(), state.myDevices)
        assertEquals(emptyList(), state.friendDevices)
        assertFalse(state.selectedDeviceIds.contains("current-device"))
    }

    @Test
    fun replaceFriendDevicesMapsRealFriendDevicesAndDropsStaleSelection() {
        val friendDevice = FriendDevice(
            ownerUserId = "user-cavan",
            deviceId = "01HV7Q9B5G8Y2N0P3R4S5T6V7W",
            platform = "ios",
            deviceName = "Cavan 的 iPhone",
            ed25519PubB64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            x25519PubB64 = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
            appVersion = "1.0",
            lastSeenAt = 1_746_000_000,
            online = true,
        )

        val state = SendPageState.initial(currentDeviceName = "Pixel")
            .copy(selectedDeviceIds = setOf("friend-${friendDevice.deviceId}", "missing-device"))
            .replaceFriendDevices(listOf(friendDevice))

        assertEquals(
            listOf(
                SendDevice(
                    id = "friend-${friendDevice.deviceId}",
                    name = "Cavan 的 iPhone",
                    group = SendDeviceGroup.Friend,
                    subtitle = "iOS · 在线",
                    isSample = false,
                    host = null,
                    port = null,
                    platformHint = "ios",
                    transportPath = com.piko.app.domain.SendTransportPath.P2P,
                    receiverUserId = "user-cavan",
                    receiverDeviceId = friendDevice.deviceId,
                    receiverEd25519PubB64 = friendDevice.ed25519PubB64,
                    receiverX25519PubB64 = friendDevice.x25519PubB64,
                    online = true,
                ),
            ),
            state.friendDevices,
        )
        assertEquals(setOf("friend-${friendDevice.deviceId}"), state.selectedDeviceIds)
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
    fun addSelectedMediaDeduplicatesAndRemoveMediaUpdatesWaitingArea() {
        val first = SendMediaItem(
            id = "content://media/1",
            displayName = "IMG_1.jpg",
            uri = "content://media/1",
            fileType = SendFileType.Image,
        )
        val second = SendMediaItem(
            id = "content://media/2",
            displayName = "VID_2.mov",
            uri = "content://media/2",
            fileType = SendFileType.Video,
        )

        val state = SendPageState.initial(currentDeviceName = "Pixel")
            .addSelectedMedia(listOf(first, second, first))

        assertEquals(listOf(first, second), state.selectedMediaItems)
        assertEquals(listOf(second), state.removeSelectedMedia(first.id).selectedMediaItems)
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
        val image = SendMediaItem(
            id = "content://image/1",
            displayName = "IMG_1.jpg",
            uri = "content://image/1",
            sizeBytes = 2048,
            fileType = SendFileType.Image,
        )
        val file = SendFileItem(
            id = "content://file/report.pdf",
            displayName = "report.pdf",
            sizeBytes = 1024,
            fileType = SendFileType.Document,
            sourceUri = "content://file/report.pdf",
        )
        val state = SendPageState.initial(currentDeviceName = "Pixel")
            .addSelectedMedia(listOf(image))
            .addSelectedFiles(listOf(file))

        assertEquals("IMG_1.jpg + 1 个文件", state.transferSummaryTitle)
        assertEquals(3072, state.transferTotalBytes)
    }

    @Test
    fun clearSelectedItemsEmptiesMediaAndFilesButKeepsTargets() {
        val device = SendDevice(id = "device-a", name = "A", group = SendDeviceGroup.Lan, host = "192.168.1.2", port = 42001)
        val media = SendMediaItem(
            id = "content://media/1",
            displayName = "VID_1.mov",
            uri = "content://media/1",
            sizeBytes = 10,
            fileType = SendFileType.Video,
        )
        val file = SendFileItem(
            id = "content://file/a.txt",
            displayName = "a.txt",
            sizeBytes = 20,
            fileType = SendFileType.Other,
            sourceUri = "content://file/a.txt",
        )

        val state = SendPageState.initial(currentDeviceName = "Pixel")
            .copy(lanDevices = listOf(device))
            .toggleDeviceSelection(device.id)
            .addSelectedMedia(listOf(media))
            .addSelectedFiles(listOf(file))
            .clearSelectedItems()

        assertEquals(emptyList(), state.selectedMediaItems)
        assertEquals(emptyList(), state.selectedFiles)
        assertEquals(setOf(device.id), state.selectedDeviceIds)
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

        assertEquals(SendTransferStatus.Idle, canceled.activeTransfer.status)
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
