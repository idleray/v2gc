package com.v2gc.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class VercelDeployment(
    @SerialName("uid")
    val id: String,
    val name: String? = null,
    val url: String? = null,
    val state: String? = null,
    @SerialName("created")
    val createdAt: Long,
    val meta: Map<String, String>? = null,
    val public: Boolean? = null,
    val version: Int? = null,
    val regions: List<String>? = null,
    val build: JsonElement? = null,
    val functions: Map<String, JsonElement>? = null,
    val routes: List<JsonElement>? = null
)

@Serializable
data class VercelFile(
    val name: String,
    val type: String,
    val mode: Int,
    val uid: String? = null,
    val children: List<VercelFile>? = null
)

@Serializable
enum class DeploymentState {
    @SerialName("READY")
    READY,
    @SerialName("ERROR")
    ERROR,
    @SerialName("BUILDING")
    BUILDING,
    @SerialName("QUEUED")
    QUEUED,
    @SerialName("CANCELED")
    CANCELED
} 