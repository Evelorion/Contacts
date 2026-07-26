package org.fossify.contacts.sync.localdb

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.fossify.commons.databases.ContactsDatabase
import java.lang.reflect.Field

/**
 * 把 SQLCipher 装进本机的两个数据库。
 *
 * ── 为什么要用反射 ────────────────────────────────────────────────
 *
 * 私密联系人存在 commons 的 `local_contacts.db` 里，而 `ContactsDatabase`
 * 是 `org.fossify:commons` 这个 Maven 依赖里的类。它的 `getInstance()`
 * 内部直接 `Room.databaseBuilder(...).build()`，没有留任何注入 openHelperFactory
 * 的口子，我们也没法在外面改它。
 *
 * 但它把实例存在 companion object 的一个静态字段里，而且是「为 null 才创建」。
 * 所以只要在**任何人第一次调用 getInstance() 之前**，把我们自己用 SQLCipher
 * 工厂建好的实例塞进那个字段，之后全 App 拿到的就都是加密版本 ——
 * commons 里的 LocalContactsHelper、ContactsHelper 全都会走加密库，一行都不用改。
 *
 * 这条路的好处是不用动构建结构。缺点也很明确，见 [install] 的注释。
 * 更稳的做法是把 commons 拉成 composite build 后打个小补丁，
 * 见 docs/LOCAL_DB_ENCRYPTION.md 的「方案 B」。
 *
 * ── 时机 ──────────────────────────────────────────────────────
 *
 * 必须在 `Application.attachBaseContext()` 里调用 [install]。
 * 不能放 `onCreate()`：ContentProvider 的 onCreate 比 Application.onCreate 早，
 * 而我们的 MyContactsContentProvider 会读数据库。
 */
object EncryptedDatabases {

    private const val TAG = "EncryptedDatabases"
    private const val CONTACTS_DB = "local_contacts.db"

    @Volatile
    private var installed = false

    @Volatile
    var lastError: String = ""
        private set

    /** 本次进程里 commons 的库到底有没有跑在加密模式上。设置页应当显示这个。 */
    @Volatile
    var contactsDbEncrypted = false
        private set

    /**
     * 本次装载实际生效的那把口令，只在内存里。
     *
     * 必须留一份：PASSPHRASE 模式下 Keystore 里没有口令，
     * 同步库要建加密工厂时没别的地方拿。
     * 进程结束就没了，这正是 PASSPHRASE 模式能挡住 root 的原因之一。
     */
    @Volatile
    private var activePassphrase: ByteArray? = null

    /**
     * 幂等，可以重复调用。
     *
     * MainActivity 里有一处 `ContactsDatabase.destroyInstance()`，它会把静态字段
     * 清成 null，下次 getInstance() 就会重新建一个**明文**实例。
     * 所以那一行后面必须再调一次这个方法，见 INTEGRATION.md。
     *
     * 失败时不抛异常，而是退回明文并把原因记进 [lastError]。
     * 理由：加密失败就让 App 打不开通讯录，比不加密更糟。
     * 但这个状态必须让用户看到，不能静默降级。
     */
    @Synchronized
    fun install(context: Context) {
        if (installed && holdsOurInstance()) return

        try {
            System.loadLibrary("sqlcipher")
        } catch (e: Throwable) {
            lastError = "SQLCipher 原生库加载失败：${e.message}"
            Log.e(TAG, lastError, e)
            return
        }

        val passphrase = try {
            DatabaseKey.getOrCreate(context)
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            // 开了屏幕锁保护但还没验证。这不是错误，调用方应当拉起验证后重试。
            lastError = "需要先通过屏幕锁验证才能打开通讯录"
            Log.i(TAG, lastError)
            return
        } catch (e: Exception) {
            lastError = "无法取得数据库口令：${e.message}"
            Log.e(TAG, lastError, e)
            return
        }

        try {
            // 第一次启用时，把已有的明文库原地转成加密库
            if (!DatabaseKey.isEnabled(context)) {
                DatabaseEncryptionMigrator.encryptInPlace(context, CONTACTS_DB, passphrase)
                DatabaseKey.markEnabled(context)
            }

            val factory = SupportOpenHelperFactory(passphrase)
            val db = buildContactsDatabase(context, factory)
            injectIntoCommons(db)

            activePassphrase = passphrase
            installed = true
            contactsDbEncrypted = true
            lastError = ""
            Log.i(TAG, "本机联系人数据库已切换到加密模式")
        } catch (e: Exception) {
            lastError = "数据库加密启用失败：${e.message}"
            contactsDbEncrypted = false
            Log.e(TAG, lastError, e)
        }
    }

    /**
     * 强制用当前的口令重新装一次。
     * 切换加密模式之后必须调 —— installed 标记还是 true，
     * 但持有的实例是用旧口令打开的，直接用会解不开新加密的文件。
     */
    @Synchronized
    fun reinstall(context: Context) {
        installed = false
        contactsDbEncrypted = false
        activePassphrase = null
        install(context)
    }

