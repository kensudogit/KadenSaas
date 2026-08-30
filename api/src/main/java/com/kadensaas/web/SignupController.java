package com.kadensaas.web;

import java.util.Map;

import com.kadensaas.service.TenantSignupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * テナントの新規登録。
 *
 * <p>★ これは認証前に呼ばれる書き込み API で、この系で唯一そういう口になる。
 * 電話をかけられるシステムなので、誰でもテナントを作れる状態にはしない。
 *
 * <p>{@code KADEN_SIGNUP_TOKEN} が未設定なら <b>エンドポイントごと無効</b>
 * （404 を返す）。設定されていれば {@code X-Signup-Token} ヘッダの一致を要求する。
 * 変数 1 つで「有効化」と「保護」の両方を兼ねる形にしてあるのは、
 * 「有効にしたがトークンを付け忘れて全開だった」という状態を作らないため。
 *
 * <p>★ 無効なときに 403 ではなく 404 を返す。403 だと「この URL は存在する」
 * ことが分かり、有効化を待って総当たりされる余地を残す。
 *
 * <p>★ 登録が成功しても電話はかけられない。発信には
 * {@code tenant_telephony}（発信者番号）の設定が要る。
 * 番号は購入・検証が必要で、自己申告で登録させてよいものではないため、
 * ここでは作らない。
 */
@RestController
@RequestMapping("/api/v1/signup")
public class SignupController {

    private static final Logger log = LoggerFactory.getLogger(SignupController.class);

    public record SignupRequest(
        @NotBlank String tenantName,
        @NotBlank String slug,
        @NotBlank String email,
        @NotBlank String password,
        String displayName) {
    }

    private final TenantSignupService signup;
    private final String signupToken;

    public SignupController(TenantSignupService signup,
                            @Value("${kaden.signup.token:}") String signupToken) {
        this.signup = signup;
        this.signupToken = signupToken == null ? "" : signupToken.trim();
    }

    /** トークンが設定されていなければ、この機能は存在しないものとして扱う。 */
    private boolean disabled() {
        return signupToken.isEmpty();
    }

    @GetMapping("/available")
    public ResponseEntity<?> available() {
        // ★ 画面が「登録できるかどうか」を判断するための問い合わせ。
        //   トークンの有無だけを返し、値は返さない
        return ResponseEntity.ok(Map.of("enabled", !disabled()));
    }

    @PostMapping
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest req,
                                    @RequestHeader(value = "X-Signup-Token",
                                                   required = false) String token) {
        if (disabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "not_found"));
        }

        // ★ 長さの違いで早期に抜けない比較。タイミングから桁数を推測されないため
        if (token == null || !constantTimeEquals(signupToken, token)) {
            log.warn("テナント登録: トークンが一致しませんでした（slug={}）", req.slug());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "invalid_signup_token",
                             "message", "登録用トークンが正しくありません"));
        }

        try {
            var result = signup.signup(
                req.tenantName(), req.slug(), req.email(),
                req.password(), req.displayName());

            // ★ 個人情報は残さない。誰がいつ作ったかは監査で必要だが、
            //   ここは認証前なので user_id が無い。slug だけを記録する
            log.info("テナントを登録しました: slug={} tenantId={}",
                result.slug(), result.tenantId());

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "tenantId", result.tenantId(),
                "slug", result.slug(),
                "userId", result.userId(),
                "email", result.email(),
                "message", "登録しました。この識別子とメールアドレスでログインできます"));

        } catch (TenantSignupService.SignupException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.code(), "message", e.getMessage()));
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }
}
