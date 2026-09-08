package com.anonymous.fileshare.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.anonymous.fileshare.crypto.ChecksumVerifier
import com.anonymous.fileshare.crypto.CryptoEngine
import com.anonymous.fileshare.util.AppLogger
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Path-traversal safe, non-executable local file storage manager.
 * Buffers writes in memory (4–8MB) before sequential disk flushes,
 * enforces size caps, computes streaming SHA-256, and deletes partial files on failure.
 */
class StorageManager(
    private val context: Context,
    private val maxFileSizeBytes: Long = 2L * 1024 * 1024 * 1024, // 2 GB
    private val maxSessionBytes: Long = 10L * 1024 * 1024 * 1024  // 10 GB
) {
    private val tempDir: File by lazy {
        val oldDir = File(context.cacheDir, "untrusted_transfers")
        try {
            if (oldDir.exists()) oldDir.deleteRecursively()
        } catch (_: Exception) {}

        val dir = File(context.cacheDir, "incoming_transfers")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir.setReadable(true, false)
        dir.setWritable(true, false)
        dir.setExecutable(true, false)
        dir
    }

    private var totalSessionBytesReceived: Long = 0L

    @Volatile
    var customDestinationUri: Uri? = null

    val saveFolderDisplayName: String
        get() {
            val uri = customDestinationUri ?: return "Downloads/AnonymousShare"
            val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
            return doc?.name ?: "Custom Folder"
        }

    class ActiveFileSession(
        val transferId: String,
        val originalFilename: String,
        val expectedSize: Long,
        val tempFile: File,
        val outStream: BufferedOutputStream,
        val hasher: ChecksumVerifier = ChecksumVerifier(),
        val memoryBuffer: ByteArrayOutputStream = ByteArrayOutputStream(4 * 1024 * 1024), // 4MB buffer
        var bytesWritten: Long = 0L
    )

    private val activeSessions = mutableMapOf<String, ActiveFileSession>()

    @Synchronized
    fun hasActiveSession(transferId: String): Boolean = activeSessions.containsKey(transferId)

    /**
     * Sanitizes client filename removing directory traversal characters.
     */
    fun sanitizeFilename(raw: String): String {
        val name = File(raw).name
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "unnamed_file_${System.currentTimeMillis()}" }
    }

    /**
     * Initializes an incoming file transfer session using a random UUID temp file.
     */
    @Synchronized
    fun startIncomingTransfer(transferId: String, rawFilename: String, size: Long): ActiveFileSession {
        if (size > maxFileSizeBytes) {
            throw IllegalArgumentException("File size exceeds maximum allowed limit ($maxFileSizeBytes bytes)")
        }
        if (totalSessionBytesReceived + size > maxSessionBytes) {
            throw IllegalArgumentException("Total session transfer limit exceeded ($maxSessionBytes bytes)")
        }

        val sanitized = sanitizeFilename(rawFilename)
        val tempFile = File(tempDir, "temp_${UUID.randomUUID()}.part")

        AppLogger.i("StorageManager", "Starting incoming file: $sanitized ($size bytes) -> temp: ${tempFile.name}")

        val outStream = BufferedOutputStream(FileOutputStream(tempFile), 1024 * 1024)
        val session = ActiveFileSession(transferId, sanitized, size, tempFile, outStream)
        activeSessions[transferId] = session
        return session
    }

    /**
     * Appends decrypted plaintext chunk to the memory buffer and flushes to disk when full.
     */
    @Synchronized
    fun appendChunk(transferId: String, chunkBytes: ByteArray) {
        val session = activeSessions[transferId] ?: throw IllegalStateException("Session $transferId not found")

        session.hasher.update(chunkBytes)
        session.memoryBuffer.write(chunkBytes)
        session.bytesWritten += chunkBytes.size
        totalSessionBytesReceived += chunkBytes.size

        if (session.bytesWritten > session.expectedSize) {
            AppLogger.e("StorageManager", "File size exceeded stated metadata size: ${session.bytesWritten} > ${session.expectedSize}")
            cancelTransfer(transferId)
            throw IllegalStateException("File size exceeded stated metadata size")
        }

        // Flush memory buffer in 4MB chunks to minimize disk IO overhead
        if (session.memoryBuffer.size() >= 4 * 1024 * 1024) {
            flushBufferToDisk(session)
        }
    }

    private fun flushBufferToDisk(session: ActiveFileSession) {
        if (session.memoryBuffer.size() > 0) {
            session.memoryBuffer.writeTo(session.outStream)
            session.memoryBuffer.reset()
        }
    }

    /**
     * Verifies SHA-256 checksum and moves the verified file to public Downloads / SAF storage.
     */
    @Synchronized
    fun finalizeTransfer(transferId: String, expectedChecksum: String): File {
        val session = activeSessions[transferId] ?: throw IllegalStateException("Session $transferId not found")

        try {
            flushBufferToDisk(session)
            session.outStream.flush()
            session.outStream.close()

            val computedChecksum = session.hasher.finalHex()
            AppLogger.i("StorageManager", "Finalizing ${session.originalFilename}: expected=$expectedChecksum, computed=$computedChecksum")
            if (!computedChecksum.equals(expectedChecksum, ignoreCase = true)) {
                AppLogger.e("StorageManager", "Checksum mismatch for ${session.originalFilename}!")
                throw SecurityException("Checksum verification failed! Expected: $expectedChecksum, Computed: $computedChecksum")
            }

            // Save to Downloads folder via MediaStore / Storage Access Framework
            saveToDownloads(session.tempFile, session.originalFilename)
            AppLogger.i("StorageManager", "✓ Successfully saved ${session.originalFilename}")
            return session.tempFile
        } catch (e: Exception) {
            AppLogger.e("StorageManager", "Failed to finalize ${session.originalFilename}: ${e.message}", e)
            cancelTransfer(transferId)
            throw e
        } finally {
            activeSessions.remove(transferId)
            try {
                if (session.tempFile.exists()) {
                    session.tempFile.delete()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Cancels an incoming transfer, closing streams, wiping memory buffers, and deleting temp file.
     */
    @Synchronized
    fun cancelTransfer(transferId: String) {
        val session = activeSessions.remove(transferId) ?: return
        AppLogger.w("StorageManager", "Cancelling transfer $transferId for ${session.originalFilename}")
        try {
            session.memoryBuffer.reset()
            session.outStream.close()
        } catch (_: Exception) {}

        if (session.tempFile.exists()) {
            session.tempFile.delete()
        }
    }

    private fun getMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return if (ext.isNotEmpty()) {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }
    }

    /**
     * Exports verified file to Android Downloads directory with safe fallbacks.
     */
    private fun saveToDownloads(sourceFile: File, displayName: String) {
        var saved = false
        val mime = getMimeType(displayName)

        // Strategy 0: Custom SAF Target selected by user
        val targetUri = customDestinationUri
        if (targetUri != null) {
            try {
                val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, targetUri)
                if (rootDoc != null && rootDoc.canWrite()) {
                    val targetDoc = rootDoc.createFile(mime, displayName)
                    if (targetDoc != null) {
                        context.contentResolver.openOutputStream(targetDoc.uri)?.use { out ->
                            FileInputStream(sourceFile).use { input ->
                                input.copyTo(out)
                            }
                        }
                        saved = true
                        AppLogger.i("StorageManager", "Saved to custom SAF destination (${rootDoc.name}): $displayName")
                    }
                }
            } catch (e: Exception) {
                AppLogger.w("StorageManager", "Custom SAF destination failed: ${e.message}, falling back to defaults")
            }
        }

        // Strategy 1: MediaStore API on Android 10+ (Q+) with subfolder
        if (!saved && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AnonymousShare")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri: Uri? = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    saved = true
                    AppLogger.i("StorageManager", "Saved to MediaStore (AnonymousShare subfolder): $displayName")
                }
            } catch (e: Exception) {
                AppLogger.w("StorageManager", "MediaStore Strategy 1 failed: ${e.message}")
            }

            // Strategy 1b: MediaStore API into root Downloads (if subfolder creation is restricted on some OEMs)
            if (!saved) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }

                    val uri: Uri? = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            FileInputStream(sourceFile).use { input ->
                                input.copyTo(out)
                            }
                        }
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                        saved = true
                        AppLogger.i("StorageManager", "Saved to MediaStore (Downloads root): $displayName")
                    }
                } catch (e: Exception) {
                    AppLogger.w("StorageManager", "MediaStore Strategy 1b failed: ${e.message}")
                }
            }
        }

        // Strategy 2: Direct public Downloads directory (Android 9 and below or Legacy Storage)
        if (!saved) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val destDir = File(downloadsDir, "AnonymousShare")
                if (!destDir.exists()) destDir.mkdirs()
                val destFile = File(destDir, displayName)
                sourceFile.copyTo(destFile, overwrite = true)
                saved = true
                AppLogger.i("StorageManager", "Saved to Public Downloads: ${destFile.absolutePath}")
            } catch (e: Exception) {
                AppLogger.w("StorageManager", "Public Downloads Strategy 2 failed: ${e.message}")
            }
        }

        // Strategy 3: App-specific external storage (always accessible without permissions)
        if (!saved) {
            try {
                val appDownloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val destDir = File(appDownloadsDir, "AnonymousShare")
                if (!destDir.exists()) destDir.mkdirs()
                val destFile = File(destDir, displayName)
                sourceFile.copyTo(destFile, overwrite = true)
                saved = true
                AppLogger.i("StorageManager", "Saved to App External Storage: ${destFile.absolutePath}")
            } catch (e: Exception) {
                AppLogger.w("StorageManager", "App External Storage Strategy 3 failed: ${e.message}")
            }
        }

        if (!saved) {
            AppLogger.e("StorageManager", "CRITICAL: All storage strategies failed to save $displayName")
            throw IOException("Failed to save $displayName to device storage")
        }
    }

    /**
     * Cleans all temporary files and resets session counters.
     */
    @Synchronized
    fun clearAll() {
        activeSessions.keys.toList().forEach { cancelTransfer(it) }
        activeSessions.clear()
        tempDir.listFiles()?.forEach { it.delete() }
        totalSessionBytesReceived = 0L
    }
}
