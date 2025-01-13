package com.v2gc.client.impl

import com.v2gc.client.exception.VercelApiException
import com.v2gc.client.exception.VercelAuthenticationException
import com.v2gc.client.exception.VercelFileDownloadException
import com.v2gc.model.DeploymentState
import com.v2gc.model.VercelConfig
import com.v2gc.model.VercelDeployment
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class VercelClientImplTest {
    private lateinit var mockEngine: MockEngine
    private lateinit var client: VercelClientImpl
    private lateinit var config: VercelConfig

    @BeforeEach
    fun setup() {
        config = VercelConfig(
            apiUrl = "https://api.vercel.com",
            token = "test-token",
            teamId = "test-team"
        )
    }

    @Test
    fun `getDeployment should return deployment when successful`() = runBlocking {
        // Given
        val deployment = createTestDeployment()
        mockEngine = MockEngine { request ->
            assertEquals("Bearer ${config.token}", request.headers["Authorization"])
            assertEquals("test-team", request.url.parameters["teamId"])
            respond(
                content = Json.encodeToString(deployment),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        client = createTestClient(mockEngine)

        // When
        val result = client.getDeployment("test-id")

        // Then
        assertEquals(deployment.id, result.id)
        assertEquals(deployment.name, result.name)
        assertEquals(deployment.state, result.state)
    }

    @Test
    fun `getDeployment should throw VercelAuthenticationException on unauthorized`() = runBlocking {
        // Given
        mockEngine = MockEngine {
            respond(
                content = "Unauthorized",
                status = HttpStatusCode.Unauthorized
            )
        }
        client = createTestClient(mockEngine)

        // When/Then
        assertThrows<VercelAuthenticationException> {
            client.getDeployment("test-id")
        }
    }

    @Test
    fun `getDeployment should retry on failure and succeed`() = runBlocking {
        // Given
        val deployment = createTestDeployment()
        var attempts = 0
        mockEngine = MockEngine { request ->
            attempts++
            if (attempts < 2) {
                respond(
                    content = "Server Error",
                    status = HttpStatusCode.InternalServerError
                )
            } else {
                respond(
                    content = Json.encodeToString(deployment),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        client = VercelClientImpl(config, retryAttempts = 3, retryDelay = 100)

        // When
        val result = client.getDeployment("test-id")

        // Then
        assertEquals(2, attempts)
        assertEquals(deployment.id, result.id)
    }

    @Test
    fun `getDeployment should fail after max retries`() = runBlocking {
        // Given
        var attempts = 0
        mockEngine = MockEngine {
            attempts++
            respond(
                content = "Server Error",
                status = HttpStatusCode.InternalServerError
            )
        }
        client = VercelClientImpl(config, retryAttempts = 3, retryDelay = 100)

        // When/Then
        val exception = assertThrows<VercelApiException> {
            client.getDeployment("test-id")
        }
        assertEquals(3, attempts)
        assertTrue(exception.message?.contains("Operation failed after 3 attempts") ?: false)
    }

    @Test
    fun `listDeployments should return list of deployments`() = runBlocking {
        // Given
        val deployments = listOf(createTestDeployment(), createTestDeployment().copy(id = "test-id-2"))
        val response = DeploymentResponse(deployments)
        mockEngine = MockEngine {
            respond(
                content = Json.encodeToString(response),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        client = createTestClient(mockEngine)

        // When
        val result = client.listDeployments(2)

        // Then
        assertEquals(2, result.size)
        assertEquals(deployments[0].id, result[0].id)
        assertEquals(deployments[1].id, result[1].id)
    }

    @Test
    fun `downloadSourceFiles should download files successfully`(@TempDir tempDir: Path) = runBlocking {
        // Given
        val deployment = createTestDeployment()
        val files = listOf(
            VercelFile("test.txt", 10, "file"),
            VercelFile("dir/test2.txt", 20, "file")
        )
        var fileListRequested = false
        var filesDownloaded = 0

        mockEngine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/files") -> {
                    fileListRequested = true
                    respond(
                        content = Json.encodeToString(files),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                request.url.encodedPath.contains("/files/") -> {
                    filesDownloaded++
                    respond(
                        content = ByteReadChannel("test content"),
                        status = HttpStatusCode.OK
                    )
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }
        client = createTestClient(mockEngine)

        // When
        client.downloadSourceFiles(deployment, tempDir.toFile())

        // Then
        assertTrue(fileListRequested)
        assertEquals(2, filesDownloaded)
        assertTrue(File(tempDir.toFile(), "test.txt").exists())
        assertTrue(File(tempDir.toFile(), "dir/test2.txt").exists())
    }

    @Test
    fun `downloadSourceFiles should throw VercelFileDownloadException on failure`(@TempDir tempDir: Path) = runBlocking {
        // Given
        val deployment = createTestDeployment()
        mockEngine = MockEngine {
            respond(
                content = "Error",
                status = HttpStatusCode.InternalServerError
            )
        }
        client = createTestClient(mockEngine)

        // When/Then
        assertThrows<VercelFileDownloadException> {
            client.downloadSourceFiles(deployment, tempDir.toFile())
        }
    }

    private fun createTestClient(engine: MockEngine) = VercelClientImpl(
        config = config,
        retryAttempts = 1,
        retryDelay = 0
    )

    private fun createTestDeployment() = VercelDeployment(
        id = "test-id",
        name = "test-deployment",
        url = "https://test.vercel.app",
        state = DeploymentState.READY,
        createdAt = 1234567890,
        meta = mapOf("version" to "2")
    )
} 