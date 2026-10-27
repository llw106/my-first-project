package h.Hchat.hooks.items.script

import android.content.Context
import java.io.File

object ScriptPluginRuntime {
    lateinit var root: File
    fun scriptDir(context: Context): File = root
}
