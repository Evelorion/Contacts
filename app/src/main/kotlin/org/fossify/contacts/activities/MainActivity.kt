package org.fossify.contacts.activities

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import androidx.viewpager.widget.ViewPager
import org.fossify.commons.databases.ContactsDatabase
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.FAQItem
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.Release
import org.fossify.commons.models.contacts.Contact
import org.fossify.contacts.BuildConfig
import org.fossify.contacts.R
import org.fossify.contacts.adapters.ViewPagerAdapter
import org.fossify.contacts.databinding.ActivityMainBinding
import org.fossify.contacts.dialogs.ChangeSortingDialog
import org.fossify.contacts.dialogs.FilterContactSourcesDialog
import org.fossify.contacts.extensions.config
import org.fossify.contacts.extensions.filterContactsForPrivacy
import org.fossify.contacts.extensions.handleGenericContactClick
import org.fossify.contacts.extensions.tryImportContactsFromFile
import org.fossify.contacts.fragments.FavoritesFragment
import org.fossify.contacts.fragments.MyViewPagerFragment
import org.fossify.contacts.helpers.ALL_TABS_MASK
import org.fossify.contacts.helpers.tabsList
import org.fossify.contacts.interfaces.RefreshContactsListener
import java.util.Arrays
import org.fossify.contacts.sync.localdb.EncryptedDatabases
import org.fossify.contacts.sync.work.SyncScheduler
import org.fossify.contacts.ui.M3BottomNav
import org.fossify.contacts.ui.M3FilterChips
import org.fossify.contacts.ui.M3Theme
import org.fossify.contacts.sync.db.SyncDatabase
import org.fossify.contacts.sync.net.SessionStore
import java.text.NumberFormat

class MainActivity : SimpleActivity(), RefreshContactsListener {
    private var werePermissionsHandled = false
    private var isFirstResume = true
    private var isGettingContacts = false

    private var storedShowContactThumbnails = false
    private var storedShowPhoneNumbers = false
    private var storedStartNameWithSurname = false
    private var storedFontSize = 0
    private var storedShowTabs = 0

    // 顶栏没有搜索框了 —— 搜索是独立的一页（M3 的 docked search bar 模式）
    override var isSearchBarEnabled = false

    private val binding by viewBinding(ActivityMainBinding::inflate)

