package com.v2gc.service

import com.v2gc.model.VercelDeployment

interface DeploymentService {
    suspend fun processDeployment(deploymentId: String)
    suspend fun getLatestDeployment(): VercelDeployment
    suspend fun syncToGit(deployment: VercelDeployment)
} 