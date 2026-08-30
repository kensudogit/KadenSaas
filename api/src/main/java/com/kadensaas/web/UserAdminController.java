package com.kadensaas.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kadensaas.security.AuthUser;
import com.kadensaas.security.PermissionCatalog;
import com.kadensaas.service.UserAdminService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 利用者の管理と、権限の一覧。
 *
 * <p>★ {@code /api/v1/admin/**} は SecurityConfig で admin に限定済み。
 * ここでも {@code @PreAuthorize} を書くのは二重に見えるが、
 * パスの構成を変えたときに片方だけ残るのを防ぐため（AdminController と同じ方針）。
 *
 * <p>★ 初期パスワードは応答に一度だけ含める。保存もログ出力もしない。
 * 再表示はできず、忘れた場合は再発行になる。
 */
@RestController
@RequestMapping("/api/v1")
public class UserAdminController {

    public record CreateRequest(@NotBlank String email, String displayName,
                                @NotBlank String role) {
    }

    public record RoleRequest(@NotBlank String role) {
    }

    public record PasswordRequest(@NotBlank String currentPassword,
                                  @NotBlank String newPassword) {
    }

    private final UserAdminService users;

    public UserAdminController(UserAdminService users) {
        this.users = users;
    }

    // ------------------------------------------------------------ 利用者

    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> list() {
        return users.list();
    }

    @PostMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> create(@RequestBody CreateRequest req) {
        var created = users.create(
            CurrentUser.require(), req.email(), req.displayName(), req.role());

        return ResponseEntity.ok(Map.of(
            "id", created.userId(),
            "email", created.email(),
            "role", created.role(),
            // ★ ここでしか返らない。再表示はできない
            "initialPassword", created.initialPassword(),
            "message", "初期パスワードはこの画面にしか表示されません。"
                + "本人に安全な経路で渡してください。最初のログイン後に変更が求められます"));
    }

    @PatchMapping("/admin/users/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> changeRole(@PathVariable UUID id,
                                        @RequestBody RoleRequest req) {
        users.changeRole(CurrentUser.require(), id, req.role());
        return ResponseEntity.ok(Map.of("ok", true, "message", "役割を変更しました"));
    }

    @PatchMapping("/admin/users/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> setStatus(@PathVariable UUID id,
                                       @RequestParam boolean active) {
        users.setStatus(CurrentUser.require(), id, active);
        return ResponseEntity.ok(Map.of("ok", true,
            "message", active ? "有効にしました" : "無効にしました"));
    }

    @PostMapping("/admin/users/{id}/password-reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> resetPassword(@PathVariable UUID id) {
        String initial = users.resetPassword(CurrentUser.require(), id);
        return ResponseEntity.ok(Map.of(
            "initialPassword", initial,
            "message", "この画面にしか表示されません。本人に安全な経路で渡してください"));
    }

    // ------------------------------------------------------------ 本人

    /**
     * 本人によるパスワード変更。
     *
     * <p>★ 管理者専用ではない。管理者が発行した初期パスワードのまま
     * 使い続けられる状態を作らないために、全員が使える必要がある。
     */
    @PostMapping("/auth/password")
    public ResponseEntity<?> changeOwnPassword(@RequestBody PasswordRequest req) {
        users.changeOwnPassword(
            CurrentUser.require(), req.currentPassword(), req.newPassword());
        return ResponseEntity.ok(Map.of("ok", true, "message", "パスワードを変更しました"));
    }

    // ------------------------------------------------------------ 権限

    /**
     * 役割ごとに何ができるかの一覧。
     *
     * <p>★ 全員が見られる。自分に何が許されているかは、権限を持つ人だけの
     * 情報ではない。「なぜこの操作ができないのか」を各自が確認できるほうが、
     * 管理者への問い合わせが減る。
     *
     * <p>★ この一覧が実装とずれていないことは {@code PermissionMatrixTest} が
     * 実際に各入口を叩いて確かめている。
     */
    @GetMapping("/permissions")
    public Map<String, Object> permissions() {
        AuthUser actor = CurrentUser.require();

        var capabilities = PermissionCatalog.ALL.stream()
            .map(c -> Map.of(
                "key", c.key(),
                "label", c.label(),
                "detail", c.detail(),
                "operator", c.allows(AuthUser.Role.OPERATOR),
                "manager", c.allows(AuthUser.Role.MANAGER),
                "admin", c.allows(AuthUser.Role.ADMIN),
                "allowedForMe", c.allows(actor.role())))
            .toList();

        return Map.of(
            "myRole", actor.role().name().toLowerCase(),
            "capabilities", capabilities);
    }
}
