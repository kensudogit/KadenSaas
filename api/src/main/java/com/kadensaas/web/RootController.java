package com.kadensaas.web;

import java.net.URI;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * api のドメインを直接開いた人を、画面へ送る。
 *
 * <p>★ 目的は URL の取り違えを終わらせること。api と web が別ドメインなので、
 * api の方をブックマークしてしまうと毎回ここに来る。最初は素の 403 画面、
 * 次は JSON が出るだけで、どちらも「で、どこへ行けばいいのか」に答えていない。
 * 実際に 2 回続けて同じ行き違いになった。案内するだけでなく、連れて行く。
 *
 * <p>★ 画面の URL は {@code CORS_ORIGIN} から取る。ここに別の設定を新設しない。
 * CORS_ORIGIN は画面から api を呼ぶために必ず正しく設定されている値なので、
 * これがずれているなら、そもそもログインできていない。
 * 「転送先だけ古い」状態が構造的に作れないようにする。
 *
 * <p>★ 転送するのはブラウザだけ。{@code Accept} が HTML を求めていない
 * 呼び出し（curl、監視、API クライアント）には JSON を返す。
 * 機械に 302 を返すと、監視が「復旧した」と誤判定しうる。
 *
 * <p>★ JSON に出すのはサービス名と「画面ではない」ことだけ。バージョンや
 * ビルド情報は出さない。認証なしで見られる場所に、攻撃者が使える情報を置かない。
 */
@RestController
public class RootController {

    private final String uiOrigin;

    public RootController(@Value("${kaden.cors.origin}") String corsOrigin) {
        // ★ CORS_ORIGIN はカンマ区切りを許す。先頭を画面の正とみなす
        this.uiOrigin = corsOrigin == null || corsOrigin.isBlank()
            ? "" : corsOrigin.split(",")[0].trim();
    }

    @GetMapping("/")
    public ResponseEntity<?> root(
            @RequestHeader(value = HttpHeaders.ACCEPT, defaultValue = "") String accept) {

        boolean wantsHtml = accept.contains(MediaType.TEXT_HTML_VALUE);

        if (wantsHtml && !uiOrigin.isEmpty()) {
            // ★ 303 ではなく 302。GET を GET のまま転送すればよく、
            //   ブラウザの戻るボタンの挙動も素直になる
            return ResponseEntity.status(302)
                .location(URI.create(uiOrigin))
                .build();
        }

        return ResponseEntity.ok(Map.of(
            "service", "kaden-api",
            "ui", false,
            "message", "これは API です。画面は別のサービスにあります",
            "health", "/actuator/health"));
    }
}
