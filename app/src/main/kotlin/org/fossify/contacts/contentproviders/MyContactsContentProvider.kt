package org.fossify.contacts.contentproviders

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import org.fossify.commons.helpers.LocalContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.contacts.extensions.config
import org.fossify.contacts.helpers.PrivacyGuard
import org.fossify.contacts.sync.VaultManager
import org.fossify.contacts.sync.crypto.Crypto
import org.fossify.contacts.sync.crypto.VaultCrypto
import org.fossify.contacts.sync.db.SyncDatabase
import org.fossify.contacts.sync.model.ContactPayload

/**
 * 私密联系人的对外出口。只有同签名且在白名单里的自家 App 能读到内容，
 * 其它任何调用方拿到的都是空 Cursor（不是异常，避免对方闪退暴露存在性）。
 *
 * 两条 URI：
 *   content://org.fossify.commons.contactsprovider           全量列表，电话 App 启动时拉一次
 *   content://org.fossify.commons.contactsprovider/number/*  按号码盲索引查单个，来电时用
 *
 * 加号码查询这条路是因为：来电时电话 App 只需要知道「这个号码是谁」，
 * 让它把整个通讯录拉过去再自己匹配，既慢又等于把全部联系人复制了一份到另一个进程。
 */
class MyContactsContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "ContactsProvider"

        const val AUTHORITY = "org.fossify.commons.contactsprovider"
        private const val MATCH_ALL = 1
        private const val MATCH_BY_NUMBER = 2

        private val privateContactColumns = arrayOf(
            MyContactsContentProvider.COL_RAW_ID,
            MyContactsContentProvider.COL_CONTACT_ID,
            MyContactsContentProvider.COL_NAME,
            MyContactsContentProvider.COL_PHOTO_URI,
            MyContactsContentProvider.COL_PHONE_NUMBERS,
            MyContactsContentProvider.COL_BIRTHDAYS,
            MyContactsContentProvider.COL_ANNIVERSARIES,
        )

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            // commons 里的 CONTACTS_CONTENT_URI 是 content://AUTHORITY/contacts，
            // 三个路径都要认，否则老版本的电话 / 短信 App 会直接查不到东西。
            addURI(AUTHORITY, null, MATCH_ALL)
            addURI(AUTHORITY, "contacts", MATCH_ALL)
            addURI(AUTHORITY, "number/*", MATCH_BY_NUMBER)
        }
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = context ?: return emptyCursor()

        if (!context.config.showPrivateContacts) return emptyCursor()

        if (!PrivacyGuard.isCallerAllowed(context, callingPackage, context.config.privacyProtectionEnabled)) {
            Log.w(TAG, "拒绝了 ${callingPackage ?: "未知调用方"} 对私密联系人的访问")
            return emptyCursor()
        }

        return when (uriMatcher.match(uri)) {
            MATCH_BY_NUMBER -> queryByNumber(uri.lastPathSegment.orEmpty())
            else -> queryAll(selectionArgs)
        }
    }

    private fun queryAll(selectionArgs: Array<out String>?): Cursor {
        val context = context ?: return emptyCursor()
        val favoritesOnly = selectionArgs?.getOrNull(0) == "1"
        val withPhoneNumbersOnly = selectionArgs?.getOrNull(1)?.equals("1") ?: true

        val cursor = emptyCursor()
        val gson = Gson()
        LocalContactsHelper(context)
            .getPrivateSimpleContactsSync(favoritesOnly, withPhoneNumbersOnly)
            .forEach { contact ->
                cursor.newRow()
                    .add(MyContactsContentProvider.COL_RAW_ID, contact.rawId)
                    .add(MyContactsContentProvider.COL_CONTACT_ID, contact.contactId)
                    .add(MyContactsContentProvider.COL_NAME, contact.name)
                    .add(MyContactsContentProvider.COL_PHOTO_URI, contact.photoUri)
                    .add(MyContactsContentProvider.COL_PHONE_NUMBERS, gson.toJson(contact.phoneNumbers))
                    .add(MyContactsContentProvider.COL_BIRTHDAYS, gson.toJson(contact.birthdays))
                    .add(MyContactsContentProvider.COL_ANNIVERSARIES, gson.toJson(contact.anniversaries))
            }
        return cursor
    }

    /**
     * 按号码查。调用方传的是归一化后的号码明文（它本来就有这个号码，来电显示上就写着），
     * 这里用本机的盲索引密钥把它换算成索引值再查表。
     *
     * 保险库锁定时返回空 —— 没有 DEK 就算不出索引密钥。
     * 这也意味着开机后第一次来电可能查不到名字，直到用户解锁一次。
     * 这个取舍是刻意的：宁可少显示一个名字，也不把索引密钥常驻在没保护的地方。
     */
    private fun queryByNumber(rawNumber: String): Cursor {
        val context = context ?: return emptyCursor()
        val cursor = emptyCursor()

        val vault = VaultManager.get(context)
        val dek = vault.dek() ?: run {
            Log.i(TAG, "保险库锁定，按号码查询返回空")
            return cursor
        }
        val salt = vault.session.kdfSalt ?: return cursor

        val normalized = ContactPayload.normalizeNumber(Uri.decode(rawNumber))
        if (normalized.isEmpty()) return cursor

        val indexKey = VaultCrypto.deriveIndexKey(dek, salt)
        val matches = try {
            SyncDatabase.get(context).syncDao().lookupIndex(VaultCrypto.blindIndex(indexKey, normalized))
        } finally {
            Crypto.wipe(indexKey)
        }

        val gson = Gson()
        val helper = LocalContactsHelper(context)
        for (match in matches) {
            val contact = helper.getContactWithId(match.localId) ?: continue
            cursor.newRow()
                .add(MyContactsContentProvider.COL_RAW_ID, contact.rawId)
                .add(MyContactsContentProvider.COL_CONTACT_ID, contact.contactId)
                .add(MyContactsContentProvider.COL_NAME, contact.name)
                .add(MyContactsContentProvider.COL_PHOTO_URI, contact.photoUri)
                .add(MyContactsContentProvider.COL_PHONE_NUMBERS, gson.toJson(contact.phoneNumbers))
                .add(MyContactsContentProvider.COL_BIRTHDAYS, gson.toJson(contact.birthdays))
                .add(MyContactsContentProvider.COL_ANNIVERSARIES, gson.toJson(contact.anniversaries))
        }
        return cursor
    }

    // 这个 Provider 是只读的。写操作一律拒绝，不要因为「反正没人调」就返回成功 ——
    // 返回 1 会让调用方以为写成功了。
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun getType(uri: Uri): String = when (uriMatcher.match(uri)) {
        MATCH_BY_NUMBER -> "vnd.android.cursor.item/vnd.$AUTHORITY.contact"
        else -> "vnd.android.cursor.dir/vnd.$AUTHORITY.contact"
    }

    private fun emptyCursor() = MatrixCursor(privateContactColumns)
}
