package app.alextran.immich.upload

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RufhSessionStore(ctx: Context) {

    private val file = File(ctx.filesDir, "rufh_sessions.json")
    private val lock = Any()

    // In-memory mirror. Kept in sync with disk on every mutation.
    private val sessions = mutableMapOf<String, RufhSession>()

    init {
        synchronized(lock) { loadFromDisk() }
    }

    fun save(session: RufhSession) {
        synchronized(lock) {
            sessions[session.assetId] = session
            writeToDisk()
        }
    }

    fun load(assetId: String): RufhSession? = synchronized(lock) { sessions[assetId] }

    fun loadByUploadId(uploadId: String): RufhSession? =
        synchronized(lock) { sessions.values.firstOrNull { it.uploadId == uploadId } }

    fun listAll(): List<RufhSession> = synchronized(lock) { sessions.values.toList() }

    fun remove(assetId: String) {
        synchronized(lock) {
            sessions.remove(assetId)
            writeToDisk()
        }
    }

    fun markOrphaned(assetId: String) {
        synchronized(lock) {
            val existing = sessions[assetId] ?: return
            sessions[assetId] = existing.copy(status = RufhSession.Status.ORPHANED)
            writeToDisk()
        }
    }

    // ── Disk I/O ──────────────────────────────────────────────────────

    private fun loadFromDisk() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("sessions") ?: return
            for (i in 0 until arr.length()) {
                val s = deserialize(arr.getJSONObject(i))
                sessions[s.assetId] = s
            }
        } catch (e: Exception) {
            Log.w("RufhSessionStore", "failed to read store, starting fresh: $e")
            file.delete()
        }
    }

    private fun writeToDisk() {
        val arr = JSONArray()
        for (s in sessions.values) arr.put(serialize(s))
        val root = JSONObject().put("sessions", arr)

        // Atomic write — write to .tmp, fsync, rename.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                // Fallback if rename fails (rare, filesystem-dependent)
                file.writeText(root.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e("RufhSessionStore", "failed to persist sessions: $e")
            tmp.delete()
        }
    }

    private fun serialize(s: RufhSession) = JSONObject().apply {
        put("assetId", s.assetId)
        put("uploadId", s.uploadId)
        put("filePath", s.filePath)
        put("fileSize", s.fileSize)
        put("fileMtime", s.fileMtime)
        put("serverUrl", s.serverUrl)
        put("committedOffset", s.committedOffset)
        put("chunkSize", s.chunkSize)
        put("reprDigestSha1", s.reprDigestSha1)
        put("createdAt", s.createdAt)
        put("status", s.status.name)
    }

    private fun deserialize(o: JSONObject) = RufhSession(
        assetId = o.getString("assetId"),
        uploadId = o.getString("uploadId"),
        filePath = o.getString("filePath"),
        fileSize = o.getLong("fileSize"),
        fileMtime = o.getLong("fileMtime"),
        serverUrl = o.getString("serverUrl"),
        committedOffset = o.getLong("committedOffset"),
        chunkSize = o.getLong("chunkSize"),
        reprDigestSha1 = o.optString("reprDigestSha1", ""),
        createdAt = o.getLong("createdAt"),
        status = RufhSession.Status.valueOf(o.optString("status", "ACTIVE")),
    )
}
