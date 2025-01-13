package com.v2gc.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VercelDeployment(
    val id: String,
    val name: String,
    val url: String,
    val state: DeploymentState,
    @SerialName("created_at")
    val createdAt: Long,
    val meta: Map<String, String> = emptyMap()
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