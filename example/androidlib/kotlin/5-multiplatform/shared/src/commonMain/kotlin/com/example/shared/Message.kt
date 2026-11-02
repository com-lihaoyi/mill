package com.example.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Message(val text: String, val platform: String)

fun Message.toJson(): String = Json.encodeToString(this)

fun messageFromJson(json: String): Message = Json.decodeFromString(json)
