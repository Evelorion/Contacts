package org.fossify.contacts.activities

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
// 通配 import：viewBinding、beVisibleIf、beGoneIf 都在这个包里，
// 逐个列出来的话漏一个就是一串 Unresolved reference
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.contacts.Contact
import org.fossify.contacts.R
import org.fossify.contacts.databinding.ActivitySearchBinding
// startCallIntent 是 **app 自己** 的 SimpleActivity 扩展（extensions/Activity.kt），
// 不在 commons 里 —— 名字看着像 commons 的，实际不是
import org.fossify.contacts.extensions.startCallIntent
import org.fossify.contacts.extensions.filterContactsForPrivacy
import org.fossify.contacts.extensions.handleGenericContactClick
import org.fossify.contacts.ui.InitialAvatarDrawable
import org.fossify.contacts.ui.M3Theme

/**
 * 全屏搜索页。
 *
 * ── 为什么搜索是一个独立 Activity ─────────────────────────────
 *
 * M3 的 docked search bar 模式。列表页那条搜索栏不接受输入，点了进这一页。
 * 好处是列表页完全不用处理输入法弹起导致的布局变化 —— 那是 Android 上
 * 最容易出现"FAB 被顶飞""列表跳一下"的地方。
 *
 * ── 搜索在本地做 ────────────────────────────────────────────
 *
 * 联系人量级（几千条）下全量加载再过滤只要几十毫秒，比维护一个索引简单得多。
 * 而且私密联系人的号码在本地库里是加密的，本来也没法交给数据库去 LIKE。
 */
class SearchActivity : SimpleActivity() {

    private val binding by viewBinding(ActivitySearchBinding::inflate)

    private var allContacts = listOf<Contact>()
    private val adapter = ResultsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        M3Theme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge()

        binding.searchResults.adapter = adapter
        binding.searchBack.setOnClickListener { finish() }
        binding.searchClear.setOnClickListener { binding.searchInput.setText("") }

