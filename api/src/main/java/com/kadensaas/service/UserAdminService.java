package com.kadensaas.service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kadensaas.security.AuthUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * テナント内のユーザー管理。
 *
 * <p>★ 初期パスワードは生成する。管理者に考えさせると弱い値が使われ、
 * しかも複数人で使い回される。生成した平文は保存せず、応答で一度だけ返す。
 *
 * <p>★ 最後の管理者を消せない・降格できないようにする。できてしまうと、
 * 設定を変えられる人が誰もいないテナントが出来上がり、
 * 復旧に DB を直接触ることになる。
 *
 * <p>★ 削除ではなく無効化。通話履歴と監査ログが担当者を参照しているので、
 * 行を消すと「誰がかけたか分からない通話」が生まれる。
 */
@Service
public class UserAdminService {

    /** 生成する初期パスワードの文字種。紛らわしい文字（0/O/1/l/I）を除く。 */
    private static final String ALPHABET =
        "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int GENERATED_LENGTH = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final List<String> ROLES = List.of("operator", "manager", "admin");

    /** 発行結果。initialPassword はここでしか返らない。 */
    public record Created(UUID userId, String email, String role, String initialPassword) {
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserAdminService(JdbcTemplate jdbc, PasswordEncoder encoder, AuditService audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        // ★ password_hash は絶対に返さない列に入れない。
        //   select * にしておくと、いつか API 経由で出ていく
        return jdbc.queryForList("""
            select u.id, u.email, u.display_name, u.role, u.status,
                   u.last_seen_at, u.created_at, u.password_change_required,
                   (select count(*) from call_sessions cs
                     where cs.operator_id = u.id) as call_count
              from users u
             order by
               case u.role when 'admin' then 0 when 'manager' then 1 else 2 end,
               u.display_name
            """);
    }

    /**
     * ユーザーを追加する。
     *
     * <p>★ 初期パスワードを生成して一度だけ返す。応答を受け取った管理者が
     * 本人に安全な経路で渡す前提。ログにも監査にも平文は残さない。
     */
    @Transactional
    public Created create(AuthUser actor, String email, String displayName, String role) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase();
        if (!normalizedEmail.contains("@") || normalizedEmail.length() < 5) {
            throw new IllegalArgumentException("メールアドレスの形式が正しくありません");
        }
        String normalizedRole = normalizeRole(role);
        String name = displayName == null || displayName.isBlank()
            ? normalizedEmail.substring(0, normalizedEmail.indexOf('@'))
            : displayName.trim();

        String initial = generatePassword();

        UUID id;
        try {
            id = jdbc.queryForObject("""
                insert into users (tenant_id, email, password_hash, display_name,
                                   role, status, password_change_required)
                values (?, ?, ?, ?, ?, 'active', true)
                returning id
                """, UUID.class,
                actor.tenantId(), normalizedEmail, encoder.encode(initial),
                name, normalizedRole);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException(
                "このメールアドレスはすでに登録されています: " + normalizedEmail);
        }

        // ★ 監査に残すのは役割だけ。初期パスワードはもちろん、
        //   メールアドレスも入れない（Json のクラスコメントの約束どおり、
        //   長期保存される監査ログに生の個人情報を溜めない）。
        //   誰を作ったかは entity_id から users を辿れば分かる
        audit.record(actor, "user.created", "user", id,
            Json.of("role", normalizedRole));

        return new Created(id, normalizedEmail, normalizedRole, initial);
    }

    /**
     * 役割を変更する。
     *
     * <p>★ 最後の管理者を降格させない。できてしまうと、設定を変えられる人が
     * いないテナントが出来上がる。
     */
    @Transactional
    public void changeRole(AuthUser actor, UUID userId, String role) {
        String normalizedRole = normalizeRole(role);
        String current = currentRole(userId);

        if ("admin".equals(current) && !"admin".equals(normalizedRole)) {
            requireAnotherAdmin(userId, "最後の管理者は降格できません");
        }

        jdbc.update("update users set role = ?, updated_at = now() where id = ?",
            normalizedRole, userId);

        audit.record(actor, "user.role_changed", "user", userId,
            Json.of("from", current, "to", normalizedRole));
    }

