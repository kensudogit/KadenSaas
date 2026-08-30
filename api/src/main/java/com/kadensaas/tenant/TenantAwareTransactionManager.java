package com.kadensaas.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;

/**
 * トランザクションを開いた直後に {@code app.tenant_id} を注入するトランザクションマネージャ。
 *
 * <p>★ なぜアスペクトやリポジトリの共通処理ではなく、ここに置くのか。
 * RLS を効かせるには「そのトランザクションを実行する接続の上で」
 * {@code SET LOCAL} していなければならない。呼び出し側のコードに任せると、
 * 新しいサービスクラスを 1 つ足したときに書き忘れる。書き忘れても
 * 例外は出ず、ただ 0 行返るか、あるいは（ポリシーの書き方を間違えていれば）
 * 他テナントが見える。忘れられる場所に置かないのが唯一の防御になる。
 *
 * <p>★ {@code set_config(..., true)} は {@code SET LOCAL} と同義で、
 * トランザクション終了時に自動で戻る。接続はプールで使い回されるので、
 * トランザクションを越えて値が残る形（{@code SET} や接続初期化）にしてはいけない。
 * 残ると、返却された接続を拾った別のリクエストが前のテナントとして動く。
 */
public class TenantAwareTransactionManager extends JpaTransactionManager {

    private static final String SET_TENANT = "select set_config('app.tenant_id', ?, true)";

    public TenantAwareTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        DataSource dataSource = getDataSource();
        if (dataSource == null) {
            return;
        }

        // ★ super.doBegin のあとなので、この接続はいま開いたトランザクションのもの。
        //   DataSourceUtils は同じトランザクションに束ねられた接続を返す
        Connection connection = DataSourceUtils.getConnection(dataSource);
        UUID tenantId = TenantContext.get();

        try (PreparedStatement ps = connection.prepareStatement(SET_TENANT)) {
            // ★ null のときは空文字を入れる。空文字なら app_current_tenant() が
            //   null を返し、ポリシーが 1 行も通さない。
            //   「未設定だから全部見せる」にしないことがこの設計の要。
            ps.setString(1, tenantId == null ? "" : tenantId.toString());
            ps.execute();
        } catch (SQLException e) {
            throw new CannotCreateTransactionException(
                "テナントの設定に失敗しました。RLS が効かない状態で処理を続けないため中断します", e);
        }
    }
}
