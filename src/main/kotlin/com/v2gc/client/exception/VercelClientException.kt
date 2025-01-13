package com.v2gc.client.exception

sealed class VercelClientException(message: String, cause: Throwable? = null) : Exception(message, cause)

class VercelAuthenticationException(message: String = "Authentication failed", cause: Throwable? = null) : 
    VercelClientException(message, cause)

class VercelApiException(message: String, cause: Throwable? = null) : 
    VercelClientException(message, cause)

class VercelFileDownloadException(message: String, cause: Throwable? = null) : 
    VercelClientException(message, cause) 