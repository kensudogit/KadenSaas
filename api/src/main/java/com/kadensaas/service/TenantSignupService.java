package com.kadensaas.service;

import java.util.Optional;
import java.util.UUID;

import com.kadensaas.domain.Tenant;
import com.kadensaas.domain.UserAccount;
import com.kadensaas.repository.TenantRepository;
import com.kadensaas.repository.UserAccountRepository;
import com.kadensaas.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * テナントの新規登録。
 *
 * <p>★ ここは「テナントがまだ存在しない状態で業務データを書く」唯一の場所で、
 * この設計のいちばん難しい箇所になる。
 *
 * <p>テナントは {@code SET LOCAL app.tenant_id} でトランザクション開始時に
 * 注入される。ところが登録の瞬間はそのテナント自身がまだ無い。
 * 1 つの {@code @Transactional} メソッドで tenants と users を両方書こうとすると、
 * users への insert が「テナント未設定」のトランザクションで走り、
 * RLS の {@code with check} に弾かれる。
 *
 * <p>そこで 2 つのトランザクションに分ける。
 * <ol>
 *   <li>tenants に insert（このテーブルには RLS が無いので context 不要）</li>
 *   <li>{@code TenantContext.set(新しい id)} を挟む</li>
 *   <li>users に insert（新しいトランザクション。ここで初めて RLS が効く）</li>
 * </ol>
 *
 * <p>★ 別クラスに分けず同じクラス内のメソッドを呼ぶと Spring のプロキシを
 * 通らず、新しいトランザクションが始まらない。だから
 * {@link TenantProvisioning} を別 Bean にしてある。
 *
 * <p>★ 途中で失敗すると「テナントだけあって利用者がいない」状態が残る。
 * ログインできないテナントは実質使えないので、その場合は tenants を消して
 * 巻き戻す。完全な原子性は 2 トランザクションでは得られないが、
 * 「入れない箱」が残り続けるよりはよい。
 */
@Service
public class TenantSignupService {

    /** 登録の結果。呼び出し側はそのまま返してよい。 */
    public record Result(UUID tenantId, String slug, UUID userId, String email) {
    }

    /** 利用者に見せてよい失敗理由。 */
    public static class SignupException extends RuntimeException {
        private final String code;

        public SignupException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(TenantSignupService.class);

    private final TenantRepository tenants;
    private final TenantProvisioning provisioning;
    private final PasswordEncoder encoder;

    public TenantSignupService(TenantRepository tenants,
                               TenantProvisioning provisioning,
                               PasswordEncoder encoder) {
        this.tenants = tenants;
        this.provisioning = provisioning;
        this.encoder = encoder;
    }

    public Result signup(String tenantName, String slug, String email,
                         String password, String displayName) {

        String normalizedSlug = slug == null ? "" : slug.trim().toLowerCase();
        String normalizedEmail = email == null ? "" : email.trim();

        validate(tenantName, normalizedSlug, normalizedEmail, password);

        // ★ 先に重複を見て分かりやすいエラーを返す。ただしこれは競合に弱いので、
        //   本当の防御は tenants_slug_uniq（部分ユニーク索引）が担う
        if (provisioning.slugExists(normalizedSlug)) {
            throw new SignupException("slug_taken",
                "この識別子はすでに使われています: " + normalizedSlug);
        }

        UUID tenantId;
        try {
            tenantId = provisioning.createTenant(tenantName.trim(), normalizedSlug);
        } catch (DataIntegrityViolationException e) {
            // ★ 整合性違反を一律「識別子の重複」と決めつけない。
            //   最初はそう書いていたが、実際には not-null 制約違反が起きており、
            //   利用者にも開発者にも嘘の理由が出て原因の特定が遅れた。
            //   重複だと確認できたときだけそう言い、それ以外は隠さず記録する。
            if (isSlugConflict(e)) {
                throw new SignupException("slug_taken",
                    "この識別子はすでに使われています: " + normalizedSlug);
            }
            log.error("テナントの作成に失敗しました（slug={}）", normalizedSlug, e);
            throw new SignupException("signup_failed",
                "登録に失敗しました。時間をおいて再度お試しください");
        }

        // ★ ここから先は新しいテナントとして振る舞う
        TenantContext.set(tenantId);
        try {
            UUID userId = provisioning.createAdminUser(
                tenantId, normalizedEmail, encoder.encode(password),
                displayName == null || displayName.isBlank() ? "管理者" : displayName.trim());

            return new Result(tenantId, normalizedSlug, userId, normalizedEmail);

        } catch (RuntimeException e) {
            // ★ 利用者を作れなかったテナントは、誰もログインできない箱になる。
            //   残すと slug だけが占有され、作り直しもできない。消して巻き戻す
            try {
                provisioning.deleteTenant(tenantId);
            } catch (RuntimeException ignored) {
                // 巻き戻しに失敗しても、元の例外を優先して伝える
            }
            throw new SignupException("signup_failed",
                "登録に失敗しました。時間をおいて再度お試しください");
        } finally {
            // ★ 認証フィルタの外で設定したので必ず片付ける。
            //   スレッドはプールで使い回されるため、残すと次のリクエストが
            //   このテナントとして動く
            TenantContext.clear();
        }
    }

