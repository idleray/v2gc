package com.v2gc.model

data class VercelConfig(
    val apiUrl: String,
    val teamId: String? = null,
    val token: String
)

data class GitConfig(
    val authorName: String,
    val authorEmail: String,
    val defaultBranch: String,
    val token: String
)

data class AppConfig(
    val tempDir: String,
    val retryAttempts: Int,
    val retryDelay: Long
) 