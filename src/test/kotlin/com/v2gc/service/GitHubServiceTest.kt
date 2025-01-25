package com.v2gc.service

import com.v2gc.client.GitHubClient
import com.v2gc.client.VercelClient
import com.v2gc.model.VercelDeployment
import com.v2gc.model.GitHubConfig
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GitHubServiceTest {
    private val mockGithubClient = mockk<GitHubClient>()
    private val mockVercelClient = mockk<VercelClient>()
    private val config = GitHubConfig(
        token = "test-token",
        owner = "test-owner",
        repo = "test-repo"
    )
    private val service = GitHubService(mockGithubClient, mockVercelClient, config)

    @Test
    fun `ensureRepositoryExists creates repository when it doesn't exist`() = runBlocking {
        coEvery { mockGithubClient.repositoryExists() } returns false
        coEvery { mockGithubClient.createRepository() } returns "https://github.com/test-repo"

        service.ensureRepositoryExists()

        coVerify { 
            mockGithubClient.repositoryExists()
            mockGithubClient.createRepository()
        }
    }

    @Test
    fun `ensureRepositoryExists does nothing when repository exists`() = runBlocking {
        coEvery { mockGithubClient.repositoryExists() } returns true

        service.ensureRepositoryExists()

        coVerify { 
            mockGithubClient.repositoryExists()
            coVerify(exactly = 0) { mockGithubClient.createRepository() }
        }
    }

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

        coEvery { mockGithubClient.repositoryExists() } returns false
        coEvery { mockGithubClient.createRepository() } returns "https://github.com/test-repo"

        val result = service.ensureGitHubRepository(deployment)
        assertEquals("https://github.com/test-repo", result)

        coVerify { 
            mockGithubClient.repositoryExists()
            mockGithubClient.createRepository()
        }
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

        coEvery { mockGithubClient.repositoryExists() } returns true

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