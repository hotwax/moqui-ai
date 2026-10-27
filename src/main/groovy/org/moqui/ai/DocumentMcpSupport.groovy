package org.moqui.ai

import groovy.json.JsonOutput
import java.util.regex.Pattern

/** Closed document transport policy. Poorti owns authorization, expiry, source access and
 * hashes; this layer validates its narrow DTOs before constructing any MCP media content.
 * No arbitrary service-provided MCP content is forwarded. All helpers are pure. */
class DocumentMcpSupport {
    static final String NAMESPACE = 'co.hotwax.poorti.document.DocumentTemplateServices.'
    static final Set<String> TOOL_SERVICES = ['list#DocumentTemplates', 'get#DocumentTemplate',
            'save#DocumentRevision', 'create#DocumentPreview', 'get#DocumentPreview',
            'list#DocumentTemplateHistory', 'activate#DocumentRevision', 'rollback#DocumentTemplate']
            .collect { NAMESPACE + it }.toSet().asImmutable()
    static final int INLINE_PNG_BYTES = 1024 * 1024
    static final int RESOURCE_BYTES = 10 * 1024 * 1024
    static final int MAX_PAGES = 20
    private static final Pattern URI_PATTERN = Pattern.compile(
            '^hotwax-document://preview/([A-Za-z0-9_-]{1,128})/(pdf|page/([1-9]|1[0-9]|20))$')
    private static final Set<String> WIRE_RESERVED = ['content', 'structuredContent', 'isError',
            '_meta', 'resultType', 'ttlMs', 'cacheScope'] as Set
    private static final Set<String> METADATA_IDS = ['documentTemplateId', 'previewId', 'manifestVersionName',
            'baseManifestVersionName', 'publishedVersionName', 'receiptId', 'publicationEventId', 'jobRunId',
            'validationResourceId', 'correlationId', 'documentType', 'status'] as Set
    private static final Set<String> METADATA_HASHES = ['manifestHash', 'baseManifestHash', 'bundleHash',
            'sourceHash', 'renderFingerprint'] as Set
    private static final Set<String> METADATA_NUMBERS = ['publicationSequence', 'expectedPublicationSequence',
            'sourceCount', 'pageCount', 'pageNumber', 'size', 'durationMs', 'totalCount'] as Set

    /** Null rejects the declaration. Options are server-controlled and inseparable so a
     * document tool can never accidentally fall back to logging its source/customer data. */
    static Map catalogOptions(String serviceName, String resultAdapter, String auditProfile) {
        if (resultAdapter == null && auditProfile == null) return TOOL_SERVICES.contains(serviceName) ? null : [:]
        if (!TOOL_SERVICES.contains(serviceName) || !(resultAdapter in ['document', 'document-preview']) ||
                auditProfile != 'document-metadata') return null
        if (resultAdapter == 'document-preview' && serviceName != NAMESPACE + 'get#DocumentPreview') return null
        return [resultAdapter: resultAdapter, auditProfile: auditProfile]
    }

    static boolean validUri(Object uri) { return uri instanceof String && URI_PATTERN.matcher(uri).matches() }

    static Map uriMetadata(Object uri) {
        if (!validUri(uri)) return [:]
        def match = URI_PATTERN.matcher((String) uri)
        match.matches()
        Map fields = [previewId: match.group(1)]
        if (match.group(3) != null) fields.pageNumber = Integer.parseInt(match.group(3))
        return fields
    }

    static String mimeType(String uri) { return uri.endsWith('/pdf') ? 'application/pdf' : 'image/png' }

    static Map privateResult(Map fields) { return fields + [ttlMs: 0, cacheScope: 'private'] }

    static Map failure(String code, String message, Map details = null) {
        Map body = [ok: false, code: code, message: message]
        if (details) body.details = details
        return privateResult([content: [[type: 'text', text: JsonOutput.toJson(body)]], structuredContent: body, isError: true])
    }

    static String domainCode(Map source, String fallback = 'DOCUMENT_OPERATION_FAILED') {
        return source?.code instanceof String && source.code ==~ /[A-Z][A-Z0-9_]{0,63}/ ? source.code : fallback
    }

    static Map domainFailure(Map source) {
        String message = source.message instanceof String ? source.message.take(1000) : 'The document operation failed.'
        return failure(domainCode(source), message, source.details instanceof Map ? (Map) source.details : null)
    }

    /** One metadata projection, never a recursive walk of arbitrary fields. Unknown keys,
     * reasons, bodies, customer references and artifact URIs/base64 cannot enter the audit. */
    static Map metadata(Map source) {
        Map safe = [:]
        for (String key in METADATA_IDS) {
            Object value = source?.get(key)
            if (value instanceof CharSequence && value.toString() ==~ /[A-Za-z0-9_.:-]{1,128}/) safe[key] = value.toString()
        }
        for (String key in METADATA_HASHES) {
            Object value = source?.get(key)
            if (value instanceof CharSequence && value.toString() ==~ /[A-Fa-f0-9]{32,128}/) safe[key] = value.toString()
        }
        for (String key in METADATA_NUMBERS) {
            Object value = source?.get(key)
            if (value instanceof Number && value >= 0 && value <= Long.MAX_VALUE && value.toString() ==~ /[0-9]+/) safe[key] = value
        }
        if (source?.sourceIds instanceof Collection) safe.sourceCount = source.sourceIds.size()
        if (source?.ok instanceof Boolean) safe.ok = source.ok
        if (source?.code instanceof String && source.code ==~ /[A-Z][A-Z0-9_]{0,63}/) safe.code = source.code
        if (source?.artifacts instanceof List) {
            List<Map> artifacts = ((List) source.artifacts).take(MAX_PAGES + 1).collect { descriptor(it) }.findAll { it != null }
            safe.artifactCount = artifacts.size()
            safe.artifactBytes = artifacts.sum(0L) { it.size as long }
        }
        return safe
    }

