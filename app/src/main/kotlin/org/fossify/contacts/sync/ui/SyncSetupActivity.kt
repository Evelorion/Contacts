package org.fossify.contacts.sync.ui

import org.fossify.contacts.ui.M3Theme
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.contacts.activities.SimpleActivity
import org.fossify.contacts.databinding.ActivitySyncSetupBinding
import org.fossify.contacts.helpers.PrivacyGuard
import org.fossify.contacts.sync.VaultManager
import org.fossify.contacts.sync.db.SyncDatabase
import org.fossify.contacts.sync.engine.SyncEngine
import org.fossify.contacts.sync.work.SyncScheduler
import java.util.concurrent.Executors

/**
 * 同步的配置与状态页。三个状态：
 *
 *   未配置    填服务器地址、用户名、主口令 → 注册或登录
 *   已锁定    有账号但内存里没有 DEK      → 输主口令，或用恢复码
 *   已解锁    显示同步状态和各项操作
 *
 * 所有网络和 KDF 操作都扔到后台线程 —— Argon2id 跑 64 MiB 在低端机上要一两秒，
 * 放主线程会直接 ANR。
 */
class SyncSetupActivity : SimpleActivity() {

    private val binding by lazy { ActivitySyncSetupBinding.inflate(layoutInflater) }
    private val executor = Executors.newSingleThreadExecutor()
    private val vault by lazy { VaultManager.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须在 super.onCreate() **之后** —— commons 的 BaseSimpleActivity
        // 会在它的 onCreate 里 setTheme() 把主题整个换掉，早于它设是白费的。
        M3Theme.apply(this)
        setContentView(binding.root)
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        val configured = vault.isConfigured
        val unlocked = vault.isUnlocked

        binding.syncSetupForm.beVisibleIf(!configured)
        binding.syncUnlockForm.beVisibleIf(configured && !unlocked)
        binding.syncStatusGroup.beVisibleIf(configured && unlocked)

        binding.syncFingerprint.text =
            "本机签名指纹\n${PrivacyGuard.ownCertificateFingerprint(this)}"

        binding.syncStorageWarning.beGoneIf(!vault.session.usingFallbackStorage)

        if (!configured || !unlocked) return

        executor.execute {
            val dao = SyncDatabase.get(this).syncDao()
            val state = dao.getState()
            val pending = dao.countPending()
            val conflicts = dao.getConflicted().size

            runOnUiThread {
                binding.syncAccount.text = "${vault.session.username} @ ${vault.session.baseUrl}"
                binding.syncLastRun.text = when {
                    state == null || state.lastSyncAt == 0L -> "还没有同步过"
                    else -> "上次同步 " + DateUtils.getRelativeTimeSpanString(state.lastSyncAt)
                }
                binding.syncPending.text = "待上传 $pending 条"
                binding.syncConflicts.text = "需要确认的冲突 $conflicts 条"
                binding.syncConflicts.beVisibleIf(conflicts > 0)
                binding.syncError.text = state?.lastError.orEmpty()
                binding.syncError.beGoneIf(state?.lastError.isNullOrEmpty())
            }
        }
    }

    // ---------------------------------------------------------------- 按钮

    private fun setupButtons() {
        binding.syncRegisterButton.setOnClickListener { submitSetup(isRegister = true) }
        binding.syncLoginButton.setOnClickListener { submitSetup(isRegister = false) }
        binding.syncUnlockButton.setOnClickListener { submitUnlock(useRecoveryCode = false) }
        binding.syncRecoveryButton.setOnClickListener { submitUnlock(useRecoveryCode = true) }

        binding.syncNowButton.setOnClickListener {
            busy(true)
            executor.execute {
                val report = SyncEngine(this).sync()
                runOnUiThread {
                    busy(false)
                    toast(
                        if (report.ok) "同步完成：拉取 ${report.pulled}，上传 ${report.pushed}"
                        else report.error
                    )
                    render()
                }
            }
        }

        binding.syncLockButton.setOnClickListener {
            vault.lock()
            toast("已锁定")
            render()
        }

        binding.syncSignOutButton.setOnClickListener {
            ConfirmationDialog(
                this,
                "断开这台设备的同步？本机的联系人不会被删除，服务器上的数据也保留。" +
                    "之后要重新登录才能继续同步。",
            ) {
                busy(true)
                executor.execute {
                    vault.signOut()
                    SyncScheduler.cancelAll(this)
                    SyncDatabase.destroy(this)
                    runOnUiThread {
                        busy(false)
                        toast("已断开")
                        render()
                    }
                }
            }
        }
    }

