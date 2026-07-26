package org.fossify.contacts

import android.content.Context
import org.fossify.commons.FossifyApp
import org.fossify.contacts.sync.localdb.EncryptedDatabases

/**
 * 自定义 Application，唯一的作用是在**任何人碰数据库之前**把 SQLCipher 装好。
 *
 * 为什么必须放在 attachBaseContext 而不是 onCreate：
 *
 *   Android 的启动顺序是
 *       Application.attachBaseContext()
 *     → ContentProvider.onCreate()          ← 我们的 MyContactsContentProvider 在这里
 *     → Application.onCreate()
 *
 *   放 onCreate 的话，ContentProvider 已经先一步用明文方式把数据库打开了，
 *   之后再塞加密实例就晚了 —— 那个进程里会同时存在两个指向同一个文件的连接，
 *   一个明文一个加密，行为不可预测。
 *
 * 记得在 AndroidManifest 里把 android:name 从
 *   org.fossify.commons.FossifyApp
 * 改成
 *   .ContactsApp
 */
class ContactsApp : FossifyApp() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 失败不会抛异常，只会退回明文并把原因记在 EncryptedDatabases.lastError 里。
        // 设置页应当把这个状态显示出来，不能静默降级。
        EncryptedDatabases.install(this)
    }
}
