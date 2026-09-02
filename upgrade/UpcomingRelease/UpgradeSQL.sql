    CREATE TABLE AI_TOOL (
        TOOL_ID varchar(40),
        TOOL_NAME varchar(63),
        VERB varchar(63),
        NOUN varchar(63),
        DESCRIPTION varchar(4095),
        SERVICE_NAME varchar(255),
        EFFECT_ENUM_ID varchar(40),
        EXPOSABLE char(1),
        REQUIRES_APPROVAL char(1),
        SOURCE_COMPONENT varchar(255),
        CREATED_BY_USER_ID varchar(40),
        STATUS_ID varchar(40),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (TOOL_ID),
        UNIQUE KEY AI_TOOL_NAME (TOOL_NAME),
        KEY IDXAiToolATEffectEnumeration (EFFECT_ENUM_ID),
        KEY IDXAiToolStatusItem (STATUS_ID),
        CONSTRAINT ai_tool_ibfk_1 FOREIGN KEY (EFFECT_ENUM_ID) REFERENCES ENUMERATION (ENUM_ID)
    );

    CREATE TABLE AI_TOOL_DENYLIST (
        SERVICE_PATTERN varchar(255),
        REASON varchar(255),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (SERVICE_PATTERN)
    );

    CREATE TABLE AI_AGENT (
        AGENT_ID varchar(40),
        AGENT_NAME varchar(63),
        DESCRIPTION varchar(4095),
        PROVIDER_NAME varchar(63),
        MODEL_NAME varchar(255),
        SYSTEM_PROMPT longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        RESPONSE_SCHEMA longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        CONTEXT_STRATEGY varchar(63),
        CONTEXT_WINDOW_MESSAGES decimal(20,0) DEFAULT NULL,
        CONTEXT_WINDOW_CHARS decimal(20,0) DEFAULT NULL,
        KNOWLEDGE_MAX_CHARS decimal(20,0) DEFAULT NULL,
        REASONING_EFFORT varchar(63),
        MAX_ITERATIONS decimal(20,0) DEFAULT NULL,
        MAX_TOKENS decimal(20,0) DEFAULT NULL,
        MAX_COST decimal(26,6) DEFAULT NULL,
        MAX_TOOL_CALLS_PER_TURN decimal(20,0) DEFAULT NULL,
        STATUS_ID varchar(40),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (AGENT_ID),
        UNIQUE KEY AI_AGENT_NAME (AGENT_NAME),
        KEY IDXAiAgentStatusItem (STATUS_ID),
        CONSTRAINT ai_agent_ibfk_1 FOREIGN KEY (STATUS_ID) REFERENCES STATUS_ITEM (STATUS_ID)
    );

    CREATE TABLE AI_AGENT_TOOL (
        AGENT_ID varchar(40),
        TOOL_ID varchar(40),
        REQUIRES_APPROVAL_OVERRIDE char(1),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (AGENT_ID,TOOL_ID),
        KEY IDXAiAgentToolAiAgent (AGENT_ID),
        CONSTRAINT ai_agent_tool_ibfk_1 FOREIGN KEY (AGENT_ID) REFERENCES AI_AGENT (AGENT_ID)
    );

    CREATE TABLE AI_AGENT_MODEL (
        AGENT_ID varchar(40),
        PRIORITY decimal(20,0) NOT NULL,
        PROVIDER_NAME varchar(63),
        MODEL_NAME varchar(255),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (AGENT_ID,PRIORITY)
        );

    CREATE TABLE AI_AGENT_RUN (
        AGENT_RUN_ID varchar(40),
        AGENT_ID varchar(40),
        AGENT_NAME varchar(63),
        USER_ID varchar(40),
        STARTED_DATE datetime(3) DEFAULT NULL,
        ENDED_DATE datetime(3) DEFAULT NULL,
        STATUS_ID varchar(40),
        PROVIDER_NAME varchar(63),
        MODEL_NAME varchar(255),
        SERVED_BY_MODEL_ID varchar(255),
        PROVIDER_RUN_ID varchar(255),
        USER_MESSAGE longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        ASSISTANT_MESSAGE longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        ITERATIONS decimal(20,0) DEFAULT NULL,
        TOKENS_IN decimal(20,0) DEFAULT NULL,
        TOKENS_OUT decimal(20,0) DEFAULT NULL,
        ESTIMATED_COST decimal(26,6) DEFAULT NULL,
        ERROR_TEXT longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        PENDING_STATE longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        CONVERSATION_ID varchar(40),
        IS_PREVIEW char(1),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (AGENT_RUN_ID),
        KEY IDXAiAgentRunStatusItem (STATUS_ID)
    );

    CREATE TABLE AI_AGENT_RUN_STEP (
        AGENT_RUN_ID varchar(40),
        STEP_SEQ_ID varchar(40),
        STEP_TYPE varchar(63),
        TOKENS_IN decimal(20,0) DEFAULT NULL,
        TOKENS_OUT decimal(20,0) DEFAULT NULL,
        FINISH_REASON varchar(63),
        SUCCESS char(1),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (AGENT_RUN_ID,STEP_SEQ_ID),
        KEY IDXAiAgentRunStepAiAgentRun (AGENT_RUN_ID),
        CONSTRAINT ai_agent_run_step_ibfk_1 FOREIGN KEY (AGENT_RUN_ID) REFERENCES AI_AGENT_RUN (AGENT_RUN_ID)
    );

    CREATE TABLE AI_TOOL_CALL (
        TOOL_CALL_ID varchar(40),
        SOURCE_ENUM_ID varchar(40),
        USER_ID varchar(40),
        AGENT_RUN_ID varchar(40),
        STEP_SEQ_ID varchar(40),
        PROVIDER_CALL_ID varchar(63),
        TOOL_ID varchar(40),
        TOOL_NAME varchar(255),
        SERVICE_NAME varchar(255),
        ARGUMENTS longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        RESULT longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        SUCCESS char(1),
        ERROR_TEXT longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        DURATION_MS decimal(20,0) DEFAULT NULL,
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (TOOL_CALL_ID),
        KEY AI_TOOL_CALL_RUN (AGENT_RUN_ID),
        KEY IDXAiToolCallATCSourceEnumeration (SOURCE_ENUM_ID),
        CONSTRAINT ai_tool_call_ibfk_1 FOREIGN KEY (SOURCE_ENUM_ID) REFERENCES ENUMERATION (ENUM_ID)
    );

    CREATE TABLE AI_TOOL_CALL_REQUEST (
        TOOL_CALL_REQUEST_ID varchar(40),
        AGENT_RUN_ID varchar(40),
        STEP_SEQ_ID varchar(40),
        TOOL_CALL_ID varchar(40),
        TOOL_NAME varchar(255),
        SERVICE_NAME varchar(255),
        ARGUMENTS longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        STATUS_ID varchar(40),
        REQUESTED_BY_USER_ID varchar(40),
        REQUESTED_DATE datetime(3) DEFAULT NULL,
        DECIDED_BY_USER_ID varchar(40),
        DECIDED_DATE datetime(3) DEFAULT NULL,
        DECISION_NOTE varchar(4095),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (TOOL_CALL_REQUEST_ID),
        KEY IDXAiToolCallRequestStatusItem (STATUS_ID)
    );

    CREATE TABLE AI_CONVERSATION (
        CONVERSATION_ID varchar(40),
        AGENT_ID varchar(40),
        USER_ID varchar(40),
        TITLE varchar(255),
        CREATED_DATE datetime(3) DEFAULT NULL,
        SUMMARY_TEXT longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        SUMMARY_THRU_MESSAGE_SEQ_ID varchar(40),
        STATUS_ID varchar(40),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (CONVERSATION_ID),
        KEY IDXAiConversationStatusItem (STATUS_ID)
    );

    CREATE TABLE AI_CONVERSATION_MESSAGE (
        CONVERSATION_ID varchar(40),
        MESSAGE_SEQ_ID varchar(40),
        ROLE varchar(63),
        CONTENT longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        TOOL_CALLS longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        TOOL_CALL_ID varchar(255),
        AGENT_RUN_ID varchar(40),
        CREATED_DATE datetime(3) DEFAULT NULL,
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (CONVERSATION_ID,MESSAGE_SEQ_ID),
        KEY IDXAiConversationMessageAiConversation (CONVERSATION_ID),
        CONSTRAINT ai_conversation_message_ibfk_1 FOREIGN KEY (CONVERSATION_ID) REFERENCES AI_CONVERSATION (CONVERSATION_ID)
    );

    CREATE TABLE AI_CONVERSATION_fact (
        CONVERSATION_ID varchar(40),
        FACT_KEY varchar(255),
        FACT_VALUE longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
        AGENT_RUN_ID varchar(40),
        CREATED_DATE datetime(3) DEFAULT NULL,
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (CONVERSATION_ID,FACT_KEY)
    );

    CREATE TABLE AI_MODEL_PRICE (
        PROVIDER_NAME varchar(63),
        MODEL_NAME varchar(255),
        FROM_DATE datetime(3) NOT NULL,
        THRU_DATE datetime(3) DEFAULT NULL,
        INPUT_PRICE_PER_MILLION decimal(25,5) DEFAULT NULL,
        OUTPUT_PRICE_PER_MILLION decimal(25,5) DEFAULT NULL,
        CURRENCY_UOM_ID varchar(40),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (PROVIDER_NAME,MODEL_NAME,FROM_DATE)
    );

    CREATE TABLE AI_DOMAIN_TERM (
        TERM_ID varchar(40),
        TERM varchar(63),
        TERM_KIND varchar(40),
        DESCRIPTION varchar(4095),
        SOURCE_TYPE varchar(40),
        STATUS_ID varchar(40),
        USAGE_COUNT decimal(20,0) DEFAULT NULL,
        OWNER_SCOPE varchar(40),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (TERM_ID),
        UNIQUE KEY AI_TERM_UNIQUE (TERM,TERM_KIND,OWNER_SCOPE),
        KEY IDXAiDomainTermStatusIte (STATUS_ID)
    );

    CREATE TABLE AI_TERM_SYNONYM (
        TERM_ID varchar(40),
        SYNONYM varchar(63),
        SOURCE_TYPE varchar(40),
        STATUS_ID varchar(40),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (TERM_ID,SYNONYM),
        KEY IDXAiTermSynonymAiDomainTer (TERM_ID),
        KEY IDXAiTermSynonymStatusIte (STATUS_ID),
        CONSTRAINT ai_term_synonym_ibfk_1 FOREIGN KEY (TERM_ID) REFERENCES AI_DOMAIN_TERM (TERM_ID)
    );

    CREATE TABLE AI_NAMING_SIGNAL (
        SIGNAL_ID varchar(40),
        SIGNAL_TYPE varchar(40),
        INTENT_TEXT varchar(4095),
        SUGGESTED_NAME varchar(255),
        CHOSEN_NAME varchar(255),
        WAS_OVERRIDDEN char(1),
        USER_ID varchar(40),
        CREATED_DATE datetime(3) DEFAULT NULL,
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (SIGNAL_ID)
    );

    CREATE TABLE AI_KNOWLEDGE_TOPIC (
        TOPIC_ID varchar(40),
        TOPIC_NAME varchar(63),
        DESCRIPTION varchar(4095),
        CONTENT_LOCATION varchar(255),
        STATUS_ID varchar(40),
        FROM_DATE datetime(3) DEFAULT NULL,
        THRU_DATE datetime(3) DEFAULT NULL,
        OWNER_SCOPE varchar(40),
        CREATED_BY_USER_ID varchar(40),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (TOPIC_ID),
        UNIQUE KEY AI_KNOW_TOPIC_NAME (TOPIC_NAME),
        KEY IDXAiKnowledgeTopicStatusItem (STATUS_ID)
    );

    CREATE TABLE AI_AGENT_KNOWLEDGE (
        AGENT_ID varchar(40),
        TOPIC_ID varchar(40),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (AGENT_ID,TOPIC_ID),
        KEY IDXAiAgentKnowledgeAiAgent (AGENT_ID),
        CONSTRAINT ai_agent_knowledge_ibfk_1 FOREIGN KEY (AGENT_ID) REFERENCES AI_AGENT (AGENT_ID)
    );

    CREATE TABLE AI_CAPABILITY_REQUEST (
        CAPABILITY_REQUEST_ID varchar(40),
        INTENT varchar(4095),
        SUGGESTED_VERB varchar(63),
        SUGGESTED_NOUN varchar(63),
        NOTES varchar(4095),
        REQUESTED_BY_USER_ID varchar(40),
        AGENT_RUN_ID varchar(40),
        CONVERSATION_ID varchar(40),
        REQUESTED_DATE datetime(3) DEFAULT NULL,
        STATUS_ID varchar(40),
        RESOLVED_BY_USER_ID varchar(40),
        RESOLVED_DATE datetime(3) DEFAULT NULL,
        RESOLUTION_NOTE varchar(4095),
        FULFILLED_TOOL_ID varchar(40),
        LAST_UPDATED_STAMP datetime(3) DEFAULT NULL,
        PRIMARY KEY (CAPABILITY_REQUEST_ID),
        KEY IDXAiCapabilityRequestStatusItem (STATUS_ID)
    );
