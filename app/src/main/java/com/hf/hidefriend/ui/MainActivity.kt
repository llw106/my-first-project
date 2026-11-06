package com.hf.hidefriend.ui

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.hf.hidefriend.Hidden

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val items = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Hidden.PREFS, MODE_PRIVATE)

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val enabled = Switch(this)
        enabled.text = "启用隐藏（关闭后微信恢复原样）"
        enabled.isChecked = prefs.getBoolean(Hidden.KEY_ENABLED, true)
        enabled.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean(Hidden.KEY_ENABLED, value).apply()
        }
        root.addView(enabled)

        val aggressive = Switch(this)
        aggressive.text = "激进模式（会话局部刷新也过滤，可能错位）"
        aggressive.isChecked = prefs.getBoolean(Hidden.KEY_AGGRESSIVE, false)
        aggressive.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean(Hidden.KEY_AGGRESSIVE, value).apply()
        }
        root.addView(aggressive)

        val tip = TextView(this)
        tip.text = "填要隐藏的 wxid（例 wxid_abc123）。隐藏范围：会话列表 / 通讯录 / 搜索。" +
            "改完名单请强行停止微信再打开，配置才会重新读取。"
        tip.textSize = 12f
        tip.setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        root.addView(tip)

        val input = EditText(this)
        input.hint = "wxid_xxx 或 群id@chatroom"
        input.inputType = InputType.TYPE_CLASS_TEXT
        root.addView(input)

        val addButton = Button(this)
        addButton.text = "添加到隐藏名单"
        root.addView(addButton)

        listView = ListView(this)
        root.addView(
            listView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val deleteButton = Button(this)
        deleteButton.text = "删除选中"
        root.addView(deleteButton)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, items)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE

        addButton.setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isEmpty()) return@setOnClickListener
            if (items.contains(value)) {
                Toast.makeText(this, "已经在名单里了", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            items.add(value)
            persist()
            adapter.notifyDataSetChanged()
            input.setText("")
        }

        deleteButton.setOnClickListener {
            val position = listView.checkedItemPosition
            if (position == ListView.INVALID_POSITION) {
                Toast.makeText(this, "先选中一条", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            items.removeAt(position)
            persist()
            adapter.notifyDataSetChanged()
        }

        setContentView(root)
        reload()
    }

    private fun reload() {
        items.clear()
        val raw = prefs.getString(Hidden.KEY_LIST, "") ?: ""
        items.addAll(raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() })
        adapter.notifyDataSetChanged()
    }

    private fun persist() {
        prefs.edit().putString(Hidden.KEY_LIST, items.joinToString("\n")).apply()
    }
}