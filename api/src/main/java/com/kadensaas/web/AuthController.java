package com.kadensaas.web;

import java.util.Map;

import com.kadensaas.domain.Tenant;
import com.kadensaas.domain.UserAccount;
import com.kadensaas.security.AuthUser;
import com.kadensaas.security.JwtService;
import com.kadensaas.service.AuthService;
import com.kadensaas.tenant.TenantContext;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * ログイン。
 *
 * <p>★ このメソッドに {@code @Transactional} を付けてはいけない。
 * テナントは {@code SET LOCAL app.tenant_id} でトランザクション開始時に
 * 注入されるので、ここでトランザクションを開くと、テナント未設定のまま
 * users を引くことになり RLS が 0 行を返す。パスワードが正しくても
 * 必ず「認証失敗」になり、原因が RLS だと気付くまで時間を溶かす。
 *
 * <p>問い合わせは {@link AuthService} の 2 つのメソッドに分け、
 * その間で {@code TenantContext} を設定する。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /**
     * ★ tenantSlug を必須にしている。email だけで所属テナントを引こうとすると、
     * RLS を迂回する検索が要る。その迂回は必ず後で別の用途に流用され、
     * テナント分離の穴になる。ログイン時にどのテナントかを申告させれば、
     * 迂回経路そのものを作らずに済む。
     */
    public record LoginRequest(@NotBlank String tenantSlug,
                               @NotBlank String email,
                               @NotBlank String password) {
    }

    private final AuthService auth;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(AuthService auth, PasswordEncoder encoder, JwtService jwt) {
        this.auth = auth;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {

        // 1. テナントを特定する（tenants に RLS は無いので未設定でも引ける）
        Tenant tenant = auth.findTenantBySlug(req.tenantSlug()).orElse(null);

        // ★ 「テナントが無い」と「パスワードが違う」を区別して返さない。
        //   区別すると、テナント名と登録済みアドレスを総当たりで調べられる
        if (tenant == null) {
            return unauthorized();
        }

        // 2. テナントを文脈に置く。★ この後に開くトランザクションから RLS が効く
        TenantContext.set(tenant.getId());
        try {
            UserAccount user = auth.findActiveUser(tenant.getId(), req.email()).orElse(null);
            if (user == null || !encoder.matches(req.password(), user.getPasswordHash())) {
                return unauthorized();
            }

            AuthUser principal = new AuthUser(
                user.getId(), tenant.getId(), user.getEmail(),
                AuthUser.Role.of(user.getRole()));

            // 最終ログイン時刻。失敗してもログインは通す
            try {
                auth.touchLastSeen(user.getId());
            } catch (RuntimeException ignored) {
                // 記録できないことでログインを止める理由は無い
            }

            return ResponseEntity.ok(Map.of(
                "token", jwt.issue(principal),
                "user", Map.of(
                    "id", user.getId(),
                    "displayName", user.getDisplayName(),
                    "role", user.getRole()),
                "tenant", Map.of(
                    "id", tenant.getId(),
                    "name", tenant.getName(),
                    "timezone", tenant.getTimezone())));
        } finally {
            // ★ 認証フィルタの外でセットしたので、ここで必ず片付ける。
            //   スレッドはプールで使い回されるため、残すと次のリクエストが
            //   このテナントとして動く
            TenantContext.clear();
        }
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        AuthUser user = CurrentUser.require();
        return Map.of(
            "id", user.userId(),
            "email", user.email(),
            "role", user.role().name(),
            "tenantId", user.tenantId());
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "invalid_credentials",
                         "message", "テナント・メールアドレス・パスワードのいずれかが違います"));
    }
}
