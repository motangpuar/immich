package app.alextran.immich.upload

data class RufhSession(
    val assetId: String,
    val uploadId: String,
    val filePath: String,
    val fileSize: Long,
    val fileMtime: Long,
    val serverUrl: String,
    val committedOffset: Long,
    val chunkSize: Long,
    val reprDigestSha1: String,
    val createdAt: Long,
    val status: Status,
) {
    enum class Status { ACTIVE, ORPHANED }
}
