package com.kadensaas.service.callcenter;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 語彙 RAG 用のトークン。
 *
 * <p>日本語は文全体が 1 トークンになると交差しない。
 * かな 2 文字（ます／です）はノイズなので、漢字 2 文字と 3 文字部分列だけ使う。
 */
final class LexicalTokens {

    private static final Pattern ASCII = Pattern.compile("[A-Za-z0-9]+");
    private static final Pattern JA = Pattern.compile("[\\u4e00-\\u9faf\\u3040-\\u309f\\u30a0-\\u30ffー]+");
    private static final Pattern KANJI2 = Pattern.compile("[\\u4e00-\\u9faf]{2}");

    private LexicalTokens() {
    }

    static Set<String> of(String value) {
        Set<String> tokens = new HashSet<>();
        String text = value == null ? "" : value;
        Matcher ascii = ASCII.matcher(text);
        while (ascii.find()) {
            String part = ascii.group();
            if (part.length() >= 2) {
                tokens.add(part.toLowerCase());
            }
        }
        Matcher ja = JA.matcher(text);
        while (ja.find()) {
            String run = ja.group();
            if (run.length() >= 3) {
                tokens.add(run);
            }
            for (int i = 0; i < run.length() - 1; i++) {
                String gram2 = run.substring(i, i + 2);
                if (KANJI2.matcher(gram2).matches()) {
                    tokens.add(gram2);
                }
            }
            for (int i = 0; i < run.length() - 2; i++) {
                tokens.add(run.substring(i, i + 3));
            }
        }
        for (String word : IntentClassifier.KEYWORD_TOKENS) {
            if (!word.isEmpty() && text.contains(word)) {
                tokens.add(word.codePoints().allMatch(c -> c < 128) ? word.toLowerCase() : word);
            }
        }
        return tokens;
    }
}
