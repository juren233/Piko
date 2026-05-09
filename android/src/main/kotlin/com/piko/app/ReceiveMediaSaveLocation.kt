package com.piko.app

enum class ReceiveMediaSaveLocation(
    val storageValue: String,
    val label: String,
) {
    Folder("folder", "文件夹"),
    Album("album", "相册");

    fun destinationFor(mimeType: String?): ReceiveSaveDestination {
        return if (this == Album && mimeType.isImageOrVideoMimeType()) {
            ReceiveSaveDestination.Album
        } else {
            ReceiveSaveDestination.Folder
        }
    }

    companion object {
        fun fromStorageValue(value: String?): ReceiveMediaSaveLocation {
            return entries.firstOrNull { it.storageValue == value } ?: Folder
        }
    }
}

enum class ReceiveSaveDestination {
    Folder,
    Album,
}

private fun String?.isImageOrVideoMimeType(): Boolean {
    return this?.startsWith("image/") == true || this?.startsWith("video/") == true
}
