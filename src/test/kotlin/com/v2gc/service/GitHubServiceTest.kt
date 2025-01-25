package com.v2gc.service

import com.v2gc.client.GitHubClient
import com.v2gc.client.VercelClient
import com.v2gc.model.VercelDeployment
import com.v2gc.model.GitHubConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GitHubServiceTest {
    private val mockClient = mockk<GitHubClient>()
    private val config = GitHubConfig(
        token = "test-token",
        owner = "test-owner",
        repo = "test-repo"
    )
    private val service = GitHubService(mockClient, config)

    @Test
    fun `ensureRepositoryExists creates repository when it doesn't exist`() = runBlocking {
        coEvery { mockClient.repositoryExists() } returns false
        coEvery { mockClient.createRepository() } returns Unit

        service.ensureRepositoryExists()

        coVerify { 
            mockClient.repositoryExists()
            mockClient.createRepository()
        }
    }

    @Test
    fun `ensureRepositoryExists does nothing when repository exists`() = runBlocking {
        coEvery { mockClient.repositoryExists() } returns true

        service.ensureRepositoryExists()

        coVerify { 
            mockClient.repositoryExists()
            mockClient.createRepository() wasNot Called
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

        coEvery { mockClient.repositoryExists() } returns false
        coEvery { mockClient.createRepository() } returns Unit

        service.ensureGitHubRepository(deployment)

        coVerify { 
            mockClient.repositoryExists()
            mockClient.createRepository()
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

        coEvery { mockClient.repositoryExists() } returns true

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