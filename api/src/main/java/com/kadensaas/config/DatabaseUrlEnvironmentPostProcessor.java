package com.kadensaas.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * PaaS が配る {@code DATABASE_URL} を Spring の接続設定に変換する。
 *
 * <p>★ なぜ必要か。Railway / Heroku / Fly.io などは接続情報を
 * {@code postgresql://user:pass@host:5432/db} の 1 本の URL で渡す。
 * 一方 Spring Boot が理解するのは {@code jdbc:postgresql://host:5432/db} で、
 * ユーザーとパスワードは別プロパティ。そのままでは繋がらない。
 *
 * <p>変換を用意していないと、デプロイのたびに人間が
 * {@code SPRING_DATASOURCE_URL} / {@code _USERNAME} / {@code _PASSWORD} の
 * 3 つに手で分解して設定することになる。そして忘れると、
 * application.yml の既定値（localhost）にフォールバックして
 * 「Connection to localhost:5433 refused」で落ちる。
 * 実際にこれで Railway のデプロイが 502 になった。
 *
 * <p>★ voice サービス（FastAPI）は最初から {@code DATABASE_URL} を
 * そのまま受けている。api だけ違う形を要求するのは一貫性が無く、
 * ポリグロット構成では特に混乱の元になる。
 *
 * <p>★ 明示的に指定された {@code SPRING_DATASOURCE_*} を上書きしない。
 * こちらは「未設定のときに補う」だけ。docker-compose のように
 * 個別に指定している環境の挙動を変えないため。
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "kadenDatabaseUrl";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Map<String, Object> resolved = new HashMap<>();

        // アプリ用（RLS が効く kaden_app）
        apply(env, resolved, "DATABASE_URL", "SPRING_DATASOURCE_URL",
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password");

        // マイグレーション用（BYPASSRLS を持つ kaden_migrator）
        // ★ 未設定なら Flyway は spring.datasource の値を使う。
        //   その場合 kaden_app でマイグレーションを流すことになり、
        //   RLS が効いた状態で DDL を打つので失敗する。
        //   本番では必ず分けること。
        apply(env, resolved, "DATABASE_MIGRATOR_URL", "SPRING_FLYWAY_URL",
            "spring.flyway.url",
            "spring.flyway.user",
            "spring.flyway.password");

        if (!resolved.isEmpty()) {
            // ★ 先頭に足す。addLast にすると application.yml の既定値
            //   （localhost:5433）のほうが優先され、変換した値が使われない。
            //   実際にそれで「Connection to localhost:5433 refused」が出た。
            //
            //   明示された SPRING_DATASOURCE_* を踏まないことは、
            //   apply() の中で「環境変数そのものの有無」を見て担保している。
            env.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, resolved));
        }
    }

    private void apply(ConfigurableEnvironment env, Map<String, Object> out,
                       String envName, String explicitEnvName,
                       String urlKey, String userKey, String passKey) {

        String raw = env.getProperty(envName);
        if (raw == null || raw.isBlank()) {
            return;
        }

        // ★ 明示指定があるなら触らない。
        //   ここで env.getProperty(urlKey)（= spring.datasource.url）を見ては
        //   いけない。application.yml の既定値まで拾ってしまい、
        //   「常に明示指定がある」と判定されて変換が一度も効かなくなる。
        //   環境変数そのものの有無で判断する。
        String explicit = env.getProperty(explicitEnvName);
        if (explicit != null && !explicit.isBlank()) {
            return;
        }

        try {
            URI uri = new URI(raw.trim());
            String host = uri.getHost();
            if (host == null) {
                return;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");

            StringBuilder jdbc = new StringBuilder("jdbc:postgresql://")
                .append(host).append(':').append(port).append('/').append(database);

            // ★ クエリ文字列（sslmode など）を落とさない。
            //   マネージド DB では sslmode=require が付いていることがある
            if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
                jdbc.append('?').append(uri.getQuery());
            }
            out.put(urlKey, jdbc.toString());

            String userInfo = uri.getUserInfo();
            if (userInfo != null && !userInfo.isBlank()) {
                int sep = userInfo.indexOf(':');
                if (sep >= 0) {
                    out.put(userKey, decode(userInfo.substring(0, sep)));
                    out.put(passKey, decode(userInfo.substring(sep + 1)));
                } else {
                    out.put(userKey, decode(userInfo));
                }
            }
        } catch (URISyntaxException e) {
            // ★ 握りつぶす。ここで例外を投げると、値の形が少し違うだけで
            //   起動そのものができなくなる。変換できなければ既定値のまま進み、
            //   接続時にもっと分かりやすいエラーが出る
        }
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
