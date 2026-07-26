package org.fossify.contacts.activities

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.contacts.Contact
import org.fossify.contacts.R
import org.fossify.contacts.ui.themeColor
import org.fossify.contacts.ui.M3Theme
import org.fossify.contacts.sync.net.SessionStore
import org.fossify.contacts.dialogs.FilterContactSourcesDialog
import org.fossify.contacts.dialogs.ChangeSortingDialog
import org.fossify.commons.dialogs.ChangeViewTypeDialog
import com.google.android.material.R as MaterialR
import android.widget.TextView
import android.widget.LinearLayout
import android.view.LayoutInflater
import org.fossify.contacts.databinding.ActivitySettingsBinding
import org.fossify.contacts.dialogs.ExportContactsDialog
import org.fossify.contacts.dialogs.ManageAutoBackupsDialog
import org.fossify.contacts.dialogs.ManageVisibleFieldsDialog
import org.fossify.contacts.dialogs.ManageVisibleTabsDialog
import org.fossify.contacts.extensions.*
import org.fossify.contacts.helpers.VcfExporter
import java.io.OutputStream
import java.util.Locale
import kotlin.system.exitProcess
import org.fossify.contacts.sync.VaultManager
import org.fossify.contacts.sync.localdb.EncryptedDatabases
import org.fossify.contacts.sync.localdb.EncryptionMode
import org.fossify.contacts.sync.ui.LocalEncryptionActivity
import org.fossify.contacts.sync.ui.SyncSetupActivity

class SettingsActivity : SimpleActivity() {
    companion object {
        private const val PICK_IMPORT_SOURCE_INTENT = 1
        private const val PICK_EXPORT_FILE_INTENT = 2
    }

    private val binding by viewBinding(ActivitySettingsBinding::inflate)

