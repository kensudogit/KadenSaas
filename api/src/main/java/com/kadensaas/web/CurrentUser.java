package com.kadensaas.web;

import com.kadensaas.security.AuthUser;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * いまのリクエストの利用者を取り出す。
 *
 * <p>★ ここ以外で principal を触らない。テナント判定に関わる値の
 * 取り出し口を 1 箇所にしておくと、「どこからテナントが決まるか」を
 * 追うのが grep 1 回で済む。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AuthUser require() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser user)) {
            throw new IllegalStateException("認証されていません");
        }
        return user;
    }
}
