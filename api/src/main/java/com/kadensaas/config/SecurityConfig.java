package com.kadensaas.config;

import java.util.List;

import com.kadensaas.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final String corsOrigin;

    public SecurityConfig(@Value("${kaden.cors.origin}") String corsOrigin) {
        this.corsOrigin = corsOrigin;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter)
            throws Exception {
        http
            // ★ トークン認証なので CSRF は不要。Cookie を使わないことが前提で、
            //   Cookie に切り替えるならここを戻すこと
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                // ★ テナント登録は認証前に呼ばれる。この系で唯一の
                //   「認証なしで書き込める口」なので、SignupController 側で
                //   トークンによる保護と、未設定時の無効化を行っている
                .requestMatchers(HttpMethod.POST, "/api/v1/signup").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/signup/available").permitAll()
                // ★ Stripe の webhook は署名で検証する。JWT は付かない
                .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/billing").permitAll()
                // ★ 診断だけは manager にも開ける。発信が止まっている理由を
                //   知りたいのは、設定を変える人だけではない。
                //   この行は /api/v1/admin/** より前に置く（先勝ち）
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/telephony/diagnose")
                    .hasAnyRole("MANAGER", "ADMIN")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private CorsConfigurationSource corsSource() {
        CorsConfiguration c = new CorsConfiguration();
        // ★ ワイルドカードにしない。録音や顧客情報を返す API なので、
        //   出所を絞る。開発用の localhost も環境変数で明示的に渡す
        c.setAllowedOrigins(List.of(corsOrigin.split(",")));
        c.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
