package h.Hchat.hooks.items.script.agent

import bsh.ScriptSyntaxValidator
import h.Hchat.utils.HLog
import java.io.File
import java.util.concurrent.Executors

private fun draft(code: String) = ScriptPluginAgentDraft(
    "test", "test", "name=test\nversion=1\nauthor=test", code, ""
)

private fun valid(code: String) {
    val result = ScriptPluginAgentValidator.validate(draft(code))
    check(result.canSave) { "Valid BeanShell rejected: ${result.errors}\n$code" }
}

private fun invalid(code: String, path: String = "main.java", line: Int? = null) {
    val issues = if (path == "main.java") ScriptPluginAgentValidator.validate(draft(code)).errors
        else ScriptPluginAgentValidator.validateAdditionalCode(path, code).filter { it.level == ScriptPluginAgentIssueLevel.ERROR }
    check(issues.size == 1) { "Expected one actionable syntax error, got $issues for $code" }
    check(issues.single().message.startsWith(path)) { issues }
    if (line != null) check(issues.single().message.startsWith("$path:$line:")) { issues }
}

fun main(args: Array<String>) {
    val validFixture = File(args.single(), "valid_extensions.bsh").readText()
    System.clearProperty("hchat.syntax.test.executed")
    valid(validFixture)
    check(System.getProperty("hchat.syntax.test.executed") == null) { "Parser executed plugin code" }
    listOf(
        "counter = 0; onLoad() { counter++; }",
        "onLoad() { toast(\"text } {\"); /* { */ } // }",
        "text = \"```native {\";",
        "void onLoad() throws Exception { return true; }",
        "class Bridge { public static native int call(); }",
        "callback = (value) -> value + 1;",
        "text = 'multiple characters';",
        "text = \"\\q\";",
        "int x = 1\nx++",
        "// a comment without a final newline",
        "/* a complete comment */",
        "return;"
    ).forEach(::valid)
    invalid("onLoad() {\n  counter = ;\n}", line = 2)
    invalid("onLoad( { toast(\"bad\"); }")
    invalid("values = [1, 2;", "lib/helper.bsh")
    invalid("onLoad() {\n  text = \"unterminated;\n}")
    invalid("/* unterminated")
    invalid("public private void onLoad() {}")
    invalid("```java\nonLoad() {}\n```")
    invalid("onLoad() {", "lib/helper.java")
    invalid("native int call();", line = 1)
    invalid("native int call();\nvalue = ;", line = 2)
    invalid("void greet(String name = \"friend\") {}\n\nvalue = ;", line = 3)
    invalid("@Ignored\nvalue = ;", line = 2)
    invalid("\n\nonLoad() {\n  value = ;\n}", line = 4)

    val original = "\n\nonLoad() {\n  value = ;\n}"
    val normalized = ScriptPluginAgentValidator.normalize(draft(original))
    val rawResult = ScriptPluginAgentValidator.validate(normalized, mainSource = original)
    check(rawResult.errors.single().message.startsWith("main.java:4:")) { rawResult }

    // Runtime errors in default expressions must not be mistaken for syntax errors.
    valid("void greet(String name = unknownRuntimeValue()) { toast(name); }")
    val rewrittenError = ScriptPluginAgentValidator.validate(draft("void broken(int amount = (1 + )) {}"))
    check(!rewrittenError.canSave)
    check(rewrittenError.errors.single().message.contains("预处理后")) { rewrittenError }

    // Kotlin and JavaScript assets are not BeanShell, including Kotlin external/native declarations.
    check(ScriptPluginAgentValidator.validateAdditionalCode("helper.kt", "external fun native(): Int").isEmpty())
    check(ScriptPluginAgentValidator.validateAdditionalCode("helper.js", "const native = (x) => `value ${'$'}{x}`;").isEmpty())
    invalid("onLoad( { }", "nested/main.java")
    check(ScriptPluginAgentValidator.validateAdditionalCode("lib/empty.bsh", "").isEmpty())
    check(!ScriptPluginAgentValidator.validate(draft(" ")).canSave)

    // There is no shared interpreter/global source-file mutation across concurrent Agent sessions.
    val executor = Executors.newFixedThreadPool(3)
    try {
        val checks = (1..24).map { count ->
            executor.submit<Boolean> {
                val result = ScriptSyntaxValidator.validate("\n".repeat(count) + "value = ;")
                result.error != null && result.line == count + 1
            }
        }
        check(checks.all { it.get() })
    } finally {
        executor.shutdownNow()
    }
    check(HLog.failures.isEmpty()) { "Unexpected parser failures: ${HLog.failures}" }
    println("PASS: BeanShell grammar, preprocess compatibility, source diagnostics, additional files, no execution and concurrent sessions")
}
