package com.kadensaas.web;

import java.util.Map;

import com.kadensaas.service.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("未処理の例外", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "internal_error"));
    }
}
