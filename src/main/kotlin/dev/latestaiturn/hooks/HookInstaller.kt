package dev.latestaiturn.hooks

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure JSON edits for the agents' hook config files. Installing only ever adds our entries; uninstalling only
 * ever removes them; anything else in the file is left as it was. Invalid JSON is refused, never overwritten.
 */
object HookInstaller {
    const val MARKER = "latest-ai-turn/hook.sh"
    private const val ANTIGRAVITY_BLOCK = "latest-ai-turn"
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    private fun command(script: String, app: String, event: String) = "sh '$script' $app $event"

    private fun parse(existing: String?): JsonObject {
        if (existing.isNullOrBlank()) return JsonObject()
        val parsed = try { JsonParser.parseString(existing) } catch (e: Exception) { null }
        require(parsed != null && parsed.isJsonObject) { "Existing hook config is not a JSON object; refusing to modify it" }
        return parsed.asJsonObject
    }

    private fun out(o: JsonObject) = gson.toJson(o) + "\n"

    private fun isOurs(handlerGroup: JsonElement): Boolean =
        handlerGroup.isJsonObject && handlerGroup.asJsonObject["hooks"]?.takeIf { it.isJsonArray }?.asJsonArray
            ?.any { it.isJsonObject && it.asJsonObject["command"]?.asString?.contains(MARKER) == true } == true

    // ---- Claude Code (~/.claude/settings.json) and Codex (~/.codex/hooks.json): same shape ----

    fun installClaudeStyle(existing: String?, script: String, app: String): String {
        val root = parse(existing)
        val hooks = root.getAsJsonObjectOrCreate("hooks")
        for ((event, kind) in listOf("UserPromptSubmit" to "start", "Stop" to "end")) {
            val groups = hooks.get(event)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { hooks.add(event, it) }
            val kept = JsonArray().also { a -> groups.filterNot(::isOurs).forEach(a::add) }
            val handler = JsonObject().apply {
                addProperty("type", "command")
                addProperty("command", command(script, app, kind))
                addProperty("timeout", 10)
            }
            kept.add(JsonObject().apply { add("hooks", JsonArray().also { it.add(handler) }) })
            hooks.add(event, kept)
        }
        return out(root)
    }

    fun uninstallClaudeStyle(existing: String?): String {
        val root = parse(existing)
        val hooks = root["hooks"]?.takeIf { it.isJsonObject }?.asJsonObject ?: return out(root)
        for (event in listOf("UserPromptSubmit", "Stop")) {
            val groups = hooks[event]?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            val kept = JsonArray().also { a -> groups.filterNot(::isOurs).forEach(a::add) }
            if (kept.size() == groups.size()) continue
            if (kept.size() == 0) hooks.remove(event) else hooks.add(event, kept)
        }
        return out(root)
    }

    fun isInstalledClaudeStyle(existing: String?): Boolean {
        val hooks = runCatching { parse(existing) }.getOrNull()?.get("hooks")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        return listOf("UserPromptSubmit", "Stop").all { e ->
            hooks[e]?.takeIf { it.isJsonArray }?.asJsonArray?.any(::isOurs) == true
        }
    }

    // ---- Antigravity (~/.gemini/config/hooks.json): named hook block, handlers listed directly ----

    fun installAntigravity(existing: String?, script: String): String {
        val root = parse(existing)
        fun handler(kind: String) = JsonArray().also {
            it.add(JsonObject().apply {
                addProperty("type", "command")
                addProperty("command", command(script, "antigravity", kind))
                addProperty("timeout", 10)
            })
        }
        root.add(ANTIGRAVITY_BLOCK, JsonObject().apply {
            addProperty("enabled", true)
            add("PreInvocation", handler("start"))
            add("Stop", handler("end"))
        })
        return out(root)
    }

    fun uninstallAntigravity(existing: String?): String {
        val root = parse(existing)
        root.remove(ANTIGRAVITY_BLOCK)
        return out(root)
    }

    fun isInstalledAntigravity(existing: String?): Boolean =
        runCatching { parse(existing) }.getOrNull()?.has(ANTIGRAVITY_BLOCK) == true

    private fun JsonObject.getAsJsonObjectOrCreate(key: String): JsonObject =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { add(key, it) }
}

/** The relay every hook runs: stores the agent's payload in the spool directory and answers the agent. */
object HookScript {
    const val TEXT = """#!/bin/sh
# Latest AI Turn hook relay (installed by the IntelliJ plugin; safe to delete if you uninstall the hooks).
# Usage: hook.sh <claude|codex|antigravity> <start|end>
# Copies the hook payload from stdin into the spool directory, then prints the minimal reply the agent expects.
app="${'$'}1"
event="${'$'}2"
dir="${'$'}{LATEST_AI_TURN_SPOOL:-${'$'}HOME/.latest-ai-turn/spool}"
mkdir -p "${'$'}dir" 2>/dev/null
tmp="${'$'}dir/.${'$'}app-${'$'}event-${'$'}${'$'}.tmp"
cat > "${'$'}tmp" 2>/dev/null
mv "${'$'}tmp" "${'$'}dir/${'$'}app-${'$'}event-${'$'}(date +%s)-${'$'}${'$'}.json" 2>/dev/null
case "${'$'}app-${'$'}event" in
  antigravity-end) printf '{"decision":"allow"}' ;;
  *) printf '{}' ;;
esac
exit 0
"""
}
