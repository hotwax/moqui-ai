# Plan: REST APIs for AI OMS Agent Integration in Order Manager

This document outlines the REST API design required to integrate an AI-powered OMS assistant (such as `nn-oms-assistant`) into the client-facing **Order Manager** app. It details the endpoints, request/response models, and the backing Moqui services required to support the conversational user experience and human-in-the-loop approval gating.

---

## 1. User Experience Overview

A conversational agent interface in the Order Manager application requires three core UX capabilities:
1. **The Chat Interface:** Users can start new conversations, see their message history, and submit new questions or commands (e.g., *"Summarize the approved orders from yesterday"*).
2. **Human-in-the-Loop Approvals:** If the agent proposes a mutating action (like canceling an order or changing a facility assignment), the action is suspended. The user must see an inline or dashboard notification to **approve** or **reject** the specific tool call before the agent resumes.
3. **Conversational Observability:** Displaying the run steps and spend telemetry (costs and tokens) optionally for transparency.

---

## 2. Proposed REST API Resource Definition (`ai.rest.xml`)

To expose the required services to a mobile/web client, a new REST resource file [ai.rest.xml](file:///Users/adityapatel/Documents/GitHub/moqui-ai/service/ai.rest.xml) must be created in the `moqui-ai` component.

### Endpoint Structure

```
/rest/s1/ai
├── /conversations
│   ├── POST - Create a new conversation
│   └── /{conversationId}
│       ├── /messages
│       │   └── GET - Get conversation history
│       └── /run
│           └── POST - Send message and execute agent loop
└── /approvals
    ├── /pending
    │   └── GET - Retrieve pending approvals requiring operator review
    └── /{approvalId}
        ├── /approve
        │   └── POST - Approve and resume
        └── /reject
            └── POST - Reject and resume
```

---

## 3. Detailed Endpoint Specifications

### 3.1 Start a Conversation
Creates a new conversation session associated with the logged-in user.

* **Path:** `POST /rest/s1/ai/conversations`
* **Authentication:** Required (User token)
* **Backing Service:** [ai.AgentServices.create#Conversation](file:///Users/adityapatel/Documents/GitHub/moqui-ai/service/ai/AgentServices.xml#L138)
* **Request Body:**
  ```json
  {
    "agentId": "AICMP_AGENT",
    "title": "Order Support Chat"
  }
  ```
  *(Note: If `title` is omitted or null, the thread will be auto-named asynchronously on the first turn using the agent's LLM model).*
* **Response Body (200 OK):**
  ```json
  {
    "conversationId": "100001"
  }
  ```

---

### 3.2 Send Message & Execute Agent Turn
Submits a user prompt, triggers the autonomous agent loop, and returns the agent's response. If the agent hits a tool call requiring approval, the response will notify the client that the run is `SUSPENDED` and awaits action.

* **Path:** `POST /rest/s1/ai/conversations/{conversationId}/run`
* **Authentication:** Required
* **Backing Service:** [ai.AgentServices.run#Agent](file:///Users/adityapatel/Documents/GitHub/moqui-ai/service/ai/AgentServices.xml#L7)
* **Request Body:**
  ```json
  {
    "userMessage": "Show me recent orders and cancel order 10023"
  }
  ```
* **Response Body - Completed Run (200 OK):**
  ```json
  {
    "agentRunId": "90001",
    "statusId": "AI_RUN_COMPLETED",
    "assistantMessage": "Here are your recent orders. I have successfully processed the request.",
    "awaitingApproval": false,
    "tokensIn": 1024,
    "tokensOut": 256,
    "estimatedCost": 0.0015
  }
  ```
* **Response Body - Suspended/Gated Run (200 OK):**
  ```json
  {
    "agentRunId": "90002",
    "statusId": "AI_RUN_SUSPENDED",
    "assistantMessage": null,
    "awaitingApproval": true,
    "approvalIds": ["80001"]
  }
  ```

---

### 3.3 Get Conversation History
Retrieves the paginated message log for the active chat window.

* **Path:** `GET /rest/s1/ai/conversations/{conversationId}/messages`
* **Authentication:** Required
* **Backing Mechanism:** Entity Find on `moqui.ai.AiConversationMessage` (matching `conversationId`, ordered by `messageSeqId` ascending).
* **Response Body (200 OK):**
  ```json
  {
    "messages": [
      {
        "messageSeqId": "01",
        "role": "user",
        "content": "Show me recent orders",
        "fromDate": "2026-06-09T12:00:00Z"
      },
      {
        "messageSeqId": "02",
        "role": "assistant",
        "content": "Here is a list of recent orders...",
        "fromDate": "2026-06-09T12:00:05Z"
      }
    ]
  }
  ```

---

### 3.4 List Pending Approvals
Lists all pending tool calls that require operator decision. It uses a service that automatically filters out preview runs.

* **Path:** `GET /rest/s1/ai/approvals/pending`
* **Authentication:** Required
* **Backing Service:** [ai.ApprovalServices.get#PendingApproval](file:///Users/adityapatel/Documents/GitHub/moqui-ai/service/ai/ApprovalServices.xml#L68)
* **Response Body (200 OK):**
  ```json
  {
    "approvalList": [
      {
        "approvalId": "80001",
        "agentRunId": "90002",
        "toolName": "cancel_order",
        "serviceName": "co.hotwax.oms.OrderServices.cancel#Order",
        "arguments": "{\"orderId\":\"10023\",\"reason\":\"requested by operator\"}",
        "requestedDate": "2026-06-09T13:42:00Z"
      }
    ]
  }
  ```

---

### 3.5 Submit Approval Decision

Exposes two endpoints to either approve or reject a held tool call and resume the agent runner loop automatically.

#### 3.5.1 Approve Tool Call
* **Path:** `POST /rest/s1/ai/approvals/{approvalId}/approve`
* **Authentication:** Required
* **Backing Service:** [ai.ApprovalServices.approve#ToolCall](file:///Users/adityapatel/Documents/GitHub/moqui-ai/service/ai/ApprovalServices.xml#L5)
* **Request Body:**
  ```json
  {
    "decisionNote": "Valid cancellation request"
  }
  ```
* **Response Body (200 OK):**
  ```json
  {
    "agentRunId": "90002",
    "runStatusId": "AI_RUN_RUNNING"
  }
  ```

#### 3.5.2 Reject Tool Call
* **Path:** `POST /rest/s1/ai/approvals/{approvalId}/reject`
* **Authentication:** Required
* **Backing Service:** [ai.ApprovalServices.reject#ToolCall](file:///Users/adityapatel/Documents/GitHub/moqui-ai/service/ai/ApprovalServices.xml#L15)
* **Request Body:**
  ```json
  {
    "decisionNote": "Denying cancel request"
  }
  ```
* **Response Body (200 OK):**
  ```json
  {
    "agentRunId": "90002",
    "runStatusId": "AI_RUN_RUNNING"
  }
  ```

---

## 4. Implementation Guidelines

1. **Service REST Expose (`ai.rest.xml`):**
   A standard REST XML must map these paths to the target services. Services like [run#Agent](file:///Users/adityapatel/Documents/GitHub/moqui-ai/service/ai/AgentServices.xml#L7) will need to be configured for remote REST access.
2. **Authentication & Multi-Tenancy:**
   All endpoints must inherit standard Moqui REST authentication (JWT/OAuth token). The `userId` and tenancy boundaries are resolved server-side using the `ExecutionContext` (`ec.user.userId`).
3. **Resiliency & Timeouts:**
   Since agent runs involve remote LLM requests, client-side HTTP timeouts for `/run` must be configured generously (e.g., 30–60 seconds). If the client times out, the run continues on the server, and the client can recover the state by polling `/conversations/{conversationId}/messages`.
