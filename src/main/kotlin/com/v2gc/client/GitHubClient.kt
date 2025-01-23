package com.v2gc.client

interface GitHubClient {
    /**
     * Check if a repository exists
     * @param name Repository name
     * @return true if repository exists, false otherwise
     */
    suspend fun repositoryExists(name: String): Boolean

    /**
     * Create a new repository
     * @param name Repository name
     * @param description Repository description (optional)
     * @param isPrivate Whether the repository should be private (default: true)
     * @return Repository URL
     */
    suspend fun createRepository(name: String, description: String? = null, isPrivate: Boolean = true): String
} 