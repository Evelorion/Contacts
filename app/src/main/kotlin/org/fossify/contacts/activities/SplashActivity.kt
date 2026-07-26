package org.fossify.contacts.activities

import android.content.Intent
import org.fossify.commons.activities.BaseSplashActivity
import org.fossify.contacts.sync.VaultManager
import org.fossify.contacts.sync.work.SyncScheduler

class SplashActivity : BaseSplashActivity() {
    override fun initActivity() {
        // 已经配置过同步的话，把周期任务排上。
        // 幂等的：WorkManager 用 UPDATE 策略，重复调用不会排出多个任务。
        if (VaultManager.get(this).isConfigured) {
            runCatching { SyncScheduler.schedulePeriodic(this) }
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
