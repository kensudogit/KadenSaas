package com.kadensaas.web;

import java.util.Map;

import com.kadensaas.service.SignupRateLimiter;
import com.kadensaas.service.TenantSignupService;
import jakarta.servlet.http.HttpServletRequest;
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
 * 誰でも登録できる設定にした場合、誰でも架電の基盤を持てるということでもある。
 * だから「登録できること」と「発信できること」を必ず分けてある。
 *
 * <p><b>★ 登録しただけでは 1 本も発信できない。</b>
 * 発信には {@code tenant_telephony}（発信者番号）が要り、登録処理はそれを作らない。
 * 発信者番号は購入・検証が必要で、自己申告で登録させてよいものではない。
 * 結果として、登録直後のテナントは関門に {@code telephony_not_configured} で
 * 止められる。これが公開サインアップの安全性の主軸であり、
 * {@code OpenSignupTest} が実際に発信を試して固定している。
 *
 * <p>★ 動作は {@code KADEN_SIGNUP_MODE} で切り替える。
 * <ul>
 *   <li>{@code open}（既定）— 誰でも登録できる</li>
 *   <li>{@code token} — {@code X-Signup-Token} が {@code KADEN_SIGNUP_TOKEN} と
 *       一致する場合だけ登録できる</li>
 *   <li>{@code disabled} — 404 を返し、機能ごと存在しないものとして扱う</li>
 * </ul>
 *
 * <p>★ 無効なときに 403 ではなく 404 を返す。403 だと「この URL は存在する」
 * ことが分かり、有効化を待って総当たりされる余地を残す。
 *
 * <p>★ 回数制限を必ず通す。無いと 1 分間に数千のテナントを作られる。
 * ただし IP は詐称できるので、これは事故と雑な自動化を止めるためのもの。
 * 本気で守るなら CAPTCHA かメール確認が要る（未実装）。
 */
@RestController
@RequestMapping("/api/v1/signup")
public class SignupController {

    private static final Logger log = LoggerFactory.getLogger(SignupController.class);

    private static final String MODE_OPEN = "open";
    private static final String MODE_TOKEN = "token";
    private static final String MODE_DISABLED = "disabled";

    public record SignupRequest(
        @NotBlank String tenantName,
        @NotBlank String slug,
        @NotBlank String email,
        @NotBlank String password,
        String displayName) {
    }

    private final TenantSignupService signup;
    private final SignupRateLimiter rateLimiter;
    private final String mode;
    private final String signupToken;

    public SignupController(TenantSignupService signup,
                            SignupRateLimiter rateLimiter,
                            @Value("${kaden.signup.mode:open}") String mode,
                            @Value("${kaden.signup.token:}") String signupToken) {
        this.signup = signup;
        this.rateLimiter = rateLimiter;
        this.signupToken = signupToken == null ? "" : signupToken.trim();

        String m = mode == null ? "" : mode.trim().toLowerCase();
        if (!m.equals(MODE_OPEN) && !m.equals(MODE_TOKEN) && !m.equals(MODE_DISABLED)) {
            throw new IllegalArgumentException(
                "KADEN_SIGNUP_MODE は open / token / disabled のいずれかです: " + mode);
        }
        // ★ token を指定しながらトークンが空なら、誰でも通ってしまう。
        //   「有効にしたがトークンを付け忘れて全開だった」を起動時に落とす
        if (m.equals(MODE_TOKEN) && this.signupToken.isEmpty()) {
            throw new IllegalArgumentException(
                "KADEN_SIGNUP_MODE=token ですが KADEN_SIGNUP_TOKEN が空です。"
                + "この状態では誰でも登録できてしまいます");
        }
        this.mode = m;
    }

    @GetMapping("/available")
    public ResponseEntity<?> available() {
        // ★ 画面が出し分けるための問い合わせ。トークンの値は返さない
        return ResponseEntity.ok(Map.of(
            "enabled", !mode.equals(MODE_DISABLED),
            "requiresToken", mode.equals(MODE_TOKEN)));
    }

    @PostMapping
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest req,
                                    @RequestHeader(value = "X-Signup-Token",
                                                   required = false) String token,
                                    HttpServletRequest http) {
        if (mode.equals(MODE_DISABLED)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "not_found"));
        }

        String ip = clientIp(http);

        if (mode.equals(MODE_TOKEN)) {
            // ★ 長さの違いで早期に抜けない比較。タイミングから桁数を推測されないため
            if (token == null || !constantTimeEquals(signupToken, token)) {
                log.warn("テナント登録: トークンが一致しませんでした（slug={}）", req.slug());
                rateLimiter.record(ip, req.slug(), false, "invalid_token");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_signup_token",
                                 "message", "登録用トークンが正しくありません"));
            }
        }

        try {
            rateLimiter.check(ip);
        } catch (SignupRateLimiter.TooManyAttempts e) {
            // ★ ここでは記録しない。制限に掛かった分まで記録すると、
            //   叩き続けるだけで表が膨らむ
            log.warn("テナント登録: 回数制限に掛かりました（slug={}）", req.slug());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.retryAfterSeconds()))
                .body(Map.of("error", "too_many_attempts", "message", e.getMessage()));
        }

        try {
            var result = signup.signup(
                req.tenantName(), req.slug(), req.email(),
                req.password(), req.displayName());

            rateLimiter.record(ip, result.slug(), true, null);

            // ★ 個人情報は残さない。ここは認証前なので user_id が無い。slug だけ
            log.info("テナントを登録しました: slug={} tenantId={}",
                result.slug(), result.tenantId());

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "tenantId", result.tenantId(),
                "slug", result.slug(),
                "userId", result.userId(),
                "email", result.email(),
                "message", "登録しました。この識別子とメールアドレスでログインできます。"
                    + "発信するには、管理画面で発信者番号の設定が別途必要です"));

        } catch (TenantSignupService.SignupException e) {
            rateLimiter.record(ip, req.slug(), false, e.code());
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.code(), "message", e.getMessage()));
        }
    }

    /**
     * 接続元。
     *
     * <p>★ X-Forwarded-For の先頭を採る。ロードバランサの背後では
     * remoteAddr が常に内部アドレスになり、制限の意味が無くなる。
     * なりすませる値なので、認可の判断には使わない（回数制限だけ）。
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }
}
