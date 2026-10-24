package com.kadensaas.service.callcenter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 問い合わせ文の意図分類。学習済みモデルではない。
 *
 * <p>★ キーワード照合だけ。実測していない精度を達成済みと言わない。
 */
final class IntentClassifier {

    static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put("billing", List.of("請求", "料金", "支払い", "領収", "引き落とし", "invoice", "bill"));
        KEYWORDS.put("shipping", List.of("配送", "発送", "届かない", "追跡", "遅延", "送料", "shipping"));
        KEYWORDS.put("account", List.of(
            "ログイン", "パスワード", "アカウント", "登録", "退会", "password", "login", "account"));
        KEYWORDS.put("complaint", List.of("苦情", "クレーム", "不満", "ひどい", "謝罪", "complaint"));
    }

    static final Set<String> KEYWORD_TOKENS = KEYWORDS.values().stream()
        .flatMap(List::stream)
        .collect(Collectors.toUnmodifiableSet());

    record Result(String intent, double score, String provider, String note) {
    }

    private IntentClassifier() {
    }

    static Result classify(String text) {
        String source = text == null ? "" : text;
        String best = "general";
        int bestScore = 0;
        for (var entry : KEYWORDS.entrySet()) {
            int score = 0;
            for (String word : entry.getValue()) {
                if (source.contains(word)) {
                    score++;
                }
            }
            if (score > bestScore) {
                best = entry.getKey();
                bestScore = score;
            }
        }
        return new Result(
            best,
            bestScore,
            "keyword",
            "keyword_nlp_not_a_trained_model");
    }
}
