package org.fossify.contacts.sync.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import org.fossify.contacts.sync.localdb.EncryptedDatabases

/**
 * 同步层自己的数据库，和 commons 的 contacts.db 分开。
 *
 * 为什么必须分开：commons 的 LocalContact 是外部依赖里的 Room 实体，
 * 我们既不能给它加列，也不该在它的 schema 上做迁移 —— 那样每次 commons
 * 升版本都可能撞车。同步需要的所有额外状态都放这里，通过 localId 关联。
 *
 * 这个库里存的是明文（base_payload 是完整的联系人快照）。
 * 它和 commons 的联系人库是同一个保密等级，靠 App 私有目录 + 关掉 allowBackup 保护，
 * 不额外加密。真要做本地静态加密，得整个换成 SQLCipher，见设计文档「已知缺口」。
 */

@Entity(tableName = "sync_records")
data class SyncRecordEntity(
    /** 跨设备稳定的联系人 id，由创建它的那台设备生成。 */
    @PrimaryKey val uuid: String,

    /** commons contacts 表的自增主键。0 表示本机当前没有这条（远端新建还没落地，或已删除）。 */
    val localId: Int,

    /** 服务端版本号。推送时当 baseRev 用；0 表示服务端还没有这条。 */
    val rev: Int,

    /** 上次同步成功时的完整快照，三方合并的共同祖先。 */
    val basePayload: String,

    /** basePayload 的哈希，用来快速判断本机有没有改过，不用每次都做字符串比较。 */
    val baseHash: String,

    /** 本机已删除，等着把墓碑推上去。 */
    val deletedLocally: Boolean = false,

    /** 有待推送的本地改动。 */
    val dirty: Boolean = false,

    /** 上次合并时两边都改过的字段，用逗号分隔，供 UI 提示用户确认。 */
    val conflictFields: String = "",

    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    /** 已经处理到的账号级序列号，下次拉取从这里往后要。 */
    val lastSeq: Long = 0,
    val lastSyncAt: Long = 0,
    val lastError: String = "",
    /** 服务端当前的 seq，用来在 UI 上显示「还差多少条没同步」。 */
    val serverSeq: Long = 0,

    /**
     * 同步清单自己的版本号。0 表示还没写过清单。
     * 服务端返回的清单 rev 比这个小，说明整份清单被回滚了。
     */
    val manifestRev: Int = 0,

    /**
     * 上一轮清单校验发现的问题，人话形式，用换行分隔。
     * 非空意味着服务器给的数据不完整 —— UI 上必须显眼地报出来，
     * 这不是「同步慢了点」，是「你的服务器可能不老实」。
     */
    val manifestIssues: String = "",
)

/**
 * 号码 → 联系人的盲索引。只存在本机，不上传。
 * 电话 App 来电时拿号码算出同样的索引值，就能查到是谁，
 * 而不需要在数据库里放一列可搜索的明文号码。
 */
@Entity(tableName = "blind_index", primaryKeys = ["idx", "localId"])
data class BlindIndexEntity(
    val idx: String,
    val localId: Int,
    val uuid: String,
)

/** 已经上传过的头像，避免每次同步都重传几百 KB。 */
@Entity(tableName = "blob_state")
data class BlobStateEntity(
    @PrimaryKey val hash: String,
    val uploaded: Boolean,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface SyncDao {

    @Query("SELECT * FROM sync_records WHERE uuid = :uuid")
    fun getByUuid(uuid: String): SyncRecordEntity?

    @Query("SELECT * FROM sync_records WHERE localId = :localId LIMIT 1")
    fun getByLocalId(localId: Int): SyncRecordEntity?

    @Query("SELECT * FROM sync_records")
    fun getAll(): List<SyncRecordEntity>

    @Query("SELECT * FROM sync_records WHERE dirty = 1 OR deletedLocally = 1 ORDER BY updatedAt LIMIT :limit")
    fun getPending(limit: Int): List<SyncRecordEntity>

    @Query("SELECT COUNT(*) FROM sync_records WHERE dirty = 1 OR deletedLocally = 1")
    fun countPending(): Int

    @Query("SELECT * FROM sync_records WHERE conflictFields != ''")
    fun getConflicted(): List<SyncRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(record: SyncRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(records: List<SyncRecordEntity>)

    @Query("DELETE FROM sync_records WHERE uuid = :uuid")
    fun deleteByUuid(uuid: String)

    @Query("UPDATE sync_records SET dirty = 1, updatedAt = :now WHERE localId = :localId")
    fun markDirtyByLocalId(localId: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE sync_records SET deletedLocally = 1, dirty = 1, localId = 0, updatedAt = :now WHERE localId = :localId")
    fun markDeletedByLocalId(localId: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE sync_records SET conflictFields = '' WHERE uuid = :uuid")
    fun clearConflict(uuid: String)

    // -------- 同步状态 --------

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun getState(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putState(state: SyncStateEntity)

    // -------- 盲索引 --------

    @Query("SELECT * FROM blind_index WHERE idx = :idx")
    fun lookupIndex(idx: String): List<BlindIndexEntity>

    @Query("DELETE FROM blind_index WHERE localId = :localId")
    fun clearIndexFor(localId: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putIndex(entries: List<BlindIndexEntity>)

    @Query("DELETE FROM blind_index")
    fun clearAllIndex()

    // -------- 头像 --------

    @Query("SELECT * FROM blob_state WHERE hash = :hash")
    fun getBlob(hash: String): BlobStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putBlob(blob: BlobStateEntity)

    @Query("SELECT hash FROM blob_state WHERE uploaded = 0")
    fun getUnuploadedBlobs(): List<String>

    // -------- 关闭同步时的清理 --------

    @Query("DELETE FROM sync_records")
    fun wipeRecords()

    @Query("DELETE FROM blob_state")
    fun wipeBlobs()

    @Query("DELETE FROM sync_state")
    fun wipeState()
}

@Database(
    entities = [
        SyncRecordEntity::class,
        SyncStateEntity::class,
        BlindIndexEntity::class,
        BlobStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SyncDatabase : RoomDatabase() {

    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile
        private var instance: SyncDatabase? = null

        fun get(context: Context): SyncDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SyncDatabase::class.java,
                "fc_sync.db"
            ).apply {
                // 这个库里的 base_payload 是完整的联系人明文快照，
                // 和 commons 的联系人库同一个保密等级，所以要一起加密。
                // 返回 null 表示本机没启用加密（或启用失败），那就退回明文 ——
                // 让 App 打不开比不加密更糟，但这个状态会显示在设置页里。
                EncryptedDatabases.openHelperFactory(context)?.let { openHelperFactory(it) }
            }.build().also { instance = it }
        }

        /**
         * 关掉连接但保留文件。
         * 切换本地加密模式时必须先调它 —— rekey 是原地操作，
         * 有连接开着的话会失败或者产生半新半旧的库。
         * 下次 get() 会用新口令重新打开。
         */
        fun closeInstance() {
            synchronized(this) {
                runCatching { instance?.close() }
                instance = null
            }
        }

        /** 用户关掉同步时，把本地所有同步痕迹清干净。 */
        fun destroy(context: Context) {
            synchronized(this) {
                instance?.close()
                instance = null
                context.applicationContext.deleteDatabase("fc_sync.db")
            }
        }
    }
}
