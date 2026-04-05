package org.fossify.contacts.helpers

import android.content.Context
import org.fossify.commons.helpers.BaseConfig
import org.fossify.commons.helpers.SHOW_TABS

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    var showTabs: Int
        get() = prefs.getInt(SHOW_TABS, ALL_TABS_MASK)
        set(showTabs) = prefs.edit().putInt(SHOW_TABS, showTabs).apply()

    var autoBackupContactSources: Set<String>
        get() = prefs.getStringSet(AUTO_BACKUP_CONTACT_SOURCES, setOf())!!
        set(autoBackupContactSources) = prefs.edit().remove(AUTO_BACKUP_CONTACT_SOURCES).putStringSet(AUTO_BACKUP_CONTACT_SOURCES, autoBackupContactSources)
            .apply()

    var privacyProtectionEnabled: Boolean
        get() = prefs.getBoolean(PRIVACY_PROTECTION_ENABLED, true)
        set(privacyProtectionEnabled) = prefs.edit().putBoolean(PRIVACY_PROTECTION_ENABLED, privacyProtectionEnabled).apply()

    var privacyAllowedPackages: Set<String>
        get() = prefs.getStringSet(PRIVACY_ALLOWED_PACKAGES, setOf())?.toSet() ?: emptySet()
        set(privacyAllowedPackages) = prefs.edit().remove(PRIVACY_ALLOWED_PACKAGES).putStringSet(PRIVACY_ALLOWED_PACKAGES, privacyAllowedPackages).apply()

}
