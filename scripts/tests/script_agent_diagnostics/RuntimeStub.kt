package h.Hchat.hooks.items.script

import android.content.Context
import java.io.File

object ScriptPluginRuntime {
    lateinit var root: File
    var snapshot = PluginRuntimeSnapshot(false, false, "main", "")
    data class PluginRuntimeSnapshot(
        val initialized: Boolean,
        val loaded: Boolean,
        val processKind: String,
        val processName: String
    )
    fun scriptDir(context: Context): File = root
    fun pluginRuntimeSnapshot(pluginId: String): PluginRuntimeSnapshot = snapshot
}
