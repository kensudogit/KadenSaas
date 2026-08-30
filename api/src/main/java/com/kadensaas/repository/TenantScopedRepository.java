package com.kadensaas.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * RLS が効くテーブルを扱うリポジトリの基底。
 *
 * <p><b>★ このプロジェクトのリポジトリは必ずこれを継承すること。JpaRepository を
 * 直接継承してはいけない。</b>
 *
 * <p>理由。テナントは {@code SET LOCAL app.tenant_id} で
 * <b>Spring のトランザクション開始時</b>に注入される
 * （{@code TenantAwareTransactionManager}）。ところが Spring Data の
 * <b>派生クエリメソッド（findByXxx のような、メソッド名から組み立てられる問い合わせ）は、
 * 明示的な {@code @Transactional} が無いと Spring のトランザクションに入らない</b>。
 * {@code SimpleJpaRepository} のクラスレベルの {@code @Transactional} は、
 * そこに実装のあるメソッド（findAll / findById / save など）にしか効かないため。
 *
 * <p>その結果どうなるか。派生クエリは Hibernate 独自のトランザクションで走り、
 * {@code app.tenant_id} が未設定のまま RLS が評価される。ポリシーは
 * {@code tenant_id = app_current_tenant()} で、未設定なら null なので比較が成立せず、
 * <b>常に 0 行が返る</b>。例外は出ない。警告も出ない。画面には
 * 「データがありません」とだけ表示される。
 *
 * <p>実際にこれで詰まった。顧客一覧が空になり、DB には 4 件あり、
 * SQL ログを見て初めて {@code BEGIN} の直後に {@code set_config} が
 * 無いことに気付いた。
 *
 * <p>インターフェースに {@code @Transactional(readOnly = true)} を置くと、
 * 派生クエリを含む全メソッドが Spring のトランザクションに入る。
 * 書き込み系（save / delete）は {@code SimpleJpaRepository} 側の
 * 非 readOnly な {@code @Transactional} が優先されるので影響しない。
 *
 * <p>継承し忘れは {@code RepositoryTransactionTest} が検出する。
 */
@NoRepositoryBean
@Transactional(readOnly = true)
public interface TenantScopedRepository<T, ID> extends JpaRepository<T, ID> {

    @Override
    List<T> findAll();

    @Override
    Optional<T> findById(ID id);
}
