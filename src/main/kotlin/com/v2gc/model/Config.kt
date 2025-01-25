package com.v2gc.model

data class VercelConfig(
    val apiUrl: String,
    val teamId: String? = null,
    val token: String,
    val projectName: String
)

data class GitConfig(
    val authorName: String,
    val authorEmail: String,
    val defaultBranch: String,
    val token: String
)

data class AppConfig(
    val projectRootDir: String,
    val retryAttempts: Int,
    val retryDelay: Long
)

data class GitHubConfig(
    val token: String,
    val owner: String,
    val repo: String
) 