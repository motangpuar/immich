package app.alextran.immich.upload

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Client for the RUFH (IETF resumable upload) protocol as implemented by
 * Immich PR #22385.
 *
 * Protocol summary:
 *   POST  /upload              → create session, returns Location
 *   HEAD  /upload/:id          → query committed offset
 *   PATCH /upload/:id          → append chunk (application/partial-upload)
 *   DELETE /upload/:id         → cancel
 *
 * All requests are forced to HTTP/1.1. The server emits interim 1xx
 * responses (100 Continue, 104 Upload Resumption Supported). OkHttp's
 * HTTP/2 state machine mishandles multiple 1xx frames — takeHeaders()
 * stalls until the read timeout fires. HTTP/1.1 handles them natively.
 */
class RufhUploader(
    baseClient: OkHttpClient,
    private val baseUrl: String,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    companion object {
        const val DEFAULT_CHUNK = 20L * 1024 * 1024   // 20 MiB starting point
        const val MIN_CHUNK     = 1L * 1024 * 1024    // 1 MiB floor
        const val MAX_CHUNK     = 90L * 1024 * 1024   // 90 MiB ceiling (Cloudflare 100 MB cap)
        const val INTEROP_VER   = 8
        const val PARTIAL_CT    = "application/partial-upload"
        const val GROWTH_FACTOR = 1.1                  // conservative adaptive growth
    }

    /**
     * Performs the full resumable upload and returns the upload/session ID
     * assigned by the server. On Immich, this ID equals the asset ID.
     */
    fun upload(
        assetId: String,
        file: File,
        deviceId: String,
        onChunkComplete: (committed: Long, total: Long) -> Unit = { _, _ -> },
    ): String {
        val digest = sha1Base64(file)
        val uploadId = create(assetId, file, digest, deviceId)
    
        var offset = head(uploadId)
        if (offset < 0) error("Session expired immediately after create")
    
        var chunkSize = DEFAULT_CHUNK
    
        while (offset < file.length()) {
            val remaining = file.length() - offset
            val thisChunk = minOf(chunkSize, remaining)
            val isLast = offset + thisChunk >= file.length()
    
            val req = Request.Builder()
                .url("$baseUrl/upload/$uploadId")
                .header("upload-draft-interop-version", INTEROP_VER.toString())
                .header("upload-offset", offset.toString())
                .header("upload-complete", if (isLast) "?1" else "?0")
                .header("content-type", PARTIAL_CT)
                .patch(FileRangeBody(file, offset, thisChunk))
                .build()
    
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    in 200..204 -> {
                        offset += thisChunk
                        onChunkComplete(offset, file.length())
                        chunkSize = (chunkSize * GROWTH_FACTOR).toLong().coerceAtMost(MAX_CHUNK)
                    }
                    413, 524 -> {
                        chunkSize = (chunkSize / 2).coerceAtLeast(MIN_CHUNK)
                        if (chunkSize == MIN_CHUNK) error("Chunk floor reached at 413/524")
                    }
                    409 -> {
                        offset = resp.header("Upload-Offset")?.toLongOrNull()
                            ?: error("409 without Upload-Offset")
                    }
                    404 -> error("Session expired")
                    else -> error("PATCH ${resp.code}: ${resp.body?.string()}")
                }
            }
        }
        return uploadId
    }

    private fun create(assetId: String, file: File, digestSha1: String, deviceId: String): String {
        val now = java.time.Instant.ofEpochMilli(file.lastModified()).toString()
        val assetData = "device-asset-id=\"${esc(assetId)}\", " +
            "device-id=\"${esc(deviceId)}\", " +
            "filename=\"${esc(file.name)}\", " +
            "file-created-at=\"$now\", " +
            "file-modified-at=\"$now\""

        val req = Request.Builder()
            .url("$baseUrl/upload")
            .header("upload-draft-interop-version", INTEROP_VER.toString())
            .header("upload-complete", "?0")
            .header("upload-length", file.length().toString())
            .header("repr-digest", "sha=:$digestSha1:")
            .header("x-immich-asset-data", assetData)
            .post(ByteArray(0).toRequestBody(null))
            .build()

        android.util.Log.i("RufhUploader", "POST $baseUrl/upload")
        android.util.Log.i("RufhUploader", "  headers=${req.headers}")
        android.util.Log.i("RufhUploader", "  digest=sha=:$digestSha1:  len=${file.length()}")

        client.newCall(req).execute().use { resp ->
            android.util.Log.i("RufhUploader", "POST response: ${resp.code}")
            android.util.Log.i("RufhUploader", "  headers=${resp.headers}")

            val body = resp.body?.string() ?: "<no body>"
            android.util.Log.i("RufhUploader", "  body=$body")

            val loc = resp.header("Location")
                ?: error("Create ${resp.code}: missing Location. body=$body")

            return loc.substringAfterLast('/')
        }
    }

    private fun head(uploadId: String): Long {
        val req = Request.Builder()
            .url("$baseUrl/upload/$uploadId")
            .header("upload-draft-interop-version", INTEROP_VER.toString())
            .head()
            .build()

        client.newCall(req).execute().use { resp ->
            return when (resp.code) {
                200, 204 -> resp.header("Upload-Offset")?.toLongOrNull() ?: 0L
                404 -> -1L
                else -> error("HEAD ${resp.code}")
            }
        }
    }

    private fun sha1Base64(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            var n: Int
            while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return Base64.getEncoder().encodeToString(md.digest())
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * Streams the byte range [offset, offset + length) directly from [file]
 * into the request body. No temp files, no in-memory copies.
 */
class FileRangeBody(
    private val file: File,
    private val offset: Long,
    private val length: Long,
) : RequestBody() {

    override fun contentType() = "application/partial-upload".toMediaType()
    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        file.inputStream().use { input ->
            // InputStream.skip may skip fewer bytes than requested;
            // loop until we've advanced to the desired offset.
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }

            val buf = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n == -1) break
                sink.write(buf, 0, n)
                remaining -= n
            }
        }
    }
}