    /**
     * 入力の検証。
     *
     * <p>★ slug は DB の check 制約と同じ形をここでも見る。DB に任せると
     * 制約違反の生メッセージが利用者に出てしまい、何を直せばよいか伝わらない。
     */
    private void validate(String tenantName, String slug, String email, String password) {
        if (tenantName == null || tenantName.isBlank()) {
            throw new SignupException("invalid_name", "組織名を入力してください");
        }
        if (!slug.matches("^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$")) {
            throw new SignupException("invalid_slug",
                "識別子は英小文字・数字・ハイフンで 3〜40 文字にしてください"
                + "（先頭と末尾はハイフン以外）");
        }
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new SignupException("invalid_email",
                "メールアドレスの形式が正しくありません");
        }
        // ★ 通話録音と顧客の個人情報を扱う管理者アカウントなので、
        //   短いパスワードを受け付けない
        if (password == null || password.length() < 12) {
            throw new SignupException("weak_password",
                "パスワードは 12 文字以上にしてください");
        }
    }

    /**
     * 例外が slug の重複によるものか。
     *
     * <p>★ 制約名で判定する。メッセージの文言に頼ると DB のバージョンや
     * ロケールで変わる。tenants_slug_uniq は V1 で定義した索引名。
     */
    private static boolean isSlugConflict(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains("tenants_slug_uniq");
    }

    public Optional<Tenant> findBySlug(String slug) {
        return tenants.findActiveBySlug(slug);
    }

    /**
     * トランザクション境界を分けるための内部 Bean。
     *
     * <p>★ 別 Bean である必要がある。同じクラス内のメソッド呼び出しは
     * Spring のプロキシを通らず、{@code REQUIRES_NEW} が効かない。
     */
    @Service
    public static class TenantProvisioning {

        private final TenantRepository tenants;
        private final UserAccountRepository users;

        public TenantProvisioning(TenantRepository tenants, UserAccountRepository users) {
            this.tenants = tenants;
            this.users = users;
        }

        /** tenants には RLS が無いので、テナント未設定でも書ける。 */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public UUID createTenant(String name, String slug) {
            Tenant t = new Tenant();
            t.setName(name);
            t.setSlug(slug);

            // ★ 必須列をすべて明示的に埋める。DB 側の default に任せてはいけない。
            //   JPA は null のフィールドも INSERT 文に含めるので、
            //   default ではなく NULL が入ろうとして not-null 制約に弾かれる。
            //   実際にこれで登録が失敗し、しかも下の catch が
            //   DataIntegrityViolationException を一律「識別子の重複」と
            //   報告していたため、原因の見当がつかなかった。
            t.setTimezone("Asia/Tokyo");
            t.setStatus("active");
            // 架電可能時間は写像していないので DB の default（09:00-20:00）が入る。
            // 理由は Tenant の当該箇所を参照
            // 月〜金（ISO で 1=月曜）
            t.setCallingWeekdays(new int[] {1, 2, 3, 4, 5});
            t.setExcludeHolidays(true);
            t.setMaxAttemptsPerDay(3);
            t.setMaxAttemptsTotal(8);
            t.setRecordingRetentionDays(365);

            tenants.saveAndFlush(t);
            return t.getId();
        }

        @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
        public boolean slugExists(String slug) {
            return tenants.findActiveBySlug(slug).isPresent();
        }

        /**
         * ★ 呼ぶ前に {@code TenantContext.set(tenantId)} を済ませておくこと。
         * REQUIRES_NEW なので、ここで新しいトランザクションが開き、
         * その開始時に app.tenant_id が注入される。
         */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public UUID createAdminUser(UUID tenantId, String email,
                                    String passwordHash, String displayName) {
            UserAccount u = new UserAccount();
            u.setTenantId(tenantId);
            u.setEmail(email);
            u.setPasswordHash(passwordHash);
            u.setDisplayName(displayName);
            // ★ 最初の利用者は admin。テナント設定と請求を触れる必要がある
            u.setRole("admin");
            u.setStatus("active");
            users.saveAndFlush(u);
            return u.getId();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void deleteTenant(UUID tenantId) {
            tenants.deleteById(tenantId);
        }
    }
}
