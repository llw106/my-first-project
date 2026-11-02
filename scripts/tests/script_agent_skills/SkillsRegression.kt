package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.net.Uri
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun doc(name: String, description: String = "用于真实插件任务") = "---\nname: $name\ndescription: $description\n---\n\n正文：不能直接注入到模型提示中。\n"

private fun rejected(block: () -> Unit) {
    check(runCatching(block).isFailure) { "Expected rejection" }
}

private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
    ZipOutputStream(output).use { archive ->
        entries.forEach { (name, bytes) ->
            archive.putNextEntry(ZipEntry(name))
            archive.write(bytes)
            archive.closeEntry()
        }
    }
}.toByteArray()

private fun textEntry(name: String, value: String) = name to value.toByteArray(Charsets.UTF_8)

private fun read(store: ScriptPluginAgentSkillStore, id: String, path: String = "SKILL.md", offset: Int = 0, limit: Int = 12000) =
    JSONObject(store.call("hchat.skills.read", JSONObject().put("skill_id", id).put("path", path).put("offset", offset).put("max_chars", limit)))

fun main() {
    val sandbox = Files.createTempDirectory("hchat-skill-regression-").toFile()
    val root = File(sandbox, "Agent/skills")
    val store = ScriptPluginAgentSkillStore(root)
    try {
        check(store.list().isEmpty())
        check(store.catalog().isEmpty())
        check(!root.exists())
        val original = doc("hello-world")
        check(store.saveDocument(null, original).id == "hello-world")
        check(store.readDocument("hello-world") == original)
        rejected { store.saveDocument(null, doc("hello-world", "不能覆盖")) }
        check(store.readDocument("hello-world") == original)
        val changed = doc("renamed-title", "保留原有 ID")
        check(store.saveDocument("hello-world", changed).id == "hello-world")
        check(store.list().single().name == "renamed-title")
        store.setEnabled("hello-world", false)
        check(!ScriptPluginAgentSkillStore(root).list().single().enabled)
        check(store.catalog().isEmpty())
        rejected { read(store, "hello-world") }
        check(store.readDocument("hello-world") == changed) // UI can still edit disabled documents.
        store.setEnabled("hello-world", true)

        store.saveDocument(null, "\uFEFF---\r\nname: 'quoted-name'\r\ndescription: \"支持: \\" + "\"引号\\\" 与 \\u4e2d文\" # comment\r\nmetadata:\r\n  version: 1\r\n---\r\n正文")
        check(store.list().first { it.id == "quoted-name" }.description == "支持: \"引号\" 与 中文")
        store.saveDocument(null, "---\nname: folded\ndescription: >-\n  第一行\n  第二行\nmetadata:\n  version: 1\n---\n正文")
        check(store.list().first { it.id == "folded" }.description == "第一行 第二行")
        store.saveDocument(null, "---\nname: literal\ndescription: |\n  第一行\n  第二行\n---\n正文")
        store.saveDocument(null, "---\nname: single-quote\ndescription: 'it''s a skill'\n---\n正文")
        store.saveDocument(null, doc("comment", "文本 # 注释里的 key: value 不影响标量"))
        check(store.list().first { it.id == "comment" }.description == "文本")
        for (invalid in listOf(
            "name: no-header", "---\nname: no-end\ndescription: missing", doc("../escape"), doc("中文"),
            doc("array", "[one, two]"), doc("map", "{text: value}"), doc("alias", "*something"), doc("comment-only", "# not a value"),
            "---\nname: duplicate\nname: second\ndescription: nope\n---",
            "---\nname: missing-description\n---", doc("unterminated", "\"bad"),
            "---\nname: multiline\ndescription: starts here\n  this unsupported continuation must not be ignored\n---"
        )) rejected { store.saveDocument(null, invalid) }
        rejected { store.saveDocument(null, doc("oversized") + "x".repeat(256 * 1024)) }
        rejected { store.saveDocument("missing", doc("missing")) }

        val packageBytes = zip(
            textEntry("package/search/SKILL.md", doc("search")),
            textEntry("package/search/references/api.md", "reference-first\n" + "中".repeat(50000) + "\nreference-last"),
            textEntry("package/search/scripts/install.sh", "echo DO_NOT_EXECUTE"),
            textEntry("package/search/references/emoji.txt", "a🙂b"),
            "package/search/assets/image.png" to byteArrayOf(0, 1, 2, 3)
        )
        check(store.importStream(packageBytes.inputStream()).single().id == "search")
        val page = read(store, "search", "references/api.md", 0, 7)
        check(page.getString("content") == "referen")
        check(page.getInt("nextOffset") == 7)
        check(read(store, "search", "references/api.md", 7, 7).getString("content") == "ce-firs")
        val listing = read(store, "search", ".").getJSONArray("files").toString()
        check("scripts/install.sh" in listing && "references/api.md" in listing && "image.png" !in listing)
        check(!read(store, "search", "scripts/install.sh").getBoolean("scriptsExecuted"))
        rejected { read(store, "search", "assets/image.png") }
        rejected { read(store, "search", "../hello-world/SKILL.md") }
        rejected { read(store, "search", "/etc/passwd") }
        rejected { read(store, "../search") }
        rejected { read(store, "search", "SKILL.md", -1) }
        rejected { read(store, "search", "SKILL.md", Int.MAX_VALUE) }
        rejected { read(store, "search", "SKILL.md", 0, 24001) }
        check(read(store, "search", "references/emoji.txt", 1, 1).getString("content") == "🙂")
        check(read(store, "search", "references/emoji.txt", 1, 1).getInt("nextOffset") == 3)
        rejected { read(store, "search", "references/emoji.txt", 2, 1) }

        check(store.importStream(zip(textEntry("SKILL.md", doc("root-package"))).inputStream()).single().id == "root-package")
        val multi = zip(textEntry("one/SKILL.md", doc("one")), textEntry("two/SKILL.md", doc("two")))
        check(store.importStream(multi.inputStream()).map { it.id }.toSet() == setOf("one", "two"))
        val before = store.list().map { it.id }.toSet()
        val invalidPackages = listOf(
            packageBytes,
            zip(textEntry("new/SKILL.md", doc("would-import")), textEntry("old/SKILL.md", doc("one"))),
            zip(textEntry("good/SKILL.md", doc("good")), textEntry("bad/SKILL.md", "invalid")),
            zip(textEntry("same1/SKILL.md", doc("duplicate-package")), textEntry("same2/SKILL.md", doc("duplicate-package"))),
            zip(textEntry("SKILL.md", doc("nested")), textEntry("sub/SKILL.md", doc("nested-child"))),
            zip(textEntry("../escape", "escape")), zip(textEntry("/absolute", "escape")),
            zip(textEntry("C:\\escape", "escape")), zip(textEntry("foo//bar", "escape")),
            zip(textEntry("SKILL.md", doc("large")), "data.bin" to ByteArray(1024 * 1024 + 1)),
            zip(*Array(257) { textEntry("item-$it", "a") }),
            zip(*Array(9) { "item-$it" to ByteArray(1024 * 1024) }),
            byteArrayOf(80, 75, 3, 4)
        )
        invalidPackages.forEach { rejected { store.importStream(it.inputStream()) } }
        check(store.list().map { it.id }.toSet() == before)
        check(root.listFiles().orEmpty().none { it.name.startsWith('.') })
        check(!File(sandbox, "escape").exists())

        // Invalid state must fail before a create/import/delete can alter the installed files.
        val state = File(root.parentFile, "skills-state.json")
        val goodState = state.readText()
        state.writeText("broken-json")
        rejected { store.saveDocument(null, doc("bad-state-create")) }
        rejected { store.importStream(zip(textEntry("SKILL.md", doc("bad-state-import"))).inputStream()) }
        rejected { store.delete("one") }
        check(!File(root, "bad-state-create").exists() && !File(root, "bad-state-import").exists())
        check(File(root, "one/SKILL.md").isFile)
        state.writeText(goodState)

        // A ZIP Unix symlink is rejected even though the Java stream omits its mode flags.
        val linkedZip = zip(textEntry("SKILL.md", doc("zip-link")), textEntry("symlink", "../../elsewhere"))
        val central = linkedZip.indices.first { index -> index + 4 <= linkedZip.size &&
            linkedZip[index] == 80.toByte() && linkedZip[index + 1] == 75.toByte() && linkedZip[index + 2] == 1.toByte() && linkedZip[index + 3] == 2.toByte() }
        linkedZip[central + 41] = 0xa0.toByte()
        rejected { store.importStream(linkedZip.inputStream()) }

        val outside = File(sandbox, "outside.txt").apply { writeText("secret") }
        Files.createSymbolicLink(File(root, "search/references/linked.md").toPath(), outside.toPath())
        rejected { read(store, "search", "references/linked.md") }
        check("linked.md" !in read(store, "search", ".").getJSONArray("files").toString())
        Files.createSymbolicLink(File(root, "search/linked-directory").toPath(), sandbox.toPath())
        rejected { read(store, "search", "linked-directory/outside.txt") }
        val previousState = state.readText()
        state.delete()
        Files.createSymbolicLink(state.toPath(), outside.toPath())
        rejected { store.setEnabled("one", false) }
        rejected { store.list() }
        check(outside.readText() == "secret")
        state.delete()
        state.writeText(previousState)
        File(root, "hello-world/SKILL.md").writeText("broken document")
        check(store.list().first { it.id == "hello-world" }.error.isNotEmpty())
        check("hello-world" !in store.catalog())
        rejected { read(store, "hello-world") }
        store.setEnabled("hello-world", false)
        rejected { store.setEnabled("hello-world", true) }
        store.saveDocument("hello-world", doc("fixed"))
        store.delete("search")
        check(outside.readText() == "secret")
        check(!File(root, "search").exists())

        repeat(30) { store.saveDocument(null, doc("catalog-$it", "long-description-".repeat(100))) }
        val catalog = store.catalog()
        check(catalog.length <= 8000 && "hchat.skills.list" in catalog)
        check("正文：不能直接注入" !in catalog)
        val firstPage = JSONObject(store.call("hchat.skills.list", JSONObject()))
        check(firstPage.getJSONArray("skills").length() == 20)
        check(firstPage.getInt("nextOffset") == 20)
        val secondPage = JSONObject(store.call("hchat.skills.list", JSONObject().put("offset", 20)))
        check(secondPage.isNull("nextOffset"))
        check(firstPage.getInt("total") == store.list().size)
        check(firstPage.getJSONArray("skills").length() + secondPage.getJSONArray("skills").length() == store.list().size)
        rejected { store.call("hchat.skills.list", JSONObject().put("limit", 51)) }
        rejected { store.call("hchat.skills.list", JSONObject().put("offset", -1)) }

        repeat(300) { File(root, "one/ref-$it.txt").writeText("bounded directory listing") }
        val bounded = read(store, "one", ".")
        check(bounded.getJSONArray("files").length() <= 256 && bounded.getBoolean("filesTruncated"))
        check(JSONObject(ScriptPluginAgentSkills.toolCatalog()).getJSONArray("tools").length() == 2)
        check(ScriptPluginAgentSkills.isKnownToolName("hchat.skills.read"))
        check(!ScriptPluginAgentSkills.isKnownToolName("hchat.skills.run"))
        rejected { store.call("hchat.skills.run", JSONObject()) }

        val context = Context()
        ScriptPluginRuntime.root = File(sandbox, "脚本插件")
        context.contentResolver.content = doc("saf-import").toByteArray()
        check(ScriptPluginAgentSkills.importFile(context, Uri()).single().id == "saf-import")
        check(ScriptPluginAgentSkills.list(context).any { it.id == "saf-import" })
        println("Skills regressions passed: metadata, CRUD, SAF/ZIP import, transactional rejection, paging, disabled/invalid skills, symlinks and catalog limits")
    } finally {
        // Production deletion above already established that skill links never remove their targets.
        sandbox.deleteRecursively()
    }
}
