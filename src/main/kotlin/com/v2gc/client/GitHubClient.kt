package com.v2gc.client

import java.io.File

interface GitHubClient {
    /**
     * Check if a repository exists
     * @return true if repository exists, false otherwise
     */
    suspend fun repositoryExists(): Boolean

    /**
     * Create a new repository
     */
    suspend fun createRepository(): String

    fun cloneRepository(directory: File)
} 