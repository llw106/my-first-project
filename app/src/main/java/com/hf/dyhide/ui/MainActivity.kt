package com.hf.dyhide.ui

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
import com.hf.dyhide.Hidden

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
        val gap = (8 * density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val enabled = Switch(this)
        enabled.text = "启用隐藏（关闭后抖音恢复原样）"
        enabled.isChecked = prefs.getBoolean(Hidden.KEY_ENABLED, true)
        enabled.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean(Hidden.KEY_ENABLED, value).apply()
        }
        root.addView(enabled)

        val tip = TextView(this)
        tip.text = "要隐藏的好友：填抖音 uid（日志里 uid= 后面那串数字），或昵称/备注。\n" +
                "当前是探针版：先跑一次抖音，从 logcat 里抄 uid 填进来。\n" +
                "改完名单请强行停止抖音再打开，配置才会重新读取。"
        tip.textSize = 12f
        tip.setPadding(0, gap, 0, gap)
        root.addView(tip)

        val input = EditText(this)
        input.hint = "uid 或 昵称"
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
        makeReadable()
        items.clear()
        val raw = prefs.getString(Hidden.KEY_LIST, "") ?: ""
        items.addAll(raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() })
        adapter.notifyDataSetChanged()
    }

    private fun persist() {
        prefs.edit().putString(Hidden.KEY_LIST, items.joinToString("\n")).commit()
        makeReadable()
    }

    /**
     * 让抖音进程能读到这份配置。
     * Android 7+ 起 SharedPreferences 默认只有 App 自己能读，
     * XSharedPreferences 在抖音进程里读必须文件对 other 可读。
     */
    private fun makeReadable() {
        try {
            val dir = java.io.File(applicationInfo.dataDir, "shared_prefs")
            val file = java.io.File(dir, Hidden.PREFS + ".xml")
            dir.setExecutable(true, false)
            dir.setReadable(true, false)
            file.setReadable(true, false)
        } catch (t: Throwable) {
        }
    }
}