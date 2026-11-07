package dev.latestaiturn

import com.google.gson.JsonParser
import dev.latestaiturn.hooks.HookInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HookInstallerTest {
    private val script = "/home/me/.latest-ai-turn/hook.sh"
    private fun json(s: String) = JsonParser.parseString(s)

    private val existing = """
        {
          "theme": "dark",
          "hooks": {
            "PreToolUse": [],
            "UserPromptSubmit": [ { "hooks": [ { "type": "command", "command": "echo other-tool" } ] } ]
          }
        }
    """.trimIndent()

    @Test
    fun `install into nothing creates start and end hooks`() {
        val out = json(HookInstaller.installClaudeStyle(null, script, "claude"))
        val ups = out.asJsonObject["hooks"].asJsonObject["UserPromptSubmit"].asJsonArray
        val stop = out.asJsonObject["hooks"].asJsonObject["Stop"].asJsonArray
        assertEquals("sh '$script' claude start", ups[0].asJsonObject["hooks"].asJsonArray[0].asJsonObject["command"].asString)
        assertEquals("sh '$script' claude end", stop[0].asJsonObject["hooks"].asJsonArray[0].asJsonObject["command"].asString)
    }

    @Test
    fun `install only adds - other settings and other tools' hooks survive`() {
        val out = json(HookInstaller.installClaudeStyle(existing, script, "codex")).asJsonObject
        assertEquals("dark", out["theme"].asString)
        assertEquals(0, out["hooks"].asJsonObject["PreToolUse"].asJsonArray.size())
        val ups = out["hooks"].asJsonObject["UserPromptSubmit"].asJsonArray
        assertEquals(2, ups.size())
        assertEquals("echo other-tool", ups[0].asJsonObject["hooks"].asJsonArray[0].asJsonObject["command"].asString)
    }

    @Test
    fun `installing twice changes nothing`() {
        val once = HookInstaller.installClaudeStyle(existing, script, "claude")
        assertEquals(json(once), json(HookInstaller.installClaudeStyle(once, script, "claude")))
    }

    @Test
    fun `uninstall removes only our hooks and restores the original`() {
        val installed = HookInstaller.installClaudeStyle(existing, script, "claude")
        val removed = HookInstaller.uninstallClaudeStyle(installed)
        assertEquals(json(existing), json(removed))
        assertTrue(HookInstaller.isInstalledClaudeStyle(installed))
        assertTrue(!HookInstaller.isInstalledClaudeStyle(removed))
    }

    @Test
    fun `antigravity uses a named hook block with PreInvocation and Stop`() {
        val out = json(HookInstaller.installAntigravity("""{"mine":{"Stop":[]}}""", script)).asJsonObject
        assertTrue(out.has("mine"))
        val block = out["latest-ai-turn"].asJsonObject
        assertEquals("sh '$script' antigravity start", block["PreInvocation"].asJsonArray[0].asJsonObject["command"].asString)
        assertEquals("sh '$script' antigravity end", block["Stop"].asJsonArray[0].asJsonObject["command"].asString)
        assertEquals(json("""{"mine":{"Stop":[]}}"""), json(HookInstaller.uninstallAntigravity(HookInstaller.installAntigravity("""{"mine":{"Stop":[]}}""", script))))
    }

    @Test
    fun `invalid existing json is refused rather than overwritten`() {
        try {
            HookInstaller.installClaudeStyle("{ not json", script, "claude")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
