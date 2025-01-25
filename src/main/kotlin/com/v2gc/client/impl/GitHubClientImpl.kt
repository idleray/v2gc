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
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig
import org.eclipse.jgit.util.FS
import java.io.File
import com.jcraft.jsch.Session

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

    private val repoUrl = "git@github.com:${config.owner}/${config.repo}.git"

    @Serializable
    private data class CreateRepoRequest(
        val name: String,
        val description: String? = null,
        val private: Boolean = true,
        val auto_init: Boolean = false
    )

    @Serializable
    private data class CreateRepoResponse(
        val html_url: String,
        val clone_url: String,
        val ssh_url: String
    )

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
        val response = client.post("user/repos") {
            contentType(ContentType.Application.Json)
            setBody(CreateRepoRequest(
                name = config.repo,
                description = "Migrated from Vercel",
                private = true
            ))
        }

        when (response.status) {
            HttpStatusCode.Created -> {
                val repoResponse = response.body<CreateRepoResponse>()
                println("Created repository: ${repoResponse.html_url}")
                return repoResponse.html_url
            }
            HttpStatusCode.Unauthorized -> {
                throw IllegalStateException("GitHub authentication failed. Please check your token.")
            }
            HttpStatusCode.UnprocessableEntity -> {
                throw IllegalStateException("Repository already exists or name is invalid.")
            }
            else -> {
                throw IllegalStateException("Failed to create repository: ${response.status}")
            }
        }
    }

    override fun cloneRepository(directory: File) {
        Git.cloneRepository()
            .setURI(repoUrl)
            .setDirectory(directory)
            .setTransportConfigCallback { transport ->
                if (transport is SshTransport) {
                    transport.sshSessionFactory = object : JschConfigSessionFactory() {
                        override fun configure(host: OpenSshConfig.Host, session: Session) {
                            session.setConfig("StrictHostKeyChecking", "no")
                        }
                    }
                }
            }
            .call()
    }
} 