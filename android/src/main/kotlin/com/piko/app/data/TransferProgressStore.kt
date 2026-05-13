package com.piko.app.data

import android.content.Context
import com.piko.app.domain.TransferV3File
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

class TransferProgressStore private constructor(
    private val rootDir: File,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {
    constructor(context: Context) : this(File(context.filesDir, "piko-transfers"), Unit)

    internal constructor(rootDir: File) : this(rootDir, Unit)

    fun transferDir(transferId: String): File = File(rootDir, transferId)

    fun completedBitmapB64(transferId: String, manifestHashB64: String): String? {
        val progress = loadRaw(transferId) ?: return null
        if (progress.optString("manifest_hash_b64") != manifestHashB64) return null
        val sanitized = sanitizeCompletedChunks(progress, transferDir(transferId)) ?: return null
        return Base64.getEncoder().encodeToString(sanitized.toString().toByteArray(Charsets.UTF_8))
    }

    fun completedChunks(transferId: String, manifestHashB64: String, manifest: List<TransferV3File>): Map<Int, BooleanArray> {
        val progress = loadRaw(transferId) ?: return emptyMap()
        if (progress.optString("manifest_hash_b64") != manifestHashB64) return emptyMap()
        val files = progress.optJSONArray("files") ?: return emptyMap()
        val result = mutableMapOf<Int, BooleanArray>()
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: continue
            val manifestFile = manifest.firstOrNull { it.index == file.optInt("index") } ?: continue
            if (manifestFile.fileHash.base64() != file.optString("file_hash_b64")) continue
            val completed = file.optJSONArray("completed")
                ?.toBooleanArray(manifestFile.chunkCount)
                ?: BooleanArray(manifestFile.chunkCount)
            val partFile = File(transferDir(transferId), "${manifestFile.index}.part")
            completed.forEachIndexed { chunkIndex, value ->
                if (value && !partFile.hasChunk(manifestFile, chunkIndex)) {
                    completed[chunkIndex] = false
                }
            }
            result[manifestFile.index] = completed
        }
        return result.takeIf { chunks -> chunks.values.any { bitmap -> bitmap.any { it } } } ?: emptyMap()
    }

    fun save(transferId: String, manifestHashB64: String, manifest: List<TransferV3File>, completedChunks: Map<Int, BooleanArray>) {
        val dir = transferDir(transferId)
        require(dir.exists() || dir.mkdirs()) { "无法创建跨网续传目录" }
        val files = JSONArray()
        manifest.forEach { file ->
            val completed = JSONArray()
            completedChunks[file.index]?.forEach { value -> completed.put(value) }
            files.put(
                JSONObject()
                    .put("index", file.index)
                    .put("display_name", file.displayName)
                    .put("size_bytes", file.sizeBytes)
                    .put("chunk_size", file.chunkSize)
                    .put("chunk_count", file.chunkCount)
                    .put("file_hash_b64", file.fileHash.base64())
                    .put("completed", completed),
            )
        }
        File(dir, progressFileName).writeText(
            JSONObject()
                .put("transfer_id", transferId)
                .put("manifest_hash_b64", manifestHashB64)
                .put("files", files)
                .put("updated_at", System.currentTimeMillis())
                .toString(),
            Charsets.UTF_8,
        )
    }

    fun clear(transferId: String) {
        transferDir(transferId).deleteRecursively()
    }

    private fun loadRaw(transferId: String): JSONObject? {
        val file = File(transferDir(transferId), progressFileName)
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun sanitizeCompletedChunks(progress: JSONObject, dir: File): JSONObject? {
        val files = progress.optJSONArray("files") ?: return null
        val sanitizedFiles = JSONArray()
        var hasCompletedChunk = false
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: continue
            val fileIndex = file.optInt("index")
            val sizeBytes = file.optLong("size_bytes", -1L)
            val chunkSize = file.optInt("chunk_size", -1)
            val chunkCount = file.optInt("chunk_count", -1)
            if (fileIndex < 0 || sizeBytes < 0L || chunkSize <= 0 || chunkCount < 0) continue
            val partFile = File(dir, "$fileIndex.part")
            val completed = file.optJSONArray("completed")?.toBooleanArray(chunkCount) ?: BooleanArray(chunkCount)
            val sanitizedCompleted = JSONArray()
            completed.forEachIndexed { chunkIndex, value ->
                val isValid = value && partFile.hasChunk(sizeBytes, chunkSize, chunkIndex)
                if (isValid) hasCompletedChunk = true
                sanitizedCompleted.put(isValid)
            }
            sanitizedFiles.put(JSONObject(file.toString()).put("completed", sanitizedCompleted))
        }
        if (!hasCompletedChunk) return null
        return JSONObject(progress.toString()).put("files", sanitizedFiles)
    }

    companion object {
        private const val progressFileName = "progress.json"

        fun decodeCompletedBitmap(bitmapB64: String?): Map<Int, Set<Int>> {
            if (bitmapB64.isNullOrBlank()) return emptyMap()
            val json = runCatching {
                JSONObject(String(Base64.getDecoder().decode(bitmapB64), Charsets.UTF_8))
            }.getOrNull() ?: return emptyMap()
            val files = json.optJSONArray("files") ?: return emptyMap()
            val result = mutableMapOf<Int, Set<Int>>()
            for (index in 0 until files.length()) {
                val file = files.optJSONObject(index) ?: continue
                val completed = file.optJSONArray("completed") ?: continue
                result[file.optInt("index")] = completed.toCompletedIndexSet()
            }
            return result
        }
    }
}

private fun JSONArray.toBooleanArray(expectedSize: Int): BooleanArray =
    BooleanArray(expectedSize) { index -> optBoolean(index, false) }

private fun JSONArray.toCompletedIndexSet(): Set<Int> =
    buildSet {
        for (index in 0 until length()) {
            if (optBoolean(index, false)) add(index)
        }
    }

private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

private fun File.hasChunk(file: TransferV3File, chunkIndex: Int): Boolean =
    hasChunk(file.sizeBytes, file.chunkSize, chunkIndex)

private fun File.hasChunk(sizeBytes: Long, chunkSize: Int, chunkIndex: Int): Boolean {
    if (!isFile || chunkIndex < 0) return false
    val offset = chunkIndex.toLong() * chunkSize.toLong()
    if (offset >= sizeBytes) return false
    val expectedLength = minOf(chunkSize.toLong(), sizeBytes - offset)
    return length() >= offset + expectedLength
}
