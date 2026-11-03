package com.kadensaas.service;

/** 通話の会話モード。voice の TwiML 分岐と揃える。 */
public final class ConversationMode {

    public static final String OPERATOR = "operator";
    public static final String AI_AGENT = "ai_agent";
    public static final String IVR = "ivr";

    private ConversationMode() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPERATOR;
        }
        return switch (raw) {
            case OPERATOR, AI_AGENT, IVR -> raw;
            default -> throw new IllegalArgumentException("conversationMode が不正です: " + raw);
        };
    }
}
