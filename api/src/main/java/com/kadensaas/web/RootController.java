package com.kadensaas.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ここが何であるかだけを返す。
 *
 * <p>★ 目的は「URL を間違えた人に、間違えたと分かってもらう」こと。
 * これが無いと、api のドメインをブラウザで開いたときに素の 403 画面になり、
 * 「サーバーが壊れているのか、URL が違うのか」が区別できない。
 * 実際にそれで問い合わせになった。
 *
 * <p>★ 返すのはサービス名と「画面ではない」ことだけ。
 * バージョン・ビルド番号・依存関係は出さない。認証なしで見られる場所に、
 * 攻撃者が使える情報を置かない。
 *
 * <p>★ 画面の URL も書かない。ここに書くと、環境ごとに変わる値を
 * ソースに持つことになり、必ずどこかで古くなる。
 */
@RestController
public class RootController {

    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
            "service", "kaden-api",
            "ui", false,
            "message", "これは API です。画面は別のサービスにあります",
            "health", "/actuator/health");
    }
}
