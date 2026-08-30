package com.kadensaas.web;

import java.util.Map;

import com.kadensaas.service.SampleDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 管理者用の操作。
 *
 * <p>★ {@code SecurityConfig} で {@code /api/v1/admin/**} は admin 限定に
 * してあるが、ここでも {@code @PreAuthorize} を書いている。二重に見えるが、
 * パス単位の設定はルーティングを変えたときに外れる。実際に権限が要る
 * メソッドの側にも書いておくと、片方が外れても守られる。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final SampleDataService sampleData;

    public AdminController(SampleDataService sampleData) {
        this.sampleData = sampleData;
    }

    /**
     * サンプルデータの投入。
     *
     * <p>★ 実在しない電話番号（03-1234-5xxx）を使っている。デモデータから
     * 本当に発信してしまう事故を防ぐため。それでも本番テナントに入れると
     * 実データと混ざるので、既にデータがあるときは {@code force} を要求する。
     */
    @PostMapping("/sample-data")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> generate(
            @RequestParam(defaultValue = "false") boolean force) {

        var user = CurrentUser.require();

        if (!force && sampleData.hasData()) {
            // ★ 黙って足さない。実データのあるテナントに混ぜると、
            //   どれがサンプルか区別できなくなる
            return ResponseEntity.badRequest().body(Map.of(
                "error", "already_has_data",
                "message", "すでに顧客データがあります。"
                    + "サンプルを追加するには force=true を指定してください"));
        }

        // ★ 監査は SampleDataService の中（データ生成と同じトランザクション）で行う。
        //   ここで呼ぶとトランザクションの外になり、RLS に弾かれる
        var s = sampleData.generate(user);

        return ResponseEntity.ok(Map.of(
            "customers", s.customers(),
            "campaigns", s.campaigns(),
            "callTargets", s.callTargets(),
            "callSessions", s.callSessions(),
            "dncEntries", s.dncEntries(),
            "callbacks", s.callbacks(),
            "message", "サンプルデータを投入しました"));
    }

    /**
     * サンプル（というより自テナントの業務データ）を全部消す。
     *
     * <p>★ 消えるのは自テナントのぶんだけ。RLS が効いているので、
     * 他テナントには 1 行も影響しない。
     */
    @DeleteMapping("/sample-data")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> clear(@RequestParam(defaultValue = "false") boolean confirm) {
        var user = CurrentUser.require();

        if (!confirm) {
            // ★ 取り返しがつかないので、確認なしでは実行しない
            return ResponseEntity.badRequest().body(Map.of(
                "error", "confirm_required",
                "message", "顧客・通話履歴・キャンペーンをすべて削除します。"
                    + "実行するには confirm=true を指定してください"));
        }

        sampleData.clear(user);
        return ResponseEntity.ok(Map.of("ok", true, "message", "削除しました"));
    }
}
