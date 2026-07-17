package com.chtholly.llm.rag;

/**
 * Immutable reader-and-assistant turn supplied to post-scoped RAG questions.
 *
 * @param question reader question
 * @param answer assistant answer
 */
public record RagConversationTurn(String question, String answer) {
}
