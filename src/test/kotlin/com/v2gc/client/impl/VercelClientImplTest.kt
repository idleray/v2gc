package com.v2gc.client.impl

import com.v2gc.client.exception.VercelApiException
import com.v2gc.client.exception.VercelAuthenticationException
import com.v2gc.client.exception.VercelFileDownloadException
import com.v2gc.model.DeploymentState
import com.v2gc.model.VercelConfig
import com.v2gc.model.VercelDeployment
import com.v2gc.model.VercelFile
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
import java.util.Base64

class VercelClientImplTest {
    private lateinit var mockEngine: MockEngine
    private lateinit var client: VercelClientImpl
    private val config = VercelConfig(
        token = "test-token",
        teamId = "test-team",
        apiUrl = "http://localhost"  // Changed to match the mock server URL
    )
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
        isLenient = true
        coerceInputValues = true
    }

    @BeforeEach
    fun setup() {
        mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            println("Mock received request: ${request.url}")
            
            when {
                path.endsWith("/v6/deployments/test-id") -> respond(
                    content = ByteReadChannel(json.encodeToString(ApiResponse(deployment = createTestDeployment()))),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                path.endsWith("/v6/deployments") -> respond(
                    content = ByteReadChannel(json.encodeToString(ApiResponse(deployments = listOf(createTestDeployment())))),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                path.endsWith("/v6/deployments/test-id/files") -> respond(
                    content = ByteReadChannel(json.encodeToString(createTestFiles())),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                path.endsWith("/files/test-uid") -> {
                    val fileContent = "test content"
                    val base64Content = Base64.getEncoder().encodeToString(fileContent.toByteArray())
                    respond(
                        content = ByteReadChannel("""{"data": "$base64Content"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> {
                    println("No matching mock for path: $path")
                    respond(
                        content = ByteReadChannel("Not found"),
                        status = HttpStatusCode.NotFound
                    )
                }
            }
        }

        val mockClient = HttpClient(mockEngine) {
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
            defaultRequest {
                url("http://localhost")  // Set base URL for tests
            }
        }

        client = VercelClientImpl(
            config = config,
            httpClient = mockClient
        )
    }

    @Test
    fun `getDeployment returns deployment when successful`() = runBlocking {
        val deployment = client.getDeployment("test-id")
        assertNotNull(deployment)
        assertEquals("test-id", deployment.id)
    }

    @Test
    fun `getDeployment throws VercelAuthenticationException when unauthorized`() {
        val unauthorizedEngine = MockEngine {
            respond(
                content = ByteReadChannel(json.encodeToString(ApiResponse<VercelDeployment>(
                    error = ErrorResponse("unauthorized", "Invalid token", true)
                ))),
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val unauthorizedClient = VercelClientImpl(
            config = config,
            httpClient = HttpClient(unauthorizedEngine) {
                install(ContentNegotiation) {
                    json(json)
                }
            }
        )

        assertThrows<VercelAuthenticationException> {
            runBlocking {
                unauthorizedClient.getDeployment("test-id")
            }
        }
    }

    @Test
    fun `listDeployments returns list when successful`() = runBlocking {
        val deployments = client.listDeployments(10)
        assertNotNull(deployments)
        assertEquals(1, deployments.size)
        assertEquals("test-id", deployments[0].id)
    }

    @Test
    fun `downloadSourceFiles downloads files successfully`(@TempDir tempDir: File) = runBlocking {
        val deployment = createTestDeployment()
        client.downloadSourceFiles(deployment, tempDir)
        
        // Verify the file structure was created
        val srcDir = File(tempDir, "src")
        assertTrue(srcDir.exists())
        assertTrue(srcDir.isDirectory)
        
        val testFile = File(srcDir, "test.txt")
        assertTrue(testFile.exists())
        assertEquals("test content", testFile.readText())
    }

    private fun createTestDeployment() = VercelDeployment(
        id = "test-id",
        url = "https://test.vercel.app",
        name = "test-deployment",
        meta = mapOf(),
        createdAt = 1234567890,
        state = "READY"
    )

    private fun createTestFiles() = listOf(
        VercelFile(
            name = "src",
            type = "directory",
            uid = "dir-uid",
            mode = 0,
            children = listOf(
                VercelFile(
                    name = "test.txt",
                    type = "file",
                    uid = "test-uid",
                    mode = 0,
                    children = null
                )
            )
        )
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