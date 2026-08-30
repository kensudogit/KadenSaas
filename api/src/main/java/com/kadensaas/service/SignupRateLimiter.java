package com.kadensaas.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公開サインアップの回数制限。
 *
 * <p>★ 誰でも登録できるようにした以上、これは任意の追加機能ではなく必須。
 * 無いと、1 分間に数千のテナントを作られる。作られたテナントは発信できない
 * （発信者番号が未設定なので関門が止める）が、識別子は先に取られてしまうし、
 * DB も監査ログも膨らむ。
 *
 * <p>★ 数えるのは DB。アプリのメモリに持つと、インスタンスが 2 つに
 * 増えた瞬間に上限が 2 倍になる。しかも増えたことに誰も気付かない。
 *
 * <p>★ 失敗も数える。成功だけ数えると、識別子の重複で弾かれ続ける
 * 総当たり（どの slug が使われているかの調査）が素通りする。
 *
 * <p>★ IP は詐称できる。X-Forwarded-For を信じる以上、これは
 * 「本気の攻撃者を止める仕組み」ではなく「事故と雑な自動化を止める仕組み」。
 * 本気で守るなら CAPTCHA かメール確認が要る。そう書いておかないと、
 * これで守れているつもりになる。
 */
@Service
public class SignupRateLimiter {

    /** 制限に掛かったことを呼び出し側に伝える。 */
    public static class TooManyAttempts extends RuntimeException {
        private final int retryAfterSeconds;

        public TooManyAttempts(String message, int retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final JdbcTemplate jdbc;
    private final int windowMinutes;
    private final int maxPerIp;

    public SignupRateLimiter(
            JdbcTemplate jdbc,
            @Value("${kaden.signup.rate-limit.window-minutes:60}") int windowMinutes,
            @Value("${kaden.signup.rate-limit.max-per-ip:5}") int maxPerIp) {
        this.jdbc = jdbc;
        this.windowMinutes = windowMinutes;
        this.maxPerIp = maxPerIp;
    }

    /**
     * 制限を超えていれば例外。
     *
     * <p>★ IP が取れないときは通す。プロキシの設定次第で取れないことがあり、
     * そこで登録を止めると「一部の環境からだけ登録できない」という
     * 切り分けにくい壊れ方になる。制限は best effort と割り切る。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void check(String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        Integer recent = jdbc.queryForObject("""
            select count(*) from signup_attempts
             where ip = cast(? as inet)
               and created_at > now() - make_interval(mins => ?)
            """, Integer.class, ip, windowMinutes);

        if (recent != null && recent >= maxPerIp) {
            throw new TooManyAttempts(
                "登録の試行が多すぎます。しばらくおいてからお試しください",
                windowMinutes * 60);
        }
    }

    /**
     * 試行を記録する。
     *
     * <p>★ REQUIRES_NEW。登録本体が失敗して巻き戻っても、試行の記録は残す。
     * 同じトランザクションに載せると、失敗した試行が 1 件も残らず、
     * 失敗を繰り返す総当たりが無制限になる。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String ip, String slug, boolean succeeded, String reason) {
        jdbc.update("""
            insert into signup_attempts (ip, slug, succeeded, reason)
            values (cast(? as inet), ?, ?, ?)
            """, ip, slug, succeeded, reason);
    }

    /**
     * 古い記録を消す。
     *
     * <p>★ IP は個人情報になりうる。制限に使う期間を過ぎたら残さない。
     * 「消す仕組み」を後から足すと、それまでに溜まった分が残り続ける。
     */
    @Transactional
    public int purgeOlderThanDays(int days) {
        return jdbc.update(
            "delete from signup_attempts where created_at < now() - make_interval(days => ?)",
            days);
    }
}