        binding.searchEmptyCreate.setOnClickListener {
            startActivity(Intent(this, EditContactActivity::class.java))
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = onQueryChanged(s?.toString().orEmpty())
        })

        loadContacts()

        // 进来就聚焦并弹键盘 —— 用户点搜索栏的意图就是要打字，
        // 让他再点一次输入框是多余的一步。
        // post 是必要的：View 还没 attach 到窗口时 showSoftInput 会被静默忽略。
        binding.searchInput.requestFocus()
        binding.searchInput.post {
            getSystemService<InputMethodManager>()
                ?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        showRecentSearches()
    }

    private fun loadContacts() {
        ContactsHelper(this).getContacts { contacts ->
            allContacts = filterContactsForPrivacy(contacts)
            // 加载完时用户可能已经打了字，补一次过滤
            runOnUiThread { onQueryChanged(binding.searchInput.text.toString()) }
        }
    }

    // ------------------------------------------------------------------ 过滤

    private fun onQueryChanged(raw: String) {
        val query = raw.trim()
        binding.searchClear.beVisibleIf(query.isNotEmpty())

        if (query.isEmpty()) {
            showState(recent = true, results = false, empty = false)
            return
        }

        val results = filter(query)
        if (results.isEmpty()) {
            binding.searchEmptyTitle.text = getString(R.string.m3_search_empty_title, query)
            showState(recent = false, results = false, empty = true)
        } else {
            binding.searchResultCount.text =
                getString(R.string.m3_search_result_count, results.size)
            adapter.submit(results)
            showState(recent = false, results = true, empty = false)
        }
    }

    /**
     * 姓名、号码、公司三个字段都匹配。
     *
     * 号码比对前先去掉所有非数字 —— 用户存的是「138 0013 8000」，
     * 搜的时候会打「13800」，不归一化就一条都搜不到。
     */
    private fun filter(query: String): List<Contact> {
        val lower = query.lowercase()
        val digits = query.filter { it.isDigit() }

        return allContacts.filter { contact ->
            val name = contact.getNameToDisplay().lowercase()
            if (name.contains(lower)) return@filter true

            if (contact.organization.company.lowercase().contains(lower)) return@filter true

            if (digits.isNotEmpty()) {
                contact.phoneNumbers.any { it.normalizedNumber.filter(Char::isDigit).contains(digits) }
            } else {
                false
            }
        }
    }

    private fun showState(recent: Boolean, results: Boolean, empty: Boolean) {
        binding.searchRecentHolder.beVisibleIf(recent)
        binding.searchResultCount.beVisibleIf(results)
        binding.searchResults.beVisibleIf(results)
        binding.searchEmpty.beVisibleIf(empty)
    }

    // ------------------------------------------------------------ 最近搜索

    /**
     * 最近搜索存在 SharedPreferences，最多 6 条。
     *
     * **只存搜索词，不存搜到了谁** —— 后者等于在明文里留下一份"我最近关注谁"
     * 的记录，和这个 App 加密通讯录的目的相反。
     */
    private fun showRecentSearches() {
        val prefs = getSharedPreferences(RECENT_PREFS, MODE_PRIVATE)
        val recent = prefs.getString(RECENT_KEY, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }

        binding.searchRecentHolder.beGoneIf(recent.isEmpty())
        binding.searchRecentChips.removeAllViews()

        val inflater = LayoutInflater.from(this)
        recent.forEach { term ->
            val chip = inflater.inflate(R.layout.item_m3_chip, binding.searchRecentChips, false) as TextView
            chip.text = term
            (chip.layoutParams as LinearLayout.LayoutParams).marginEnd =
                resources.getDimensionPixelSize(R.dimen.m3_chip_gap)
            chip.setOnClickListener {
                binding.searchInput.setText(term)
                binding.searchInput.setSelection(term.length)
            }
            binding.searchRecentChips.addView(chip)
        }
    }

    private fun rememberSearch(term: String) {
        if (term.isBlank()) return
        val prefs = getSharedPreferences(RECENT_PREFS, MODE_PRIVATE)
        val existing = prefs.getString(RECENT_KEY, "").orEmpty()
            .split('\n').filter { it.isNotBlank() && it != term }
        val updated = (listOf(term) + existing).take(MAX_RECENT)
        prefs.edit().putString(RECENT_KEY, updated.joinToString("\n")).apply()
    }

    override fun onPause() {
        super.onPause()
        rememberSearch(binding.searchInput.text.toString().trim())
    }

    // ------------------------------------------------------------- Adapter

    private inner class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.Holder>() {

        private var items = listOf<Contact>()

        fun submit(new: List<Contact>) {
            items = new
            // 搜索结果整批换掉，没有增量更新的意义，
            // notifyDataSetChanged 在这里是正确的选择而不是偷懒
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_m3_search_result, parent, false)
            return Holder(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val avatar = view.findViewById<ImageView>(R.id.search_item_avatar)
            private val name = view.findViewById<TextView>(R.id.search_item_name)
            private val number = view.findViewById<TextView>(R.id.search_item_number)
            private val call = view.findViewById<ImageView>(R.id.search_item_call)

            fun bind(contact: Contact) {
                val display = contact.getNameToDisplay()
                name.text = display
                val phone = contact.phoneNumbers.firstOrNull()?.value.orEmpty()
                number.text = phone
                number.beVisibleIf(phone.isNotEmpty())

                avatar.setImageDrawable(
                    InitialAvatarDrawable(
                        context = itemView.context,
                        initial = InitialAvatarDrawable.initialOf(display),
                        key = contact.id,
                    )
                )

                itemView.setOnClickListener {
                    rememberSearch(binding.searchInput.text.toString().trim())
                    handleGenericContactClick(contact)
                }
                call.beVisibleIf(phone.isNotEmpty())
                call.setOnClickListener {
                    // 显式限定接收者：这里是 ViewHolder 的作用域。
                    // startCallIntent 的接收者是 SimpleActivity，不是 View。
                    this@SearchActivity.startCallIntent(phone)
                }
            }
        }
    }

    companion object {
        private const val RECENT_PREFS = "m3_search"
        private const val RECENT_KEY = "recent_terms"
        private const val MAX_RECENT = 6
    }
}