    private var ignoredExportContactSources = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须在 super.onCreate() **之后** —— commons 的 BaseSimpleActivity
        // 会在它的 onCreate 里 setTheme() 把主题整个换掉，早于它设是白费的。
        M3Theme.apply(this)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.settingsNestedScrollview))
        setupMaterialScrollListener(binding.settingsNestedScrollview, binding.settingsAppbar)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)

        setupCustomizeColors()
        setupManageShownContactFields()
        setupManageShownTabs()
        setupFontSize()
        setupUseEnglish()
        setupLanguage()
        setupShowContactThumbnails()
        setupShowPhoneNumbers()
        setupEnableNumberFormatting()
        setupShowContactsWithNumbers()
        setupStartNameWithSurname()
        setupMergeDuplicateContacts()
        setupShowCallConfirmation()
        setupShowDialpadButton()
        setupShowPrivateContacts()
        setupPrivacyProtection()
        setupMigrateContactsToPrivateStorage()
        setupOnContactClick()
        setupDefaultTab()
        setupEnableAutomaticBackups()
        setupManageAutomaticBackups()
        setupExportContacts()
        setupImportContacts()
        setupSync()
        setupLocalEncryption()
        setupM3Account()
        setupM3Palette()
        setupM3DarkMode()
        setupM3DisplayOptions()
        updateTextColors(binding.settingsHolder)

        arrayOf(
            binding.settingsColorCustomizationSectionLabel,
            binding.settingsPrivacySectionLabel,
            binding.settingsGeneralSettingsLabel,
            binding.settingsMainScreenLabel,
            binding.settingsListViewLabel,
            binding.settingsBackupsLabel,
            binding.settingsMigratingLabel,
            binding.settingsSyncSectionLabel
        ).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
    }

    /**
     * 加密同步的入口。状态文字在这里填 —— 用户不点进去也能看出同步在不在跑。
     */
    private fun setupSync() {
        val vault = VaultManager.get(this)
        binding.settingsSyncStatus.text = when {
            !vault.isConfigured -> getString(R.string.sync_summary)
            !vault.isUnlocked -> "已配置，保险库锁定中"
            else -> "已启用 · ${vault.session.username}"
        }
        binding.settingsSyncHolder.setOnClickListener {
            startActivity(Intent(this, SyncSetupActivity::class.java))
        }
    }

    private fun setupLocalEncryption() {
        binding.settingsLocalEncryptionStatus.text = when {
            EncryptedDatabases.contactsDbEncrypted -> "已加密（${EncryptionMode.current(this).name}）"
            EncryptedDatabases.lastError.isNotEmpty() -> "未加密：${EncryptedDatabases.lastError}"
            else -> getString(R.string.local_encryption_summary)
        }
        binding.settingsLocalEncryptionHolder.setOnClickListener {
            startActivity(Intent(this, LocalEncryptionActivity::class.java))
        }
    }

    private fun setupCustomizeColors() {
        binding.settingsColorCustomizationHolder.setOnClickListener {
            startCustomizationActivity()
        }
    }

    private fun setupManageShownContactFields() {
        binding.settingsManageContactFieldsHolder.setOnClickListener {
            ManageVisibleFieldsDialog(this) {}
        }
    }

    private fun setupManageShownTabs() {
        binding.settingsManageShownTabsHolder.setOnClickListener {
            ManageVisibleTabsDialog(this)
        }
    }

    private fun setupDefaultTab() {
        binding.settingsDefaultTab.text = getDefaultTabText()
        binding.settingsDefaultTabHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(TAB_CONTACTS, getString(org.fossify.commons.R.string.contacts_tab)),
                RadioItem(TAB_FAVORITES, getString(org.fossify.commons.R.string.favorites_tab)),
                RadioItem(TAB_GROUPS, getString(org.fossify.commons.R.string.groups_tab)),
                RadioItem(TAB_LAST_USED, getString(org.fossify.commons.R.string.last_used_tab))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.defaultTab) {
                config.defaultTab = it as Int
                binding.settingsDefaultTab.text = getDefaultTabText()
            }
        }
    }

    private fun getDefaultTabText() = getString(
        when (baseConfig.defaultTab) {
            TAB_CONTACTS -> org.fossify.commons.R.string.contacts_tab
            TAB_FAVORITES -> org.fossify.commons.R.string.favorites_tab
            TAB_GROUPS -> org.fossify.commons.R.string.groups_tab
            else -> org.fossify.commons.R.string.last_used_tab
        }
    )

    private fun setupFontSize() {
        binding.settingsFontSize.text = getFontSizeText()
        binding.settingsFontSizeHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(FONT_SIZE_SMALL, getString(org.fossify.commons.R.string.small)),
                RadioItem(FONT_SIZE_MEDIUM, getString(org.fossify.commons.R.string.medium)),
                RadioItem(FONT_SIZE_LARGE, getString(org.fossify.commons.R.string.large)),
                RadioItem(FONT_SIZE_EXTRA_LARGE, getString(org.fossify.commons.R.string.extra_large))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.fontSize) {
                config.fontSize = it as Int
                binding.settingsFontSize.text = getFontSizeText()
            }
        }
    }

    private fun setupUseEnglish() {
        binding.settingsUseEnglishHolder.beVisibleIf((config.wasUseEnglishToggled || Locale.getDefault().language != "en") && !isTiramisuPlus())
        binding.settingsUseEnglish.isChecked = config.useEnglish
        binding.settingsUseEnglishHolder.setOnClickListener {
            binding.settingsUseEnglish.toggle()
            config.useEnglish = binding.settingsUseEnglish.isChecked
            exitProcess(0)
        }
    }

    private fun setupLanguage() {
        binding.settingsLanguage.text = Locale.getDefault().displayLanguage
        binding.settingsLanguageHolder.beVisibleIf(isTiramisuPlus())
        binding.settingsLanguageHolder.setOnClickListener {
            launchChangeAppLanguageIntent()
        }
    }

    private fun setupShowContactThumbnails() {
        binding.settingsShowContactThumbnails.isChecked = config.showContactThumbnails
        binding.settingsShowContactThumbnailsHolder.setOnClickListener {
            binding.settingsShowContactThumbnails.toggle()
            config.showContactThumbnails = binding.settingsShowContactThumbnails.isChecked
        }
    }

    private fun setupEnableNumberFormatting(){
        binding.settingsFormatPhoneNumbers.isChecked = config.formatPhoneNumbers
        binding.settingsFormatPhoneNumbersHolder.setOnClickListener{
            binding.settingsFormatPhoneNumbers.toggle()
            config.formatPhoneNumbers = binding.settingsFormatPhoneNumbers.isChecked
        }
    }

    private fun setupShowPhoneNumbers() {
        binding.settingsShowPhoneNumbers.isChecked = config.showPhoneNumbers
        binding.settingsShowPhoneNumbersHolder.setOnClickListener {
            binding.settingsShowPhoneNumbers.toggle()
            config.showPhoneNumbers = binding.settingsShowPhoneNumbers.isChecked
        }
    }

    private fun setupShowContactsWithNumbers() {
        binding.settingsShowOnlyContactsWithNumbers.isChecked = config.showOnlyContactsWithNumbers
        binding.settingsShowOnlyContactsWithNumbersHolder.setOnClickListener {
            binding.settingsShowOnlyContactsWithNumbers.toggle()
            config.showOnlyContactsWithNumbers = binding.settingsShowOnlyContactsWithNumbers.isChecked
        }
    }

    private fun setupStartNameWithSurname() {
        binding.settingsStartNameWithSurname.isChecked = config.startNameWithSurname
        binding.settingsStartNameWithSurnameHolder.setOnClickListener {
            binding.settingsStartNameWithSurname.toggle()
            config.startNameWithSurname = binding.settingsStartNameWithSurname.isChecked
        }
    }

    private fun setupShowDialpadButton() {
        binding.settingsShowDialpadButton.isChecked = config.showDialpadButton
        binding.settingsShowDialpadButtonHolder.setOnClickListener {
            binding.settingsShowDialpadButton.toggle()
            config.showDialpadButton = binding.settingsShowDialpadButton.isChecked
        }
    }

    private fun setupShowPrivateContacts() {
        binding.settingsShowPrivateContacts.isChecked = config.showPrivateContacts
        binding.settingsShowPrivateContactsHolder.setOnClickListener {
            binding.settingsShowPrivateContacts.toggle()
            config.showPrivateContacts = binding.settingsShowPrivateContacts.isChecked
        }
    }

    private fun setupPrivacyProtection() {
        binding.settingsPrivacyProtection.isChecked = config.privacyProtectionEnabled
        binding.settingsPrivacyProtectionHolder.setOnClickListener {
            binding.settingsPrivacyProtection.toggle()
            config.privacyProtectionEnabled = binding.settingsPrivacyProtection.isChecked
            if (binding.settingsPrivacyProtection.isChecked) {
                config.lastUsedContactSource = SMT_PRIVATE
            }
        }
    }

    private fun setupMigrateContactsToPrivateStorage() {
        binding.contactsMigrateToPrivateHolder.setOnClickListener {
            handlePermission(PERMISSION_READ_CONTACTS) { canRead ->
                if (!canRead) {
                    return@handlePermission
                }

                handlePermission(PERMISSION_WRITE_CONTACTS) { canWrite ->
                    if (!canWrite) {
                        return@handlePermission
                    }

                    ConfirmationDialog(this, getString(R.string.migrate_contacts_to_private_confirmation)) {
                        migrateContactsToPrivateStorage()
                    }
                }
            }
        }
    }

    private fun migrateContactsToPrivateStorage() {
        toast(R.string.migrating_contacts_to_private)
        val contactsHelper = ContactsHelper(this)
        contactsHelper.getContacts(true) { contacts ->
            ensureBackgroundThread {
                val publicContacts = ArrayList(contacts.filterNot { it.isPrivate() })
                if (publicContacts.isEmpty()) {
                    runOnUiThread {
                        toast(R.string.no_public_contacts_to_migrate)
                    }
                    return@ensureBackgroundThread
                }

                val migratedPublicContacts = ArrayList<Contact>()
                publicContacts.forEach { publicContact ->
                    val privateCopy = publicContact.copy(
                        id = 0,
                        source = SMT_PRIVATE,
                        contactId = 0,
                        thumbnailUri = ""
                    )

                    if (contactsHelper.insertContact(privateCopy)) {
                        migratedPublicContacts.add(publicContact)
                    }
                }

                val deletedPublicCopies = migratedPublicContacts.isNotEmpty() && contactsHelper.deleteContacts(ArrayList(migratedPublicContacts))
                config.lastUsedContactSource = SMT_PRIVATE

                runOnUiThread {
                    when {
                        migratedPublicContacts.isEmpty() -> toast(R.string.migrate_contacts_to_private_failed)
                        deletedPublicCopies && migratedPublicContacts.size == publicContacts.size ->
                            toast(getString(R.string.migrate_contacts_to_private_success, migratedPublicContacts.size))
                        else -> toast(getString(R.string.migrate_contacts_to_private_partial, migratedPublicContacts.size))
                    }
                }
            }
        }
    }

    private fun setupOnContactClick() {
        binding.settingsOnContactClick.text = getOnContactClickText()
        binding.settingsOnContactClickHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(ON_CLICK_CALL_CONTACT, getString(org.fossify.commons.R.string.call_contact)),
                RadioItem(ON_CLICK_VIEW_CONTACT, getString(org.fossify.commons.R.string.view_contact)),
                RadioItem(ON_CLICK_EDIT_CONTACT, getString(org.fossify.commons.R.string.edit_contact))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.onContactClick) {
                config.onContactClick = it as Int
                binding.settingsOnContactClick.text = getOnContactClickText()
            }
        }
    }

    private fun getOnContactClickText() = getString(
        when (config.onContactClick) {
            ON_CLICK_CALL_CONTACT -> org.fossify.commons.R.string.call_contact
            ON_CLICK_VIEW_CONTACT -> org.fossify.commons.R.string.view_contact
            else -> org.fossify.commons.R.string.edit_contact
        }
    )

    private fun setupShowCallConfirmation() {
        binding.settingsShowCallConfirmation.isChecked = config.showCallConfirmation
        binding.settingsShowCallConfirmationHolder.setOnClickListener {
            binding.settingsShowCallConfirmation.toggle()
            config.showCallConfirmation = binding.settingsShowCallConfirmation.isChecked
        }
    }

    private fun setupMergeDuplicateContacts() {
        binding.settingsMergeDuplicateContacts.isChecked = config.mergeDuplicateContacts
        binding.settingsMergeDuplicateContactsHolder.setOnClickListener {
            binding.settingsMergeDuplicateContacts.toggle()
            config.mergeDuplicateContacts = binding.settingsMergeDuplicateContacts.isChecked
        }
    }

    private fun setupEnableAutomaticBackups() {
        binding.settingsBackupsLabel.beVisibleIf(isRPlus())
        binding.settingsEnableAutomaticBackupsHolder.beVisibleIf(isRPlus())
        binding.settingsEnableAutomaticBackups.isChecked = config.autoBackup
        binding.settingsEnableAutomaticBackupsHolder.setOnClickListener {
            val wasBackupDisabled = !config.autoBackup
            if (wasBackupDisabled) {
                ManageAutoBackupsDialog(
                    activity = this,
                    onSuccess = {
                        enableOrDisableAutomaticBackups(true)
                        scheduleNextAutomaticBackup()
                    }
                )
            } else {
                cancelScheduledAutomaticBackup()
                enableOrDisableAutomaticBackups(false)
            }
        }
    }

    private fun setupManageAutomaticBackups() {
        binding.settingsManageAutomaticBackupsHolder.beVisibleIf(isRPlus() && config.autoBackup)
        binding.settingsManageAutomaticBackupsHolder.setOnClickListener {
            ManageAutoBackupsDialog(
                activity = this,
                onSuccess = {
                    scheduleNextAutomaticBackup()
                }
            )
        }
    }

    private fun enableOrDisableAutomaticBackups(enable: Boolean) {
        config.autoBackup = enable
        binding.settingsEnableAutomaticBackups.isChecked = enable
        binding.settingsManageAutomaticBackupsHolder.beVisibleIf(enable)
    }

    private fun setupExportContacts() {
        binding.contactsExportHolder.setOnClickListener {
            tryExportContacts()
        }
    }

    private fun setupImportContacts() {
        binding.contactsImportHolder.setOnClickListener {
            tryImportContacts()
        }
    }

    private fun tryImportContacts() {
        if (isQPlus()) {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/x-vcard"

                try {
                    startActivityForResult(this, PICK_IMPORT_SOURCE_INTENT)
                } catch (e: ActivityNotFoundException) {
                    toast(org.fossify.commons.R.string.system_service_disabled, Toast.LENGTH_LONG)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }
        } else {
            handlePermission(PERMISSION_READ_STORAGE) {
                if (it) {
                    importContacts()
                }
            }
        }
    }

    private fun importContacts() {
        FilePickerDialog(this) {
            showImportContactsDialog(it) {}
        }
    }

    private fun tryExportContacts() {
        if (isQPlus()) {
            ExportContactsDialog(this, config.lastExportPath, true) { file, ignoredContactSources ->
                ignoredExportContactSources = ignoredContactSources

                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "text/x-vcard"
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    addCategory(Intent.CATEGORY_OPENABLE)

                    try {
                        startActivityForResult(this, PICK_EXPORT_FILE_INTENT)
                    } catch (e: ActivityNotFoundException) {
                        toast(org.fossify.commons.R.string.no_app_found, Toast.LENGTH_LONG)
                    } catch (e: Exception) {
                        showErrorToast(e)
                    }
                }
            }
        } else {
            handlePermission(PERMISSION_WRITE_STORAGE) {
                if (it) {
                    ExportContactsDialog(this, config.lastExportPath, false) { file, ignoredContactSources ->
                        getFileOutputStream(file.toFileDirItem(this), true) {
                            exportContactsTo(ignoredContactSources, it)
                        }
                    }
                }
            }
        }
    }

    private fun exportContactsTo(ignoredContactSources: HashSet<String>, outputStream: OutputStream?) {
        ContactsHelper(this).getContacts(true, false, ignoredContactSources) { contacts ->
            if (contacts.isEmpty()) {
                toast(org.fossify.commons.R.string.no_entries_for_exporting)
            } else {
                VcfExporter().exportContacts(
                    context = this,
                    outputStream = outputStream,
                    contacts = contacts,
                    showExportingToast = true
                ) { result ->
                    toast(
                        when (result) {
                            VcfExporter.ExportResult.EXPORT_OK -> org.fossify.commons.R.string.exporting_successful
                            VcfExporter.ExportResult.EXPORT_PARTIAL -> org.fossify.commons.R.string.exporting_some_entries_failed
                            else -> org.fossify.commons.R.string.exporting_failed
                        }
                    )
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == PICK_IMPORT_SOURCE_INTENT && resultCode == Activity.RESULT_OK && resultData?.data != null) {
            tryImportContactsFromFile(resultData.data!!) {}
        } else if (requestCode == PICK_EXPORT_FILE_INTENT && resultCode == Activity.RESULT_OK && resultData?.data != null) {
            try {
                val outputStream = contentResolver.openOutputStream(resultData.data!!)
                exportContactsTo(ignoredExportContactSources, outputStream)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    // ══════════════════════════════════════════════════ M3 外观与显示

    /** 账号卡。没配置同步就整块隐藏 —— 显示一个空账号比不显示更让人困惑。 */
    private fun setupM3Account() {
        val session = SessionStore(this)
        binding.settingsM3AccountCard.beVisibleIf(session.isConfigured)
        if (!session.isConfigured) return

        binding.settingsM3AccountName.text = session.username
        binding.settingsM3AccountDetail.text = session.baseUrl
        binding.settingsM3AccountCard.setOnClickListener {
            startActivity(Intent(this, SyncSetupActivity::class.java))
        }
    }

    /**
     * 配色三选一。
     *
     * chip 是代码生成的而不是写死在 XML 里 —— 以后加第四套配色时
     * 只要往 M3Theme.Palette 里加一个枚举值，这里自动多一个 chip。
     */
    private fun setupM3Palette() {
        val palettes = listOf(
            M3Theme.Palette.DEFAULT to R.string.m3_palette_default,
            M3Theme.Palette.TEAL to R.string.m3_palette_teal,
            M3Theme.Palette.WARM to R.string.m3_palette_warm,
        )
        val current = M3Theme.palette(this)
        val container = binding.settingsM3PaletteChips
        container.removeAllViews()

        val inflater = LayoutInflater.from(this)
        val gap = resources.getDimensionPixelSize(R.dimen.m3_chip_gap)

        palettes.forEachIndexed { index, (palette, labelRes) ->
            val chip = inflater.inflate(R.layout.item_m3_chip, container, false) as TextView
            chip.setText(labelRes)
            chip.isSelected = palette == current
            chip.setTextColor(
                themeColor(
                    if (palette == current) MaterialR.attr.colorOnSecondaryContainer
                    else MaterialR.attr.colorOnSurfaceVariant
                )
            )
            (chip.layoutParams as LinearLayout.LayoutParams).marginEnd =
                if (index == palettes.lastIndex) 0 else gap
            chip.setOnClickListener { M3Theme.switchPalette(this, palette) }
            container.addView(chip)
        }
    }

    /**
     * 深色主题三选一，不是开关。
     *
     * 两态开关表达不了「跟随系统」—— 会逼用户在两个都不对的选项里选一个。
     */
    private fun setupM3DarkMode() {
        val modes = listOf(
            M3Theme.DarkMode.FOLLOW_SYSTEM to R.string.m3_dark_follow_system,
            M3Theme.DarkMode.LIGHT to R.string.m3_dark_always_off,
            M3Theme.DarkMode.DARK to R.string.m3_dark_always_on,
        )
        val current = M3Theme.darkMode(this)
        binding.settingsM3DarkModeValue.text =
            getString(modes.first { it.first == current }.second)

        binding.settingsM3DarkModeHolder.setOnClickListener {
            val items = modes.mapIndexed { i, (_, labelRes) ->
                RadioItem(i, getString(labelRes))
            } as ArrayList<RadioItem>
            RadioGroupDialog(this, items, modes.indexOfFirst { it.first == current }) { chosen ->
                M3Theme.switchDarkMode(this, modes[chosen as Int].first)
            }
        }
    }

    /**
     * 排序 / 筛选来源 / 视图类型。
     *
     * 这三项原来挂在主页的溢出菜单里。新主页顶栏按设计稿只有「我」一个入口，
     * 没有溢出菜单，所以搬到设置页 —— 它们本来就是显示偏好。
     */
    private fun setupM3DisplayOptions() {
        binding.settingsM3SortHolder.setOnClickListener {
            ChangeSortingDialog(this, showCustomSorting = false) { }
        }

        binding.settingsM3FilterHolder.apply {
            // 隐私保护开着时联系人来源被锁定，这一项点了也没用，直接禁掉
            val locked = config.privacyProtectionEnabled
            alpha = if (locked) 0.4f else 1f
            setOnClickListener {
                if (locked) {
                    toast(R.string.private_contacts_filter_locked)
                } else {
                    FilterContactSourcesDialog(this@SettingsActivity) { }
                }
            }
        }

        binding.settingsM3ViewTypeHolder.setOnClickListener {
            ChangeViewTypeDialog(this) { }
        }
    }

}
