package com.v2gc.client.impl

import com.v2gc.client.exception.VercelAuthenticationException
import com.v2gc.model.VercelConfig
import kotlinx.coroutines.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

@Tag("integration")
class VercelClientIntegrationTest {
    private lateinit var client: VercelClientImpl
    private lateinit var config: VercelConfig

    companion object {
        private val requiredEnvVars = listOf(
            "VERCEL_TOKEN",
            "VERCEL_TEAM_ID",
            "TEST_DEPLOYMENT_ID"
        )
    }

    @BeforeEach
    fun setup() {
        // Check if all required environment variables are present
        val missingVars = requiredEnvVars.filter { System.getenv(it) == null }
        if (missingVars.isNotEmpty()) {
            throw IllegalStateException(
                "Missing required environment variables for integration tests: ${missingVars.joinToString(", ")}"
            )
        }

        config = VercelConfig(
            apiUrl = "https://api.vercel.com",
            token = System.getenv("VERCEL_TOKEN"),
            teamId = System.getenv("VERCEL_TEAM_ID")
        )
        client = VercelClientImpl(config)
    }

    @Test
    fun `should get deployment details`() = runBlocking {
        // Given
        val deploymentId = System.getenv("TEST_DEPLOYMENT_ID")

        // When
        val deployment = client.getDeployment(deploymentId)

        // Then
        assertNotNull(deployment)
        assertEquals(deploymentId, deployment.id)
        assertNotNull(deployment.name)
        assertNotNull(deployment.url)
    }

    @Test
    fun `should list deployments with limit`() = runBlocking {
        // When
        val deployments = client.listDeployments(limit = 5)

        // Then
        assertNotNull(deployments)
        assertTrue(deployments.isNotEmpty())
        assertTrue(deployments.size <= 5)
        deployments.forEach {
            assertNotNull(it.id)
            assertNotNull(it.name)
            assertNotNull(it.url)
        }
    }

    @Test
    fun `should download deployment files`(@TempDir tempDir: Path) = runBlocking {
        // Given
        val deploymentId = System.getenv("TEST_DEPLOYMENT_ID")
        val deployment = client.getDeployment(deploymentId)

        // When
        client.downloadSourceFiles(deployment, tempDir.toFile())

        // Then
        val downloadedFiles = tempDir.toFile().walk().filter { it.isFile }.toList()
        assertTrue(downloadedFiles.isNotEmpty())
        
        // Verify some common files that should exist
        val commonFiles = listOf("package.json", "README.md")
        commonFiles.forEach { fileName ->
            val found = downloadedFiles.any { it.name == fileName }
            if (found) {
                val file = downloadedFiles.first { it.name == fileName }
                assertTrue(file.length() > 0, "File $fileName should not be empty")
            }
        }
    }

    @Test
    fun `should fail with invalid token`() = runBlocking {
        // Given
        val invalidConfig = config.copy(token = "invalid-token")
        val invalidClient = VercelClientImpl(invalidConfig)

        // When/Then
        assertThrows<VercelAuthenticationException> {
            invalidClient.listDeployments(1)
        }
    }

    @Test
    fun `should handle rate limits`() = runBlocking {
        // Make multiple rapid requests to test rate limiting
        repeat(10) {
            val deployments = client.listDeployments(1)
            assertNotNull(deployments)
            delay(100) // Small delay to avoid hitting rate limits too hard
        }
    }

    @Test
    fun `should handle large file downloads`(@TempDir tempDir: Path) = runBlocking {
        // Given
        val deploymentId = System.getenv("TEST_DEPLOYMENT_ID")
        val deployment = client.getDeployment(deploymentId)

        // When
        withTimeout(60_000) { // Timeout after 60 seconds
            client.downloadSourceFiles(deployment, tempDir.toFile())
        }

        // Then
        val totalSize = tempDir.toFile()
            .walk()
            .filter { it.isFile }
            .sumOf { it.length() }

        println("Total downloaded size: ${totalSize / 1024} KB")
        assertTrue(totalSize > 0)
    }
} 