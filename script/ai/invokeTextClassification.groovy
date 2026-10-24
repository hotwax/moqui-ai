// XML owns validation, audit and caching. Keep only the provider exception boundary here.
classificationResponse = [:]
classificationFailureType = null
try {
    def provider = ec.factory.getTool('AI', Object.class).getProvider('openai')
    classificationResponse = provider.classify([text:text, labels:labels]) ?: [:]
} catch (Exception failure) {
    Throwable cause = failure
    while (cause.cause != null && cause.cause != cause) cause = cause.cause
    classificationFailureType = (cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException) ? 'TIMEOUT' : 'PROVIDER_ERROR'
}
return null
