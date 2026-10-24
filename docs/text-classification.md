# Audited text classification

`ai.DecisionServices.classify#Text` accepts `text`, a unique nonblank `labels` list
and a required `cacheScope`. It returns a permitted `label` (null for no match),
`agentRunId`, `statusId`, `errorType` and `cacheHit`.

The service uses the existing configured OpenAI provider's dedicated Decisions
operation with model `gpt-6-luna`. It does not run an agent, tools, knowledge,
conversation context or model failover. Each miss makes exactly one HTTP request.
Validation, cache lookup/result handling and audit orchestration use XML actions
in `service/ai/DecisionServices.xml`. The small `invokeTextClassification.groovy`
helper contains only the provider call and exception classification; atomic cache
loading and HTTP transport stay in the tool factory/provider classes.
The one-second full-response deadline cancels the underlying asynchronous HTTP
exchange on expiry; existing chat/agent transports and their timeout configuration
remain unchanged. The JDK transport is needed because the current framework
RestClient clamps timeouts to a minimum of two seconds.

Classification uses HTTP/1.1 so cancelling a timed-out request closes its affected
connection instead of retaining a stalled HTTP/2 connection for subsequent calls.
Healthy connections are still pooled. The one-second deadline and no-retry policy
remain fixed; this changes recovery, not the speed of an upstream response.

One existing AiAgentRun row retains each completed or failed attempt; no step audit
or additional provider interface is used. Agent ID is null; agentName identifies
the classification scope. Usage,
provider ID and request duration are retained when available. Audit writes are
required before a provider call and preserved on completion/failure; a database
failure is not silently converted into an optional provider failure.
The run ID is sent as `X-Client-Request-Id`; the provider response's `x-request-id`
is retained when the response has no JSON ID. Successful run details include the
reported `openai-processing-ms` alongside measured request time. Failure audit
retains exception class names (without remote exception text), and recognizes a
timeout anywhere in a wrapped cause chain.

Validated answers, including null/no-match, are cached for one hour in the native
Moqui `ai.classification.results` cache. Transient failures use
`ai.classification.failures` for 30 seconds. Both are bounded to 10,000 entries.
The SHA-256 key includes normalized text, exact sorted labels, scope, model and
request version. Result reuse returns the original audit ID without another
provider call. Atomic read-through loading in the tool factory coalesces concurrent
misses per key; the temporary loading map is cleared after each load. Retention
and eviction stay with the Moqui caches. Caches/loading are per process.

OpenAI prompt caching is separate from result reuse. The Decisions API requires a
reusable shared-input prefix of at least 1,024 tokens; these short classification
requests do not depend on that discount. No key, customer record or caller context
is added to a request by this service.

Regression and real-HTTP timeout tests live in hotwax/oms-test under
`co.hotwax.omstest.spock.aiclassification`. They use actual Moqui audit/cache
behavior and replace only the external provider response. The connector's live
HC Sandbox acceptance harness exercises the actual OpenAI Decisions endpoint.
The opt-in `verifyOpenAiConnectionRecovery.py` TLS fault regression demonstrates
recovery after an established connection stops responding, against an HTTP/2
control. See oms-test's connection-recovery documentation for the command.

API contract: https://developers.openai.com/api/docs/guides/decisions