    private lateinit var bottomNav: M3BottomNav
    private lateinit var filterChips: M3FilterChips

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须在 super.onCreate() **之后** —— commons 的 BaseSimpleActivity
        // 会在它的 onCreate 里 setTheme() 把主题整个换掉，早于它设是白费的。
        M3Theme.apply(this)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupEdgeToEdge(
            padBottomImeAndSystem = listOf(binding.mainBottomNav),
        )
        storeStateVariables()
        setupHeader()
        setupBottomNav()
        setupFilterChips()
        checkContactPermissions()
        checkWhatsNewDialog()
    }

    // ────────────────────────────────────────────────────────── M3 顶栏

    private fun setupHeader() {
        binding.mainSearchBar.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
            // 搜索页自己有进入动画（从下往上），关掉系统默认的横向切换
            overridePendingTransition(0, 0)
        }
        binding.mainProfileAvatar.setOnClickListener { launchSettings() }
        binding.mainFab.setOnClickListener {
            startActivity(Intent(this, EditContactActivity::class.java))
        }
    }

    /**
     * 副标题显示「N 位 · 同步状态」。
     *
     * 没配置同步时只显示条数 —— 挂一个「未同步」在那里会让人以为出了问题，
     * 而实际上用户只是没开这个功能。
     *
     * 同步状态存在 Room 里（sync_state 表），必须异步读。所以这里先把条数
     * 显示出来，状态查到之后再补上 —— 副标题不该为了一个次要信息卡住首屏。
     */
    private fun updateSubtitle(contactCount: Int) {
        val count = NumberFormat.getInstance().format(contactCount)
        binding.mainSubtitle.text = getString(R.string.m3_subtitle_count, count)

        if (!SessionStore(this).isConfigured) return

        ensureBackgroundThread {
            val state = SyncDatabase.get(this).syncDao().getState()
            val label = when {
                state == null || state.lastSyncAt == 0L -> R.string.m3_sync_state_never
                state.lastError.isNotEmpty() -> R.string.m3_sync_state_offline
                else -> R.string.m3_sync_state_synced
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                binding.mainSubtitle.text =
                    getString(R.string.m3_subtitle_with_sync, count, getString(label))
            }
        }
    }

    // ────────────────────────────────────────────────────── M3 底部导航

    /**
     * 底部导航固定三项：联系人 / 收藏 / 设置。
     *
     * 注意这和 config.showTabs 是两回事 —— showTabs 控制的是 ViewPager 里
     * 有哪几个 fragment。导航的前两项映射到 ViewPager 的页，第三项跳设置页。
     * 用户关掉了「收藏」页时，第二项也要一起隐藏，否则点了会跳到不存在的页。
     */
    private fun setupBottomNav() {
        bottomNav = M3BottomNav(binding.mainBottomNav)
        bottomNav.setItems(M3BottomNav.defaultItems()) { index ->
            when (index) {
                NAV_CONTACTS -> switchToPage(TAB_CONTACTS)
                NAV_FAVORITES -> switchToPage(TAB_FAVORITES)
                NAV_SETTINGS -> {
                    launchSettings()
                    // 设置是另一个 Activity，不是页签 —— 高亮要退回原来那项，
                    // 否则用户返回后会看到"设置"仍然是选中态
                    bottomNav.select(currentNavIndex())
                }
            }
        }
        bottomNav.select(currentNavIndex())
    }

    private fun switchToPage(tabMask: Int) {
        val index = tabsList.filter { config.showTabs and it != 0 }.indexOf(tabMask)
        if (index >= 0) {
            binding.viewPager.currentItem = index
            updateTitleForPage(index)
        }
    }

    private fun currentNavIndex(): Int {
        val visible = tabsList.filter { config.showTabs and it != 0 }
        return when (visible.getOrNull(binding.viewPager.currentItem)) {
            TAB_FAVORITES -> NAV_FAVORITES
            else -> NAV_CONTACTS
        }
    }

    private fun updateTitleForPage(index: Int) {
        val visible = tabsList.filter { config.showTabs and it != 0 }
        binding.mainTitle.setText(
            when (visible.getOrNull(index)) {
                TAB_FAVORITES -> org.fossify.commons.R.string.favorites_tab
                TAB_GROUPS -> org.fossify.commons.R.string.groups_tab
                else -> org.fossify.commons.R.string.contacts_tab
            }
        )
    }

    // ─────────────────────────────────────────────────────── 筛选 chip

    /**
     * 群组页在 1a 规范型里不进底部导航（导航只有三项），
     * 用筛选 chip 进入。只在用户开着「群组」页时才显示这一栏。
     */
    private fun setupFilterChips() {
        val hasGroups = config.showTabs and TAB_GROUPS != 0
        binding.mainFilterScroll.beVisibleIf(hasGroups)
        if (!hasGroups) return

        filterChips = M3FilterChips(binding.mainFilterChips)
        filterChips.setItems(listOf(R.string.m3_filter_all, org.fossify.commons.R.string.groups_tab)) { index ->
            switchToPage(if (index == 0) TAB_CONTACTS else TAB_GROUPS)
        }
    }

    private fun checkContactPermissions() {
        handlePermission(PERMISSION_READ_CONTACTS) {
            werePermissionsHandled = true
            if (it) {
                handlePermission(PERMISSION_WRITE_CONTACTS) {
                    handlePermission(PERMISSION_GET_ACCOUNTS) {
                        initFragments()
                    }
                }
            } else {
                initFragments()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台顺手同步一次。SyncEngine 靠全量扫描发现本地改动，
        // 不埋这行也不会丢数据，只是会晚到下一次周期任务。
        SyncScheduler.syncNow(this, "resume")
        if (storedShowPhoneNumbers != config.showPhoneNumbers) {
            System.exit(0)
            return
        }

        if (storedShowTabs != config.showTabs) {
            config.lastUsedViewPagerPage = 0
            finish()
            startActivity(intent)
            return
        }

        val configShowContactThumbnails = config.showContactThumbnails
        if (storedShowContactThumbnails != configShowContactThumbnails) {
            getAllFragments().forEach {
                it?.showContactThumbnailsChanged(configShowContactThumbnails)
            }
        }

        val properPrimaryColor = getProperPrimaryColor()
        getAllFragments().forEach {
            it?.setupColors(getProperTextColor(), properPrimaryColor)
        }
        bottomNav.select(currentNavIndex())
        updateTitleForPage(binding.viewPager.currentItem)

        val configStartNameWithSurname = config.startNameWithSurname
        if (storedStartNameWithSurname != configStartNameWithSurname) {
            findViewById<MyViewPagerFragment<*>>(R.id.contacts_fragment)?.startNameWithSurnameChanged(configStartNameWithSurname)
            findViewById<MyViewPagerFragment<*>>(R.id.favorites_fragment)?.startNameWithSurnameChanged(configStartNameWithSurname)
        }

        val configFontSize = config.fontSize
        if (storedFontSize != configFontSize) {
            getAllFragments().forEach {
                it?.fontSizeChanged()
            }
        }

        if (werePermissionsHandled && !isFirstResume) {
            if (binding.viewPager.adapter == null) {
                initFragments()
            } else {
                refreshContacts(ALL_TABS_MASK)
            }
        }

        val dialpadIcon =
            resources.getColoredDrawableWithColor(org.fossify.commons.R.drawable.ic_dialpad_vector, properPrimaryColor.getContrastColor())
        binding.mainDialpadButton.apply {
            setImageDrawable(dialpadIcon)
            background.applyColorFilter(properPrimaryColor)
            beVisibleIf(config.showDialpadButton)
        }
        updatePrivacyBadge(properPrimaryColor)

        isFirstResume = false
        checkShortcuts()
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            ContactsDatabase.destroyInstance()
            // destroyInstance 把 commons 的静态实例清成 null，
            // 下次 getInstance() 会重建一个**明文**实例去开已经加密的文件 —— 直接崩。
            // 必须立刻用当前口令重新装回加密层。
            EncryptedDatabases.install(this)
        }
    }

    override fun onBackPressedCompat(): Boolean {
        // 搜索现在是独立 Activity，主页没有要先关掉的浮层
        return false
    }

                        private fun storeStateVariables() {
        config.apply {
            storedShowContactThumbnails = showContactThumbnails
            storedShowPhoneNumbers = showPhoneNumbers
            storedStartNameWithSurname = startNameWithSurname
            storedShowTabs = showTabs
            storedFontSize = fontSize
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcuts() {
        val appIconColor = config.appIconColor
        if (isNougatMR1Plus() && config.lastHandledShortcutColor != appIconColor) {
            val createNewContact = getCreateNewContactShortcut(appIconColor)

            try {
                shortcutManager.dynamicShortcuts = Arrays.asList(createNewContact)
                config.lastHandledShortcutColor = appIconColor
            } catch (ignored: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getCreateNewContactShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(org.fossify.commons.R.string.create_new_contact)
        val drawable = resources.getDrawable(org.fossify.commons.R.drawable.shortcut_plus)
        (drawable as LayerDrawable).findDrawableByLayerId(org.fossify.commons.R.id.shortcut_plus_background).applyColorFilter(appIconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, EditContactActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "create_new_contact")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }

    private fun getCurrentFragment(): MyViewPagerFragment<*>? {
        val showTabs = config.showTabs
        val fragments = arrayListOf<MyViewPagerFragment<*>>()
        if (showTabs and TAB_CONTACTS != 0) {
            fragments.add(findViewById(R.id.contacts_fragment))
        }

        if (showTabs and TAB_FAVORITES != 0) {
            fragments.add(findViewById(R.id.favorites_fragment))
        }

        if (showTabs and TAB_GROUPS != 0) {
            fragments.add(findViewById(R.id.groups_fragment))
        }

        return fragments.getOrNull(binding.viewPager.currentItem)
    }

        private fun updatePrivacyBadge(primaryColor: Int) {
        binding.mainPrivacyBadge.apply {
            background.applyColorFilter(primaryColor)
            setImageDrawable(resources.getColoredDrawableWithColor(R.drawable.ic_lock_vector, primaryColor.getContrastColor()))
            beVisibleIf(config.privacyProtectionEnabled)
        }
    }

                private fun initFragments() {
        binding.viewPager.offscreenPageLimit = tabsList.size - 1
        binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                bottomNav.select(currentNavIndex())
                updateTitleForPage(position)
                getAllFragments().forEach {
                    it?.finishActMode()
                }
            }
        })

        binding.viewPager.onGlobalLayout {
            refreshContacts(ALL_TABS_MASK)
        }

        handleExternalIntent()
        binding.mainDialpadButton.setOnClickListener {
            launchDialpad()
        }
    }

    private fun handleExternalIntent() {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }

        if (uri != null) {
            tryImportContactsFromFile(uri) { success ->
                if (success) {
                    runOnUiThread {
                        refreshContacts(ALL_TABS_MASK)
                    }
                }
            }
            intent.action = null
        }
    }

        private fun showSortingDialog(showCustomSorting: Boolean) {
        ChangeSortingDialog(this, showCustomSorting) {
            refreshContacts(TAB_CONTACTS or TAB_FAVORITES)
        }
    }

    fun showFilterDialog() {
        if (config.privacyProtectionEnabled) {
            toast(R.string.private_contacts_filter_locked)
            return
        }

        FilterContactSourcesDialog(this) {
            findViewById<MyViewPagerFragment<*>>(R.id.contacts_fragment)?.forceListRedraw = true
            refreshContacts(TAB_CONTACTS or TAB_FAVORITES)
        }
    }

    private fun launchDialpad() {
        hideKeyboard()
        Intent(Intent.ACTION_DIAL).apply {
            try {
                startActivity(this)
            } catch (e: ActivityNotFoundException) {
                toast(org.fossify.commons.R.string.no_app_found)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_JODA or LICENSE_GLIDE or LICENSE_GSON or LICENSE_INDICATOR_FAST_SCROLL or LICENSE_AUTOFITTEXTVIEW

        val faqItems = arrayListOf(
            FAQItem(R.string.faq_1_title, R.string.faq_1_text),
            FAQItem(org.fossify.commons.R.string.faq_9_title_commons, org.fossify.commons.R.string.faq_9_text_commons)
        )

        if (!resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)) {
            faqItems.add(FAQItem(org.fossify.commons.R.string.faq_2_title_commons, org.fossify.commons.R.string.faq_2_text_commons))
            faqItems.add(FAQItem(org.fossify.commons.R.string.faq_6_title_commons, org.fossify.commons.R.string.faq_6_text_commons))
            faqItems.add(FAQItem(org.fossify.commons.R.string.faq_7_title_commons, org.fossify.commons.R.string.faq_7_text_commons))
        }

        startAboutActivity(R.string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, true)
    }

    override fun refreshContacts(refreshTabsMask: Int) {
        if (isDestroyed || isFinishing || isGettingContacts) {
            return
        }

        isGettingContacts = true

        if (binding.viewPager.adapter == null) {
            binding.viewPager.adapter = ViewPagerAdapter(this, tabsList, config.showTabs)
            binding.viewPager.currentItem = getDefaultTab()
        }

        ContactsHelper(this).getContacts { contacts ->
            isGettingContacts = false
            if (isDestroyed || isFinishing) {
                return@getContacts
            }

            val filteredContacts = filterContactsForPrivacy(contacts)

            if (refreshTabsMask and TAB_CONTACTS != 0) {
                findViewById<MyViewPagerFragment<*>>(R.id.contacts_fragment)?.apply {
                    skipHashComparing = true
                    refreshContacts(filteredContacts)
                }
            }

            if (refreshTabsMask and TAB_FAVORITES != 0) {
                findViewById<MyViewPagerFragment<*>>(R.id.favorites_fragment)?.apply {
                    skipHashComparing = true
                    refreshContacts(filteredContacts)
                }
            }

            if (refreshTabsMask and TAB_GROUPS != 0) {
                findViewById<MyViewPagerFragment<*>>(R.id.groups_fragment)?.apply {
                    if (refreshTabsMask == TAB_GROUPS) {
                        skipHashComparing = true
                    }
                    refreshContacts(filteredContacts)
                }
            }

            // 联系人数变了，副标题跟着更新
            runOnUiThread { updateSubtitle(filteredContacts.size) }
        }
    }

    override fun contactClicked(contact: Contact) {
        handleGenericContactClick(contact)
    }

    private fun getAllFragments() = arrayListOf<MyViewPagerFragment<*>?>(
        findViewById(R.id.contacts_fragment),
        findViewById(R.id.favorites_fragment),
        findViewById(R.id.groups_fragment)
    )

    private fun getDefaultTab(): Int {
        val showTabsMask = config.showTabs
        return when (config.defaultTab) {
            TAB_LAST_USED -> config.lastUsedViewPagerPage
            TAB_CONTACTS -> 0
            TAB_FAVORITES -> if (showTabsMask and TAB_CONTACTS > 0) 1 else 0
            else -> {
                if (showTabsMask and TAB_GROUPS > 0) {
                    if (showTabsMask and TAB_CONTACTS > 0) {
                        if (showTabsMask and TAB_FAVORITES > 0) {
                            2
                        } else {
                            1
                        }
                    } else {
                        if (showTabsMask and TAB_FAVORITES > 0) {
                            1
                        } else {
                            0
                        }
                    }
                } else {
                    0
                }
            }
        }
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }

    companion object {
        // 底部导航的三项。用具名常量而不是裸数字 —— when(index) 里
        // 写 0/1/2 的话，以后插入一项就要人肉核对每个分支。
        private const val NAV_CONTACTS = 0
        private const val NAV_FAVORITES = 1
        private const val NAV_SETTINGS = 2
    }

}
