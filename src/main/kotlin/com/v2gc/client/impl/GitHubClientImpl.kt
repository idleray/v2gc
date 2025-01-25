package com.v2gc.client.impl

import com.v2gc.client.GitHubClient
import com.v2gc.model.GitHubConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

class GitHubClientImpl(
    private val config: GitHubConfig,
    private val httpClient: HttpClient? = null
) : GitHubClient {

    private val client = (httpClient ?: HttpClient(CIO)).config {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            })
        }
        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(config.token, "")
                }
                sendWithoutRequest { request ->
                    request.url.host == "api.github.com"
                }
            }
        }
        defaultRequest {
            url("https://api.github.com")
            header("Accept", "application/vnd.github.v3+json")
        }
    }

    private val credentialsProvider = UsernamePasswordCredentialsProvider(config.token, "")
    private val repoUrl = "https://github.com/${config.owner}/${config.repo}.git"

    override suspend fun repositoryExists(): Boolean {
        return try {
            val response = client.get("repos/${config.owner}/${config.repo}")
            response.status == HttpStatusCode.OK
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) {
                false
            } else {
                throw e
            }
        }
    }

    override suspend fun createRepository(): String {
        // Implementation to create new repository using GitHub API
        // TODO: Implement actual API call
        return ""
    }

    override fun cloneRepository(directory: File) {
        Git.cloneRepository()
            .setURI(repoUrl)
            .setDirectory(directory)
            .setCredentialsProvider(credentialsProvider)
            .call()
    }
} 