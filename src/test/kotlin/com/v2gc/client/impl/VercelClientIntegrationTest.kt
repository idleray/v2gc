package com.v2gc.client.impl

import com.v2gc.model.VercelConfig
import com.v2gc.client.exception.VercelClientException
import com.v2gc.model.VercelDeployment
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.util.Properties

@Tag("integration")
@Disabled("Integration tests require valid Vercel credentials")
class VercelClientIntegrationTest {
    private lateinit var client: VercelClientImpl
    private lateinit var properties: Properties
    private lateinit var config: VercelConfig
    private lateinit var httpClient: HttpClient

    @BeforeEach
    fun setup() {
        properties = Properties().apply {
            val stream = javaClass.classLoader.getResourceAsStream("test.properties")
                ?: throw IllegalStateException("test.properties not found")
            load(stream)
        }

        config = VercelConfig(
            apiUrl = "https://api.vercel.com",
            token = properties.getProperty("VERCEL_TOKEN"),
            teamId = properties.getProperty("VERCEL_TEAM_ID")
        )

        httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                })
            }
            install(Logging) {
                level = LogLevel.ALL
            }
        }

        client = VercelClientImpl(config, httpClient)
    }

    @Test
    fun `should list deployments with limit`() = runBlocking {
        val deployments = client.listDeployments(limit = 1)
        assertNotNull(deployments)
        assertTrue(deployments.isNotEmpty())
    }

    @Test
    fun `should get deployment details`() = runBlocking {
        val deploymentId = properties.getProperty("TEST_DEPLOYMENT_ID")
        val deployment = client.getDeployment(deploymentId)
        assertNotNull(deployment)
        assertEquals(deploymentId, deployment.id)
    }

    @Test
    fun `should download deployment files`() = runBlocking {
        val deploymentId = properties.getProperty("TEST_DEPLOYMENT_ID")
        val tempDir = createTempDir("vercel-test")
        try {
            val deployment = client.getDeployment(deploymentId)
            client.downloadSourceFiles(deployment, tempDir)
            assertTrue(tempDir.listFiles()?.isNotEmpty() ?: false)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `should handle unauthorized response`() = runBlocking {
        val invalidHttpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                })
            }
        }
        val invalidClient = VercelClientImpl(
            VercelConfig(
                apiUrl = "https://api.vercel.com",
                token = "invalid_token",
                teamId = "invalid_team"
            ),
            invalidHttpClient
        )
        try {
            assertThrows<VercelClientException> {
                runBlocking {
                    invalidClient.listDeployments()
                }
            }
        } finally {
            invalidHttpClient.close()
        }
    }

    @Test
    fun `should handle large file downloads`() = runBlocking {
        val deploymentId = properties.getProperty("TEST_DEPLOYMENT_ID")
        val tempDir = createTempDir("vercel-test")
        try {
            val deployment = client.getDeployment(deploymentId)
            client.downloadSourceFiles(deployment, tempDir)
            assertTrue(tempDir.listFiles()?.any { it.length() > 0 } ?: false)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @AfterEach
    fun cleanup() {
        httpClient.close()
    }
} 