    private fun submitSetup(isRegister: Boolean) {
        val url = binding.syncServerUrl.value.trim()
        val username = binding.syncUsername.value.trim()
        val passphrase = binding.syncPassphrase.value
        val invite = binding.syncInviteCode.value.trim()
        val deviceName = android.os.Build.MODEL.ifEmpty { "Android" }
        val cache = binding.syncCacheOnDevice.isChecked
        val screenLock = binding.syncRequireScreenLock.isChecked

        vault.session.validateUrl(url)?.let { toast(it); return }
        if (username.isEmpty()) { toast("请填写用户名"); return }
        if (passphrase.length < 10) {
            // 这不是形式主义：主口令是唯一挡在攻击者和你全部联系人之间的东西，
            // 而且它不像登录密码那样有服务端限流兜底 —— 拖库之后是离线爆破。
            toast("主口令至少 10 个字符。它是唯一能解开数据的东西，请用一句只有你知道的话")
            return
        }

        busy(true)
        executor.execute {
            try {
                if (isRegister) {
                    val result = vault.register(url, username, passphrase, invite, deviceName, cache, screenLock)
                    runOnUiThread {
                        busy(false)
                        showRecoveryCode(result.recoveryCode)
                    }
                } else {
                    vault.login(url, username, passphrase, deviceName, cache, screenLock)
                    afterUnlock("登录成功")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy(false)
                    toast(e.message ?: "操作失败")
                }
            }
        }
    }

    private fun submitUnlock(useRecoveryCode: Boolean) {
        val secret = if (useRecoveryCode) binding.syncRecoveryCode.value else binding.syncUnlockPassphrase.value
        if (secret.isBlank()) { toast(if (useRecoveryCode) "请输入恢复码" else "请输入主口令"); return }

        val cache = binding.syncCacheOnDevice.isChecked
        val screenLock = binding.syncRequireScreenLock.isChecked

        busy(true)
        executor.execute {
            try {
                if (useRecoveryCode) {
                    vault.unlockWithRecoveryCode(secret, cache, screenLock)
                } else {
                    vault.unlockWithPassphrase(secret, cache, screenLock)
                }
                afterUnlock("已解锁")
            } catch (e: Exception) {
                runOnUiThread {
                    busy(false)
                    toast(e.message ?: "解锁失败")
                }
            }
        }
    }

    private fun afterUnlock(message: String) {
        val report = SyncEngine(this).sync()
        runOnUiThread {
            busy(false)
            SyncScheduler.schedulePeriodic(this)
            toast(if (report.ok) message else report.error)
            render()
        }
    }

    /**
     * 恢复码只在这一刻出现。服务器上存的是「用恢复码才能解开的包裹」，
     * 恢复码本身谁都没有，关掉这个对话框就再也拿不回来了。
     */
    private fun showRecoveryCode(code: String) {
        binding.syncRecoveryDisplay.text = code
        binding.syncRecoveryPanel.visibility = View.VISIBLE
        binding.syncSetupForm.visibility = View.GONE

        binding.syncRecoveryDoneButton.setOnClickListener {
            ConfirmationDialog(
                this,
                "确认已经把恢复码抄下来了？\n\n" +
                    "它不会再显示第二次。忘记主口令又没有恢复码时，" +
                    "服务器上的数据谁都解不开 —— 包括我们自己。",
            ) {
                binding.syncRecoveryPanel.visibility = View.GONE
                SyncScheduler.schedulePeriodic(this)
                render()
            }
        }
    }

    private fun busy(isBusy: Boolean) {
        binding.syncProgress.beVisibleIf(isBusy)
        binding.syncRegisterButton.isEnabled = !isBusy
        binding.syncLoginButton.isEnabled = !isBusy
        binding.syncUnlockButton.isEnabled = !isBusy
        binding.syncRecoveryButton.isEnabled = !isBusy
        binding.syncNowButton.isEnabled = !isBusy
    }
}