    /**
     * 把内存里的口令抹掉并断开加密层。
     * PASSPHRASE 模式下用户主动锁定时调用。
     */
    @Synchronized
    fun lock() {
        activePassphrase?.fill(0)
        activePassphrase = null
        installed = false
        contactsDbEncrypted = false
    }

    /**
     * PASSPHRASE 模式专用：用主口令派生出的口令装上加密层。
     * 和 install 的区别是口令不来自 Keystore，而是调用方现算的。
     */
    @Synchronized
    fun installWithKey(context: Context, passphrase: ByteArray) {
        if (!runCatching { System.loadLibrary("sqlcipher") }.isSuccess) {
            lastError = "SQLCipher 原生库加载失败"
            return
        }
        try {
            val db = buildContactsDatabase(context, SupportOpenHelperFactory(passphrase))
            injectIntoCommons(db)
            activePassphrase = passphrase
            installed = true
            contactsDbEncrypted = true
            lastError = ""
        } catch (e: Exception) {
            lastError = "用主口令打开数据库失败：${e.message}"
            contactsDbEncrypted = false
            Log.e(TAG, lastError, e)
        }
    }

    /**
     * 给我们自己的同步库用。这个库的 builder 在我们手里，不需要反射。
     *
     * 用的是本次装载时实际生效的那把口令，而不是重新去 Keystore 取 ——
     * PASSPHRASE 模式下 Keystore 里根本没有口令，取不到。
     */
    fun openHelperFactory(context: Context): SupportSQLiteOpenHelper.Factory? {
        if (!installed || !contactsDbEncrypted) return null
        val passphrase = activePassphrase ?: return null
        return try {
            SupportOpenHelperFactory(passphrase)
        } catch (e: Exception) {
            Log.e(TAG, "同步库的加密工厂创建失败，退回明文", e)
            null
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 复刻 commons 里 getInstance() 的 builder 配置。
     *
     * 这里必须和 commons 保持一致，否则 Room 会因为 schema 版本或迁移路径对不上而崩。
     * 升级 commons 版本时**要回来核对这段**——这是反射方案最主要的维护成本。
     * 当前对齐的是 commons 6.1.6（ContactsDatabase version = 3）。
     */
    private fun buildContactsDatabase(
        context: Context,
        factory: SupportSQLiteOpenHelper.Factory,
    ): ContactsDatabase = Room.databaseBuilder(
        context.applicationContext,
        ContactsDatabase::class.java,
        CONTACTS_DB,
    )
        .openHelperFactory(factory)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN photo_uri TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN ringtone TEXT DEFAULT ''")
        }
    }

    /**
     * 把实例塞进 commons 的静态字段。
     *
     * Kotlin 把 companion object 里的私有属性编译成外部类上的静态字段，
     * 但这属于实现细节，不同 Kotlin 版本可能变。所以两个类都找一遍，
     * 按「类型能装下 ContactsDatabase」来认字段，而不是按名字硬编码 ——
     * 名字会被 R8 改掉，类型不会。
     */
    private fun injectIntoCommons(database: ContactsDatabase) {
        val field = findInstanceField()
            ?: throw IllegalStateException(
                "在 ContactsDatabase 里找不到存实例的静态字段，" +
                    "可能是 commons 版本变了。请改用 composite build 方案（见文档）"
            )
        field.isAccessible = true
        field.set(companionInstanceOrNull(), database)
    }

    private fun holdsOurInstance(): Boolean = try {
        val field = findInstanceField() ?: return false
        field.isAccessible = true
        field.get(companionInstanceOrNull()) != null
    } catch (e: Exception) {
        false
    }

    private fun findInstanceField(): Field? {
        val candidates = buildList {
            addAll(ContactsDatabase::class.java.declaredFields.toList())
            ContactsDatabase::class.java.declaredClasses
                .firstOrNull { it.simpleName == "Companion" }
                ?.let { addAll(it.declaredFields.toList()) }
        }
        return candidates.firstOrNull { ContactsDatabase::class.java.isAssignableFrom(it.type) }
    }

    private fun companionInstanceOrNull(): Any? = try {
        val companionClass = ContactsDatabase::class.java.declaredClasses
            .firstOrNull { it.simpleName == "Companion" }
        val field = findInstanceField()
        // 静态字段 set 的第一个参数会被忽略；实例字段才需要 Companion 对象
        if (field != null && java.lang.reflect.Modifier.isStatic(field.modifiers)) {
            null
        } else {
            ContactsDatabase::class.java.getDeclaredField("Companion")
                .apply { isAccessible = true }
                .get(null)
                ?: companionClass?.getDeclaredConstructor()?.apply { isAccessible = true }?.newInstance()
        }
    } catch (e: Exception) {
        null
    }
}
