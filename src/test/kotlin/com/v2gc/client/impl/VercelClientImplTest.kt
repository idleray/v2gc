package com.v2gc.client.impl

import com.v2gc.client.exception.VercelApiException
import com.v2gc.client.exception.VercelAuthenticationException
import com.v2gc.client.exception.VercelFileDownloadException
import com.v2gc.model.DeploymentState
import com.v2gc.model.VercelConfig
import com.v2gc.model.VercelDeployment
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    private val json = Json { 
        ignoreUnknownKeys = true 
        coerceInputValues = true
    }

    @BeforeEach
    fun setup() {
        config = VercelConfig(
            apiUrl = "https://api.vercel.com",
            token = "test-token",
            teamId = "test-team"
        )
    }

    private fun createTestClient(engine: MockEngine): VercelClientImpl {
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
            install(Auth) {
                bearer {
                    loadTokens {
                        BearerTokens(config.token, "")
                    }
                }
            }
        }
        return VercelClientImpl(config, httpClient)
    }

    @Test
    fun `getDeployment should return deployment when successful`() = runBlocking {
        // Given
        val deployment = createTestDeployment()
        mockEngine = MockEngine { request ->
            assertEquals("Bearer ${config.token}", request.headers[HttpHeaders.Authorization])
            assertEquals("test-team", request.url.parameters["teamId"])
            respond(
                content = json.encodeToString(ApiResponse(deployment = deployment)),
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
                content = json.encodeToString(ApiResponse<VercelDeployment>(
                    error = ErrorResponse("forbidden", "Not authorized", true)
                )),
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
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
                    content = json.encodeToString(ApiResponse<VercelDeployment>(
                        error = ErrorResponse("error", "Internal Server Error")
                    )),
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(
                    content = json.encodeToString(ApiResponse(deployment)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        client = createTestClient(mockEngine)

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
                content = json.encodeToString(ApiResponse<VercelDeployment>(
                    error = ErrorResponse("error", "Internal Server Error")
                )),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        client = createTestClient(mockEngine)

        // When/Then
        assertThrows<VercelApiException> {
            client.getDeployment("test-id")
        }
        assertEquals(3, attempts)
    }

    @Test
    fun `listDeployments should return list of deployments`() = runBlocking {
        // Given
        val deployments = listOf(createTestDeployment(), createTestDeployment().copy(id = "test-id-2"))
        mockEngine = MockEngine {
            respond(
                content = json.encodeToString(ApiResponse(deployments = deployments)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        client = createTestClient(mockEngine)

        // When
        val result = client.listDeployments(10)

        // Then
        assertEquals(2, result.size)
        assertEquals(deployments[0].id, result[0].id)
        assertEquals(deployments[1].id, result[1].id)
    }

    private fun createTestDeployment() = VercelDeployment(
        id = "test-id",
        name = "test-deployment",
        url = "https://test.vercel.app",
        state = DeploymentState.READY,
        createdAt = 1704067200000, // 2024-01-01T00:00:00Z in milliseconds
        meta = mapOf("version" to "1.0.0")
    )
}

@Serializable
data class ApiResponse<T>(
    val deployment: T? = null,
    val deployments: List<T>? = null,
    val files: List<T>? = null,
    val error: ErrorResponse? = null
)

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
    val invalidToken: Boolean = false
) 