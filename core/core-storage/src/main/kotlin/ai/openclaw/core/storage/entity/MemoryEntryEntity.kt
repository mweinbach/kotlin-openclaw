package ai.openclaw.core.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_entries",
    indices = [Index("source"), Index("createdAt")],
)
data class MemoryEntryEntity(
    @PrimaryKey
    val id: String,
    val content: String,
    val source: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: FloatArray? = null,
    val embeddingJson: String? = null,
    val metadataJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryEntryEntity) return false
        if (id != other.id) return false
        if (content != other.content) return false
        if (source != other.source) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false
        if (embeddingJson != other.embeddingJson) return false
        if (metadataJson != other.metadataJson) return false
        if (createdAt != other.createdAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + (embeddingJson?.hashCode() ?: 0)
        result = 31 * result + (metadataJson?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
