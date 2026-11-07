package dev.latestaiturn.signals

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Turns one spool file into a [TurnSignal]. The hook script names each file `<app>-<start|end>-<time>-<pid>.json`
 * and stores the agent's raw stdin payload unchanged inside; the app and event come from the name because they
 * are reliable, while session and directory come from the payload.
 */
object SpoolParser {
    fun parse(fileName: String, json: String, mtimeMillis: Long): TurnSignal? {
        val parts = fileName.removeSuffix(".json").split("-")
        if (parts.size < 2 || !fileName.endsWith(".json")) return null
        val app = parts[0]
        val event = parts[1]
        val obj = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            null
        } ?: return null

        return when (app) {
            "claude", "codex" -> {
                val kind = when (event) { "start" -> SignalKind.START; "end" -> SignalKind.END; else -> return null }
                val session = obj.str("session_id") ?: return null
                val cwd = obj.str("cwd") ?: return null
                TurnSignal(kind, app, session, listOf(cwd), mtimeMillis)
            }
            "antigravity" -> {
                val session = obj.str("conversationId") ?: return null
                val paths = obj.get("workspacePaths")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null } ?: return null
                when (event) {
                    "start" -> TurnSignal(SignalKind.CONTINUE, app, session, paths, mtimeMillis)
                    "end" ->
                        if (obj.get("fullyIdle")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
                            TurnSignal(SignalKind.END, app, session, paths, mtimeMillis)
                        else null
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
