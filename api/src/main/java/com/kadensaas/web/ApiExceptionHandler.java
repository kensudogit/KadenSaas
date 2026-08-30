package com.kadensaas.web;

import java.util.Map;

import com.kadensaas.service.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 例外を API のレスポンスに変換する。
 *
 * <p>★ 例外メッセージをそのまま返さない。SQL や内部の識別子が混ざると、
 * スキーマの形が外から見えてしまう。利用者に意味のある文言だけを返し、
 * 詳細はサーバー側のログに残す。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PhoneNumbers.InvalidNumberException.class)
    public ResponseEntity<Map<String, String>> invalidNumber(
            PhoneNumbers.InvalidNumberException e) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "invalid_phone_number", "message", e.getMessage()));
    }

    /**
     * 権限不足。
     *
     * <p>★ これを書かないと、下の {@code Exception} を受ける口が
     * {@code AccessDeniedException} まで飲み込み、権限不足が 500 になる。
     * 実際にそうなっていた（{@code @PreAuthorize} で弾かれる経路だけ 500、
     * SecurityConfig で弾かれる経路は 403、という分かりにくい形で）。
     *
     * <p>500 で返すと、呼ぶ側は「権限が無い」のか「サーバーが壊れている」のか
     * 区別できず、当番は障害として調査を始めることになる。
     * 権限の設計どおりに動いているときに、障害として扱われてはいけない。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("error", "forbidden",
                "message", "この操作を行う権限がありません"));
    }

    /**
     * 認証されていない。
     *
     * <p>★ 403 と分ける。401 は「ログインし直せば解決する」、
     * 403 は「別の人に頼め」で、利用者が取るべき行動が違う。
     */
    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<Map<String, String>> unauthenticated(
            AuthenticationCredentialsNotFoundException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "unauthenticated",
                "message", "ログインし直してください"));
    }

    /**
     * 呼び出し側の誤り（必須パラメータ不足・型違い・本文が読めない）。
     *
     * <p>★ これも書かないと、下の {@code Exception} を受ける口が拾って 500 になる。
     * パラメータの付け忘れはクライアント側の誤りであって、サーバーの障害ではない。
     * 500 を返すと「未処理の例外」としてログに error で残り、
     * 当番が障害として調査を始める。実際そうなっていた。
     */
    @ExceptionHandler({
        ServletRequestBindingException.class,
        TypeMismatchException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, String>> clientError(Exception e) {
        // ★ 例外メッセージをそのまま返さない。引数名や型名から内部構造が見える
        log.debug("リクエストの形式が不正です", e);
        return ResponseEntity.badRequest()
            .body(Map.of("error", "bad_request",
                "message", "リクエストの形式が正しくありません"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("未処理の例外", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "internal_error"));
    }
}
