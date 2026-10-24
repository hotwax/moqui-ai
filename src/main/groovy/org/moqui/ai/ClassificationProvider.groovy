package org.moqui.ai

/** Single bounded classification request; deliberately separate from agent/chat capabilities. */
interface ClassificationProvider {
    Map classify(Map request)
}
