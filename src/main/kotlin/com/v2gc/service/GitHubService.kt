package com.v2gc.service

import com.v2gc.client.GitHubClient
import com.v2gc.client.VercelClient
import com.v2gc.model.VercelDeployment
import com.v2gc.model.GitHubConfig

class GitHubService(
    private val githubClient: GitHubClient,
    private val vercelClient: VercelClient,
    private val config: GitHubConfig
) {
    /**
     * Check if a GitHub repository exists for the Vercel deployment and create one if it doesn't
     * @param deployment Vercel deployment
     * @return GitHub repository URL
     * @throws IllegalArgumentException if deployment name is null
     */
    suspend fun ensureGitHubRepository(deployment: VercelDeployment): String {
        val projectName = deployment.name 
            ?: throw IllegalArgumentException("Deployment name cannot be null")
        
        return if (!githubClient.repositoryExists()) {
            println("Creating GitHub repository for project: $projectName")
            githubClient.createRepository()
        } else {
            println("GitHub repository already exists for project: $projectName")
            "https://github.com/${projectName}"
        }
    }

    suspend fun ensureRepositoryExists() {
        if (!githubClient.repositoryExists()) {
            githubClient.createRepository()
        }
    }
} 