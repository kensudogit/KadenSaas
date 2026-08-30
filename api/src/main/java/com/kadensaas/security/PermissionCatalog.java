package com.kadensaas.security;

import java.util.List;
import java.util.Set;

import com.kadensaas.security.AuthUser.Role;

/**
 * 役割ごとに何ができるかの一覧。管理画面の「権限」はここを表示する。
 *
 * <p>★ この一覧は説明用であって、実際に権限を決めているのはここではない。
 * 決めているのは {@code SecurityConfig} と各コントローラの
 * {@code @PreAuthorize}。つまりこの表は放っておけば必ず実態からずれ、
 * 「画面には書いてあるのに実際は違う」という、いちばん質の悪い嘘になる。
 *
 * <p>★ そうならないよう、各項目に実際の入口（{@link Capability#method} と
 * {@link Capability#path}）を持たせてある。{@code PermissionMatrixTest} が
 * 3 つの役割それぞれで実際にその入口を叩き、ここの宣言と一致するかを
 * 確かめる。ずれたらテストが落ちる。
 *
 * <p>★ したがって、権限を変えるときはこの表も直す必要がある。
 * 「直さないと気付かない」ではなく「直さないとビルドが通らない」形にしてある。
 */
public final class PermissionCatalog {

    private PermissionCatalog() {
    }

    /**
     * できることの 1 項目。
     *
     * @param key    画面と試験が参照する識別子
     * @param label  画面に出す名前
     * @param detail なぜこの制限なのか。表だけ見て納得できるように書く
     * @param roles  許可する役割
     * @param method 実際の入口の HTTP メソッド。試験が叩く
     * @param path   実際の入口のパス。試験が叩く
     * @param probeQuery 試験が付けるクエリ文字列（不要なら空）
     * @param probeBody  試験が送る本文（不要なら空）
     */
    public record Capability(String key, String label, String detail,
                             Set<Role> roles, String method, String path,
                             String probeQuery, String probeBody) {

        /**
         * ★ 必須パラメータのある入口には、必ず値を持たせること。
         *
         * <p>Spring MVC は引数の解決を先に行い、{@code @PreAuthorize} は
         * その後に走る。パラメータが足りないと、認可に到達する前に 400 で
         * 返ってしまい、「権限を検査したつもりで何も検査していない」状態になる。
         * 実際、最初はこれで dnc.remove の権限が検証できていなかった。
         */
        public Capability(String key, String label, String detail,
                          Set<Role> roles, String method, String path) {
            this(key, label, detail, roles, method, path, "", "");
        }

        public Capability(String key, String label, String detail,
                          Set<Role> roles, String method, String path, String probeQuery) {
            this(key, label, detail, roles, method, path, probeQuery, "");
        }

        public boolean allows(Role role) {
            return roles.contains(role);
        }

        /** 試験が実際に叩く URL。 */
        public String probeUrl() {
            return probeQuery.isEmpty() ? path : path + "?" + probeQuery;
        }
    }

    private static final Set<Role> EVERYONE =
        Set.of(Role.OPERATOR, Role.MANAGER, Role.ADMIN);
    private static final Set<Role> MANAGER_UP = Set.of(Role.MANAGER, Role.ADMIN);
    private static final Set<Role> ADMIN_ONLY = Set.of(Role.ADMIN);

    public static final List<Capability> ALL = List.of(

        new Capability("calls.dial", "架電する",
            "オペレーターの主業務。関門（DNC・時間帯・上限）は役割に関係なく適用される",
            // ★ 本文が無いと引数解決の段階で 400 になり、認可まで届かない。
            //   空の JSON を送って「電話番号が見つかりません」で返させる
            EVERYONE, "POST", "/api/v1/calls", "", "{}"),

        new Capability("queue.next", "架電キューから次の相手を取る",
            "同じ相手に 2 人が同時にかけないよう、取得時に予約する",
            EVERYONE, "POST", "/api/v1/queue/next",
            "campaignId=00000000-0000-0000-0000-000000000000"),

        new Capability("customers.read", "顧客リストを見る",
            "架電するには相手先が要るので、全員が読める",
            EVERYONE, "GET", "/api/v1/customers"),

        new Capability("history.read", "架電履歴を見る",
            "オペレーターは自分の通話だけ。manager 以上は全員分を見られる",
            EVERYONE, "GET", "/api/v1/call-history"),

        new Capability("dnc.register", "再勧誘拒否として登録する",
            "断られた相手を登録する。架電結果で DO_NOT_CALL を選んだ場合は"
                + "オペレーターでも自動的に登録される",
            // ★ 番号が無いと正規化で 400 になり、認可まで届かない。
            //   実在しない 03-1234-5xxx を使う（試験データは毎回消える）
            EVERYONE, "POST", "/api/v1/dnc", "",
            "{\"phone\":\"03-1234-5678\",\"reason\":\"権限の検査\"}"),

        new Capability("dnc.remove", "再勧誘拒否を解除する",
            "解除すると、断った相手にまた電話がかかる。取り消しは manager 以上に限る",
            // ★ phone は必須。無いと認可より先に 400 になり、検査にならない
            MANAGER_UP, "DELETE", "/api/v1/dnc", "phone=03-1234-5678"),

        new Capability("analytics.read", "分析を見る（時間帯・曜日・担当者）",
            "担当者別の成績は評価に直結するので、オペレーター同士では見えない",
            MANAGER_UP, "GET", "/api/v1/analytics/hourly"),

        new Capability("telephony.diagnose", "発信できない理由を調べる",
            "止まっている理由を知りたいのは設定を変える人だけではないので、"
                + "manager にも開けてある",
            MANAGER_UP, "GET", "/api/v1/admin/telephony/diagnose"),

        new Capability("telephony.settings", "発信者番号と録音の設定を変える",
            "他人名義の番号での発信や、録音の可否に関わるため管理者のみ",
            ADMIN_ONLY, "GET", "/api/v1/admin/telephony"),

        new Capability("telephony.killswitch", "発信を停止・再開する",
            "事故のときに使う。停止は即座に全オペレーターに効く",
            ADMIN_ONLY, "POST", "/api/v1/admin/telephony/dialing", "enabled=false"),

        new Capability("users.manage", "利用者の追加・役割変更・無効化",
            "役割を変えられるということは、自分の権限も増やせるということ。管理者のみ",
            ADMIN_ONLY, "GET", "/api/v1/admin/users"),

        new Capability("sampledata.manage", "サンプルデータの投入・削除",
            "本番データを消す操作を含むため管理者のみ",
            ADMIN_ONLY, "DELETE", "/api/v1/admin/sample-data")
    );
}
