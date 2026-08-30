package com.kadensaas.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT の発行と検証。
 *
 * <p>★ 秘密鍵は voice サービス（FastAPI）と同一でなければならない。
 * ポリグロット構成で最も踏みやすいのがここで、片方で生成し直すと
 * 「api では通るのに voice で 401」という切り分けにくい壊れ方をする。
 * 環境変数 JWT_SECRET を両サービスに同じ値で渡すこと。
 *
 * <p>★ HS256（共通鍵）にしてあるのは、サービスが 2 つだけで
 * 鍵の配布が環境変数で済むため。サービスが増えるなら RS256 に変え、
 * voice 側は公開鍵だけを持つ形にする。
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${kaden.jwt.secret}") String secret,
                      @Value("${kaden.jwt.ttl-minutes}") long ttlMinutes) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            // ★ 短い鍵を黙って受け入れない。HS256 は 256bit 未満だと
            //   総当たりが現実的になる。起動時に落とすほうが安い
            throw new IllegalStateException(
                "JWT_SECRET が短すぎます（32 バイト以上必要）。openssl rand -hex 32 で生成してください");
        }
        this.key = Keys.hmacShaKeyFor(raw);
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public String issue(AuthUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(user.userId().toString())
            // ★ voice 側もこのクレーム名で読む。変えるときは両方同時に
            .claim("tid", user.tenantId().toString())
            .claim("email", user.email())
            .claim("role", user.role().name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key)
            .compact();
    }

    /** 検証に失敗したら例外。呼び出し側は 401 に変換する。 */
    public AuthUser parse(String token) {
        Claims c = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        return new AuthUser(
            UUID.fromString(c.getSubject()),
            UUID.fromString(c.get("tid", String.class)),
            c.get("email", String.class),
            AuthUser.Role.of(c.get("role", String.class)));
    }
}
