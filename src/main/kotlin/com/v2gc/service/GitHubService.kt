package com.v2gc.service

import com.v2gc.client.GitHubClient
import com.v2gc.client.VercelClient
import com.v2gc.model.VercelDeployment

class GitHubService(
    private val githubClient: GitHubClient,
    private val vercelClient: VercelClient
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
        
        return if (!githubClient.repositoryExists(projectName)) {
            println("Creating GitHub repository for project: $projectName")
            githubClient.createRepository(
                name = projectName,
                description = "Migrated from Vercel deployment: ${deployment.url ?: "unknown"}",
                isPrivate = true
            )
        } else {
            println("GitHub repository already exists for project: $projectName")
            "https://github.com/${projectName}"
        }
    }
} 