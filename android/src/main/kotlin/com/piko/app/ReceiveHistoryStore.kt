package com.piko.app

import android.content.Context
import java.io.File
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal class ReceiveHistoryStore(
    private val file: File,
) {
    fun load(): List<ReceiveHistoryItem> {
        if (!file.isFile) {
            return emptyList()
        }
        return runCatching {
            JSONArray(file.readText())
                .toReceiveHistoryItems()
        }.getOrElse {
            emptyList()
        }
    }

    fun save(history: List<ReceiveHistoryItem>) {
        file.parentFile?.mkdirs()
        file.writeText(history.toJsonArray().toString())
    }

    companion object {
        private const val FILE_NAME = "receive_history.json"

        fun fromContext(context: Context): ReceiveHistoryStore {
            return ReceiveHistoryStore(File(context.applicationContext.filesDir, FILE_NAME))
        }
    }
}

private fun List<ReceiveHistoryItem>.toJsonArray(): JSONArray {
    val items = JSONArray()
    forEach { item ->
        items.put(
            JSONObject()
                .put("id", item.id)
                .put("receivedAtEpochMillis", item.receivedAtEpochMillis)
                .put("receivedAtLabel", item.receivedAtLabel)
                .put("sourceDeviceName", item.sourceDeviceName)
                .put("fileCount", item.fileCount)
                .put("files", item.files.toFilesJsonArray()),
        )
    }
    return items
}

private fun List<ReceiveHistoryFile>.toFilesJsonArray(): JSONArray {
    val files = JSONArray()
    forEach { file ->
        files.put(
            JSONObject()
                .put("displayName", file.displayName)
                .put("fileType", file.fileType.name)
                .put("sizeBytes", file.sizeBytes)
                .put("thumbnailBytes", file.thumbnailBytes?.toBase64() ?: JSONObject.NULL)
                .put("savedUri", file.savedUri ?: JSONObject.NULL),
        )
    }
    return files
}

private fun JSONArray.toReceiveHistoryItems(): List<ReceiveHistoryItem> {
    return buildList {
        for (index in 0 until length()) {
            val item = getJSONObject(index)
            val files = item.getJSONArray("files").toReceiveHistoryFiles()
            if (files.isNotEmpty()) {
                add(
                    ReceiveHistoryItem(
                        id = item.getString("id"),
                        receivedAtEpochMillis = item.getLong("receivedAtEpochMillis"),
                        receivedAtLabel = item.optString("receivedAtLabel"),
                        sourceDeviceName = item.optString("sourceDeviceName"),
                        fileCount = files.size,
                        files = files,
                    ),
                )
            }
        }
    }
}

private fun JSONArray.toReceiveHistoryFiles(): List<ReceiveHistoryFile> {
    return buildList {
        for (index in 0 until length()) {
            val file = getJSONObject(index)
            add(
                ReceiveHistoryFile(
                    displayName = file.getString("displayName"),
                    fileType = file.optString("fileType").toReceiveFileType(),
                    sizeBytes = file.optLong("sizeBytes").coerceAtLeast(0L),
                    thumbnailBytes = file.optString("thumbnailBytes").takeIf { it.isNotBlank() }?.fromBase64(),
                    savedUri = file.optString("savedUri").takeIf { it.isNotBlank() },
                ),
            )
        }
    }
}

private fun String.toReceiveFileType(): ReceiveFileType {
    return ReceiveFileType.entries.firstOrNull { it.name == this } ?: ReceiveFileType.Other
}

private fun ByteArray.toBase64(): String {
    return Base64.getEncoder().encodeToString(this)
}

private fun String.fromBase64(): ByteArray {
    return Base64.getDecoder().decode(this)
}