    /**
     * 有効・無効を切り替える。
     *
     * <p>★ 行を消さない。通話履歴と監査ログが担当者を参照しているので、
     * 消すと「誰がかけたか分からない通話」が残る。
     */
    @Transactional
    public void setStatus(AuthUser actor, UUID userId, boolean active) {
        if (!active) {
            if (userId.equals(actor.userId())) {
                // ★ 自分を無効化すると、その場でログインできなくなる
                throw new IllegalArgumentException("自分自身は無効化できません");
            }
            if ("admin".equals(currentRole(userId))) {
                requireAnotherAdmin(userId, "最後の管理者は無効化できません");
            }
        }

        jdbc.update("update users set status = ?, updated_at = now() where id = ?",
            active ? "active" : "disabled", userId);

        audit.record(actor, active ? "user.enabled" : "user.disabled", "user", userId);
    }

    /**
     * 初期パスワードを再発行する。
     *
     * <p>★ 「パスワードを忘れた」への対応。管理者が値を考えるのではなく、
     * ここでも生成する。再び password_change_required を立てる。
     */
    @Transactional
    public String resetPassword(AuthUser actor, UUID userId) {
        String initial = generatePassword();
        int updated = jdbc.update("""
            update users set password_hash = ?, password_change_required = true,
                             updated_at = now()
             where id = ?
            """, encoder.encode(initial), userId);

        if (updated == 0) {
            throw new IllegalArgumentException("利用者が見つかりません");
        }
        audit.record(actor, "user.password_reset", "user", userId);
        return initial;
    }

    /**
     * 本人によるパスワード変更。
     *
     * <p>★ 現在のパスワードを必ず照合する。照合しないと、席を離れた隙に
     * 端末を触った誰かが、そのままパスワードを差し替えて本人を締め出せる。
     */
    @Transactional
    public void changeOwnPassword(AuthUser actor, String currentPassword, String newPassword) {
        String hash = jdbc.queryForObject(
            "select password_hash from users where id = ?", String.class, actor.userId());

        if (hash == null || !encoder.matches(currentPassword, hash)) {
            throw new IllegalArgumentException("現在のパスワードが違います");
        }
        validateStrength(newPassword);
        if (encoder.matches(newPassword, hash)) {
            throw new IllegalArgumentException("現在と同じパスワードは設定できません");
        }

        jdbc.update("""
            update users set password_hash = ?, password_change_required = false,
                             updated_at = now()
             where id = ?
            """, encoder.encode(newPassword), actor.userId());

        audit.record(actor, "user.password_changed", "user", actor.userId());
    }

    // ------------------------------------------------------------ 補助

    private String currentRole(UUID userId) {
        String role = jdbc.query("select role from users where id = ?",
            rs -> rs.next() ? rs.getString(1) : null, userId);
        if (role == null) {
            // ★ RLS により他テナントの利用者は 0 行になる。同じ文言で返す
            throw new IllegalArgumentException("利用者が見つかりません");
        }
        return role;
    }

    private void requireAnotherAdmin(UUID excludingUserId, String message) {
        Integer others = jdbc.queryForObject("""
            select count(*) from users
             where role = 'admin' and status = 'active' and id <> ?
            """, Integer.class, excludingUserId);
        if (others == null || others == 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private String normalizeRole(String role) {
        String r = role == null ? "" : role.trim().toLowerCase();
        if (!ROLES.contains(r)) {
            throw new IllegalArgumentException(
                "役割は operator / manager / admin のいずれかです");
        }
        return r;
    }

    private void validateStrength(String password) {
        // ★ TenantSignupService と同じ基準（12 文字以上）にそろえる。
        //   片方だけ緩いと、緩いほうの経路から弱いパスワードが入り、
        //   厳しくしたはずの検査が意味を失う
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException("パスワードは 12 文字以上にしてください");
        }
    }

    private String generatePassword() {
        StringBuilder sb = new StringBuilder(GENERATED_LENGTH);
        for (int i = 0; i < GENERATED_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
