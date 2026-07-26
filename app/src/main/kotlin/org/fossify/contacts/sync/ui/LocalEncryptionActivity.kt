package org.fossify.contacts.sync.ui

import android.os.Bundle
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.contacts.R
import org.fossify.contacts.activities.SimpleActivity
import org.fossify.contacts.databinding.ActivityLocalEncryptionBinding
import org.fossify.contacts.sync.VaultManager
import org.fossify.contacts.sync.localdb.EncryptedDatabases
import org.fossify.contacts.sync.localdb.EncryptionMode
import java.util.concurrent.Executors

/**
 * 本地数据库加密的模式切换。
 *
 * 切换模式 = 给数据库换一把口令（SQLCipher 的 PRAGMA rekey）。
 * 这个操作会阻塞几百毫秒到几秒，而且中途被杀会留下半新半旧的库 ——
 * 所以：
 *   · 全程在后台线程跑
 *   · 切换期间禁用返回键，并明确提示不要退出
 *   · 底层 rekey 自带备份与还原，失败不会丢数据
 */
class LocalEncryptionActivity : SimpleActivity() {

    private val binding by lazy { ActivityLocalEncryptionBinding.inflate(layoutInflater) }
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var switching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    override fun onBackPressed() {
        // rekey 中途退出会留下半新半旧的库。虽然有备份兜底，
        // 但让用户等几秒比事后修复体验好得多。
        if (switching) {
            toast(R.string.local_encryption_switching)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun render() {
        val mode = EncryptionMode.current(this)
        binding.modeKeystore.isChecked = mode == EncryptionMode.KEYSTORE
        binding.modeScreenLock.isChecked = mode == EncryptionMode.KEYSTORE_SCREEN_LOCK
        binding.modePassphrase.isChecked = mode == EncryptionMode.PASSPHRASE
        binding.modeDesc.text = EncryptionMode.describe(mode)

        binding.encryptionStatus.text = when {
            EncryptedDatabases.contactsDbEncrypted -> "联系人数据库已加密"
            EncryptedDatabases.lastError.isNotEmpty() -> "未加密：${EncryptedDatabases.lastError}"
            else -> "未加密"
        }

        // 主口令模式依赖同步账号的 KDF 参数，没配置同步就用不了
        val canUsePassphrase = VaultManager.get(this).session.kdfSalt != null
        binding.modePassphrase.isEnabled = canUsePassphrase
        binding.passphraseUnavailable.beGoneIf(canUsePassphrase)
    }

    private fun setupListeners() {
        binding.modeKeystore.setOnClickListener { requestSwitch(EncryptionMode.KEYSTORE) }
        binding.modeScreenLock.setOnClickListener { requestSwitch(EncryptionMode.KEYSTORE_SCREEN_LOCK) }
        binding.modePassphrase.setOnClickListener { requestSwitch(EncryptionMode.PASSPHRASE) }
    }

    private fun requestSwitch(target: EncryptionMode) {
        val from = EncryptionMode.current(this)
        if (from == target) return

        // 涉及主口令模式的切换（进或出）都需要用户当场输一次主口令：
        // 进去时要用它派生新的库口令，出来时要用它解开现在的库。
        val needsPassphrase = target == EncryptionMode.PASSPHRASE || from == EncryptionMode.PASSPHRASE

        if (!needsPassphrase) {
            doSwitch(target, null)
            return
        }

        binding.passphrasePrompt.beVisibleIf(true)
        binding.passphraseConfirmButton.setOnClickListener {
            val passphrase = binding.passphraseInput.value
            if (passphrase.isBlank()) {
                toast("请输入主口令")
                return@setOnClickListener
            }
            binding.passphrasePrompt.beVisibleIf(false)
            binding.passphraseInput.setText("")

            if (target == EncryptionMode.PASSPHRASE) {
                ConfirmationDialog(
                    this,
                    "切到主口令模式后，每次打开通讯录都要输一次主口令。" +
                        "在那之前来电显示查不到私密联系人，后台同步也跑不了。\n\n确定要切换吗？",
                ) {
                    doSwitch(target, passphrase)
                }
            } else {
                doSwitch(target, passphrase)
            }
        }
    }

    private fun doSwitch(target: EncryptionMode, passphrase: String?) {
        switching = true
        busy(true)
        executor.execute {
            val error = try {
                EncryptionMode.switchTo(this, target, passphrase)
                null
            } catch (e: Exception) {
                e.message ?: "切换失败"
            }
            runOnUiThread {
                switching = false
                busy(false)
                toast(error ?: getString(R.string.local_encryption_switch_done))
                render()
            }
        }
    }

    private fun busy(isBusy: Boolean) {
        binding.progress.beVisibleIf(isBusy)
        binding.switchingHint.beVisibleIf(isBusy)
        binding.modeKeystore.isEnabled = !isBusy
        binding.modeScreenLock.isEnabled = !isBusy
        binding.modePassphrase.isEnabled = !isBusy && VaultManager.get(this).session.kdfSalt != null
    }
}
