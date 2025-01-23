package com.v2gc.service

import com.v2gc.client.GitHubClient
import com.v2gc.client.VercelClient
import com.v2gc.model.VercelDeployment
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GitHubServiceTest {
    private val githubClient = mockk<GitHubClient>()
    private val vercelClient = mockk<VercelClient>()
    private val service = GitHubService(githubClient, vercelClient)

    @Test
    fun `ensureGitHubRepository creates repository when it doesn't exist`() = runBlocking {
        val deployment = VercelDeployment(
            id = "test-id",
            name = "test-project",
            url = "https://test.vercel.app",
            createdAt = 1234567890,
            meta = mapOf(),
            state = "READY"
        )

        coEvery { githubClient.repositoryExists("test-project") } returns false
        coEvery { 
            githubClient.createRepository(
                "test-project", 
                "Migrated from Vercel deployment: https://test.vercel.app",
                true
            )
        } returns "https://github.com/owner/test-project"

        val result = service.ensureGitHubRepository(deployment)
        assertEquals("https://github.com/owner/test-project", result)
    }

    @Test
    fun `ensureGitHubRepository returns existing repository URL when it exists`() = runBlocking {
        val deployment = VercelDeployment(
            id = "test-id",
            name = "test-project",
            url = "https://test.vercel.app",
            createdAt = 1234567890,
            meta = mapOf(),
            state = "READY"
        )

        coEvery { githubClient.repositoryExists("test-project") } returns true

        val result = service.ensureGitHubRepository(deployment)
        assertEquals("https://github.com/test-project", result)
    }

    @Test
    fun `ensureGitHubRepository throws exception when deployment name is null`() = runBlocking {
        val deployment = VercelDeployment(
            id = "test-id",
            name = null,
            url = "https://test.vercel.app",
            createdAt = 1234567890,
            meta = mapOf(),
            state = "READY"
        )

        assertThrows<IllegalArgumentException> {
            service.ensureGitHubRepository(deployment)
        }
    }
} 