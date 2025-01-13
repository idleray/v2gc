package com.v2gc.client

import com.v2gc.model.VercelDeployment
import java.io.File

interface VercelClient {
    suspend fun getDeployment(deploymentId: String): VercelDeployment
    suspend fun downloadSourceFiles(deployment: VercelDeployment, targetDir: File)
    suspend fun listDeployments(limit: Int = 10): List<VercelDeployment>
} 