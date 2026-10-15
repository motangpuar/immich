package app.alextran.immich.upload

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
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
    private val store: RufhSessionStore? = null,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    companion object {
        private const val TAG = "RufhUploader"
        const val DEFAULT_CHUNK = 20L * 1024 * 1024   // 20 MiB starting point
        const val MIN_CHUNK     = 1L * 1024 * 1024    // 1 MiB floor
        const val MAX_CHUNK     = 40L * 1024 * 1024   // 90 MiB ceiling (Cloudflare 100 MB cap)
        const val INTEROP_VER   = 8
        const val PARTIAL_CT    = "application/partial-upload"
        const val GROWTH_FACTOR = 1.1                  // conservative adaptive growth
    }

    fun upload(
        assetId: String,
        file: File,
        deviceId: String,
        onCreated: (uploadId: String, chunkSize: Long) -> Unit = { _, _ -> },
        onChunkComplete: (committed: Long, total: Long, chunkSize: Long) -> Unit = { _, _, _ -> },
    ): String {
        val digest = sha1Base64(file)

        // If we've seen this assetId before, the session may still be alive.
        // HEAD it first; fall back to create if gone.
        val existing = store?.load(assetId)
        val uploadId: String = if (existing != null && existing.uploadId.isNotEmpty()) {
            // HEAD regardless of status. An ORPHANED record may still point at
            // a live server session — the last failure might have been local.
            val serverOffset = if (existing.uploadId.isNotEmpty()) {
                runCatching { head(existing.uploadId) }.getOrDefault(-1L)
            } else -1L

            if (serverOffset >= 0) {
                Log.i(TAG, "resuming session ${existing.uploadId} at offset $serverOffset")
                // Promote back to ACTIVE — the server confirmed it's alive.
                if (existing.status != RufhSession.Status.ACTIVE) {
                    store?.save(existing.copy(status = RufhSession.Status.ACTIVE))
                }
                existing.uploadId
            } else {
                Log.i(TAG, "stored session ${existing.uploadId} gone, creating fresh")
                store?.remove(assetId)
                val fresh = create(assetId, file, digest, deviceId)
                onCreated(fresh, DEFAULT_CHUNK)
                fresh
            }
        } else {
            val fresh = create(assetId, file, digest, deviceId)
            onCreated(fresh, DEFAULT_CHUNK)
            fresh
        }

        var offset = head(uploadId)
        if (offset < 0) error("Session expired immediately after create")
        Log.i(TAG, "head: offset=$offset")

        var chunkSize = existing?.chunkSize ?: DEFAULT_CHUNK

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

            Log.i(TAG, "PATCH offset=$offset size=$thisChunk complete=$isLast")

            executeWithRetry(req).use { resp ->
                Log.i(TAG, "PATCH response: ${resp.code}")
                when (resp.code) {
                    in 200..204 -> {
                        offset += thisChunk
                        chunkSize = (chunkSize * GROWTH_FACTOR).toLong().coerceAtMost(MAX_CHUNK)
                        onChunkComplete(offset, file.length(), chunkSize)
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
                    in 500..599 -> throw java.io.IOException("PATCH ${resp.code}")
                    else -> error("PATCH ${resp.code}: ${resp.body?.string()}")
                }
            }
        }
        return uploadId
    }
    
    private fun parseAssetId(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            org.json.JSONObject(body).optString("id").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
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

        Log.i("RufhUploader", "POST $baseUrl/upload")
        Log.i("RufhUploader", "  headers=${req.headers}")
        Log.i("RufhUploader", "  digest=sha=:$digestSha1:  len=${file.length()}")

        executeWithRetry(req).use { resp ->
            Log.i(TAG, "POST response: ${resp.code}")
            Log.i(TAG, "  headers=${resp.headers}")

            val body = resp.body?.string() ?: ""
            Log.i(TAG, "  body=$body")

            val loc = resp.header("Location")

            // 1. Location present → server has a session, resume it.
            //    This is the primary branch. 201 carries it for a fresh session;
            //    500 carries it when a session already exists. Both mean the
            //    same thing to us: here's the upload ID, HEAD it and continue.
            if (loc != null) {
                Log.i(TAG, "create: session $loc (code ${resp.code})")
                return loc.substringAfterLast('/')
            }

            // 2. No Location, but the server named the reason → orphaned.
            //    We know a session exists server-side but can't reach it.
            if (resp.code == 500 && body.contains("Incomplete asset already exists")) {
                throw OrphanedSessionException(assetId, body)
            }

            // 3. No Location on a 5xx → transient server error. Retry.
            if (resp.code in 500..599) {
                throw IOException("Create ${resp.code}: $body")
            }

            // 4. Anything else is unexpected.
            error("Create ${resp.code}: missing Location. body=$body")
        }
    }

    private fun executeWithRetry(request: Request, maxAttempts: Int = 3): Response {
        var lastException: IOException? = null
        for (attempt in 1..maxAttempts) {
            try {
                return client.newCall(request).execute()
            } catch (e: java.net.UnknownHostException) {
                lastException = e
            } catch (e: java.net.ConnectException) {
                lastException = e
            } catch (e: java.net.SocketTimeoutException) {
                lastException = e
            }
            if (attempt < maxAttempts) {
                Thread.sleep(1000L * attempt)  // 1s, then 2s
            }
        }
        throw lastException ?: IOException("executeWithRetry exhausted")
    }

    fun head(uploadId: String): Long {
        val req = Request.Builder()
            .url("$baseUrl/upload/$uploadId")
            .header("upload-draft-interop-version", INTEROP_VER.toString())
            .head()
            .build()

        executeWithRetry(req).use { resp ->
            return when (resp.code) {
                200, 204 -> resp.header("Upload-Offset")?.toLongOrNull() ?: 0L
                404 -> -1L
                in 500..599 -> throw java.io.IOException("HEAD ${resp.code}")
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

class OrphanedSessionException(
    val assetId: String,
    val serverMessage: String,
) : Exception("Orphaned RUFH session for $assetId: $serverMessage")

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
