package com.kadensaas.security;

import java.io.IOException;
import java.util.List;

import com.kadensaas.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorization ヘッダの JWT を検証し、テナントを文脈に置く。
 *
 * <p>★ ここが RLS への唯一の入口。トークンに入っている tenant_id 以外の経路で
 * テナントが決まることが無いようにしてある。クエリパラメータやヘッダで
 * テナントを指定できるようにすると、その瞬間に分離が壊れる。
 *
 * <p>★ finally で必ず clear する。スレッドプールで使い回されるので、
 * 消し忘れると次のリクエストが前のテナントとして動く。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                AuthUser user = jwt.parse(header.substring(7));
                var auth = new UsernamePasswordAuthenticationToken(
                    user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
                TenantContext.set(user.tenantId());
            } catch (Exception e) {
                // ★ 壊れたトークンは「認証なし」として扱う。ここで 401 を返さないのは、
                //   認証不要のエンドポイントを塞がないため。保護は SecurityConfig が行う
                SecurityContextHolder.clearContext();
                TenantContext.clear();
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
