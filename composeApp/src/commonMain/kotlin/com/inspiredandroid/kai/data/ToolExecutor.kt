package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.getAvailableTools
import com.inspiredandroid.kai.getPlatformToolDefinitions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.getString

class ToolExecutor {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    fun formatJsonElement(element: JsonElement): String = when {
        element is JsonNull -> "null"
        element is JsonPrimitive && element.isString -> "\"${element.content}\""
        element is JsonPrimitive -> element.content
        else -> element.toString()
    }

    suspend fun executeTool(name: String, arguments: String): String {
        println("[Tool] executeTool called: name=$name, arguments=$arguments")

        val tools = getAvailableTools()
        println("[Tool] Available tools: ${tools.map { it.schema.name }}")
        val tool = tools.find { it.schema.name == name }
        if (tool == null) {
            println("[Tool] Tool not found: $name")
            return """{"success": false, "error": "Unknown tool: $name"}"""
        }

        val args = try {
            parseJsonToMap(arguments)
        } catch (e: Exception) {
            println("[Tool] Failed to parse arguments: ${e.message}")
            return """{"success": false, "error": "Failed to parse arguments: ${e.message}"}"""
        }
        println("[Tool] Parsed arguments: $args")

        return try {
            println("[Tool] Executing tool...")
            val result = tool.execute(args)
            println("[Tool] Tool execution result: $result")
            when (result) {
                is Map<*, *> -> {
                    val jsonEntries = result.entries.joinToString(", ") { (k, v) ->
                        val valueStr = when (v) {
                            is String -> "\"$v\""
                            is Boolean, is Number -> v.toString()
                            else -> "\"$v\""
                        }
                        "\"$k\": $valueStr"
                    }
                    "{$jsonEntries}"
                }

                is String -> result

                else -> """{"result": "$result"}"""
            }
        } catch (e: Exception) {
            println("[Tool] Tool execution failed: ${e.message}")
            e.printStackTrace()
            """{"success": false, "error": "Tool execution failed: ${e.message}"}"""
        }
    }

    private fun parseJsonToMap(json: String): Map<String, Any> {
        val jsonObject = jsonParser.parseToJsonElement(json).jsonObject
        return jsonObject.toMap()
    }

    private fun JsonObject.toMap(): Map<String, Any> = entries.associate { (key, value) ->
        key to when (value) {
            is JsonPrimitive if value.isString -> value.content
            is JsonPrimitive if value.booleanOrNull != null -> value.boolean
            is JsonPrimitive if value.intOrNull != null -> value.int
            is JsonPrimitive if value.doubleOrNull != null -> value.double
            is JsonObject -> value.toMap()
            else -> value.toString()
        }
    }

    suspend fun getToolDisplayName(toolId: String): String {
        val toolInfo = getPlatformToolDefinitions().find { it.id == toolId } ?: return toolId
        return toolInfo.nameRes?.let { getString(it) } ?: toolInfo.name
    }
}
