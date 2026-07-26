package org.fossify.contacts

import android.content.Context
import org.fossify.commons.FossifyApp
import org.fossify.contacts.sync.localdb.EncryptedDatabases
import org.fossify.contacts.ui.M3Theme

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

    override fun onCreate() {
        super.onCreate()
        // 把用户选的深浅模式还给 AppCompat。必须在任何 Activity 创建之前生效，
        // 否则第一个页面会先按系统默认渲染一帧再翻过来，肉眼能看到闪一下。
        M3Theme.applyDarkModeGlobally(this)
        // 把 M3 的颜色写进 commons 的颜色偏好。commons 有大量代码在运行时
        // 给 View 重新着色（updateTextColors / getProperTextColor / …），
        // 那些值来自 SharedPreferences，和主题属性无关。不同步的话快速滚动条、
        // 对话框、空列表提示会保持 commons 的蓝，和 M3 的紫拼在一起。
        M3Theme.syncCommonsColors(this)
    }

}