    /** Only standard resource descriptor fields cross the wire; no annotations, custom
     * URLs, descriptions, or caller-controlled MCP block types are copied. */
    static Map descriptor(Object candidate) {
        if (!(candidate instanceof Map)) return null
        Map item = (Map) candidate
        if (!validUri(item.uri) || item.mimeType != mimeType((String) item.uri)) return null
        if (!(item.size instanceof Number) || item.size <= 0 || item.size > RESOURCE_BYTES ||
                !(item.size.toString() ==~ /[0-9]+/)) return null
        if (!(item.name instanceof String) || item.name.length() == 0 || item.name.length() > 128 ||
                item.name.any { Character.isISOControl((char) it) }) return null
        return [uri: item.uri, name: item.name, mimeType: item.mimeType, size: item.size]
    }

    /** Preserve the lifecycle's page identity/dimensions in its domain DTO, independently
     * of standard MCP resource-link fields. Optional values must agree with the URI. */
    static Map domainDescriptor(Object candidate) {
        Map resource = descriptor(candidate)
        if (resource == null) return null
        Map item = (Map) candidate
        Map identity = uriMetadata(item.uri)
        String expectedKey = identity.pageNumber == null ? 'pdf' : 'page-' + identity.pageNumber
        if (item.artifactKey == expectedKey) resource.artifactKey = expectedKey
        if (identity.pageNumber != null && item.pageNumber == identity.pageNumber) resource.pageNumber = identity.pageNumber
        for (String key in ['widthPoints', 'heightPoints']) {
            Object value = item.get(key)
            if (value instanceof Number && value > 0 && value <= 1000000) resource[key] = value
        }
        return resource
    }

    /** Strict canonical base64, expected URI/MIME, byte cap and media signature. Source
     * hashes are checked by the authorized Poorti artifact reader before it returns bytes. */
    static Map binary(Object candidate, String requestedUri, int byteLimit = RESOURCE_BYTES) {
        if (!(candidate instanceof Map) || !validUri(requestedUri)) throw new IllegalArgumentException('Invalid artifact response')
        Map artifact = (Map) candidate
        if (artifact.uri != requestedUri || artifact.mimeType != mimeType(requestedUri) || !(artifact.blob instanceof String))
            throw new IllegalArgumentException('Invalid artifact response')
        String blob = artifact.blob
        if (blob.length() == 0 || blob.length() > ((byteLimit + 2L).intdiv(3) * 4))
            throw new IllegalArgumentException('Artifact exceeds transport limit')
        byte[] bytes = Base64.decoder.decode(blob)
        if (bytes.length == 0 || bytes.length > byteLimit || Base64.encoder.encodeToString(bytes) != blob)
            throw new IllegalArgumentException('Invalid artifact encoding')
        byte[] signature = artifact.mimeType == 'application/pdf' ? '%PDF-'.getBytes('US-ASCII') :
                [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a] as byte[]
        if (bytes.length < signature.length || !(0..<signature.length).every { bytes[it] == signature[it] })
            throw new IllegalArgumentException('Invalid artifact media')
        return [uri: requestedUri, mimeType: artifact.mimeType, blob: blob]
    }

    static Map toolResult(Map serviceResult, String adapter, Closure<Map> readArtifact) {
        Object candidate = serviceResult?.get('documentResult')
        if (!(candidate instanceof Map) || !(((Map) candidate).ok instanceof Boolean))
            throw new IllegalArgumentException('Invalid document service response')
        Map body = new LinkedHashMap((Map) candidate)
        WIRE_RESERVED.each { body.remove(it) }
        if (!body.ok) return domainFailure(body)
        List<Map> content = []
        if (adapter == 'document-preview' && body.artifacts instanceof List) {
            List<Map> artifacts = ((List) body.artifacts).take(MAX_PAGES + 1).collect { descriptor(it) }.findAll { it != null }
            body.artifacts = ((List) body.artifacts).take(MAX_PAGES + 1).collect { domainDescriptor(it) }.findAll { it != null }
            for (Map artifact in artifacts) content.add([type: 'resource_link'] + artifact)
            Map page = artifacts.find { it.mimeType == 'image/png' && it.size <= INLINE_PNG_BYTES }
            if (page != null) {
                Map artifactResult = readArtifact.call((String) page.uri)
                // Lifecycle.execute returns domain failures inside artifactResult too;
                // these are expected denials/readiness outcomes, not malformed binaries.
                if (artifactResult?.ok == false) return domainFailure(artifactResult)
                Map media = binary(artifactResult, (String) page.uri, INLINE_PNG_BYTES)
                if (Base64.decoder.decode((String) media.blob).length != (page.size as long))
                    throw new IllegalArgumentException('Artifact size does not match descriptor')
                content.add([type: 'image', data: media.blob, mimeType: 'image/png'])
            }
        }
        content.add(0, [type: 'text', text: JsonOutput.toJson(body)])
        return privateResult([content: content, structuredContent: body, isError: false])
    }

    static List<Map> templates() {
        return [[uriTemplate: 'hotwax-document://preview/{previewId}/pdf', name: 'Document preview PDF', mimeType: 'application/pdf'],
                [uriTemplate: 'hotwax-document://preview/{previewId}/page/{pageNumber}', name: 'Document preview page', mimeType: 'image/png']]
    }
}
