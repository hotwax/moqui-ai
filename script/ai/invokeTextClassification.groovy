// XML owns validation, audit and caching. Keep only the provider exception boundary here.
classificationResponse = [:]
classificationFailureType = null
classificationFailureDetails = null
try {
    def provider = ec.factory.getTool('AI', Object.class).getProvider('openai')
    classificationResponse = provider.classify([text:text, labels:labels, clientRequestId:agentRunId]) ?: [:]
} catch (Exception failure) {
    Throwable cause = failure
    List causes = []
    while (cause != null && !causes.contains(cause)) {
        causes.add(cause)
        if (cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException) classificationFailureType = 'TIMEOUT'
        cause = cause.cause
    }
    classificationFailureType = classificationFailureType ?: 'PROVIDER_ERROR'
    classificationFailureDetails = causes.collect { it.class.name }.join(' <- ')
}
return null
