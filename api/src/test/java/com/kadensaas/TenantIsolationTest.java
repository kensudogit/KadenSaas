package com.kadensaas;

import java.util.UUID;

import com.kadensaas.repository.CustomerRepository;
import com.kadensaas.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * テナント分離が「書いてあるだけでなく効いている」ことを確かめる。
 *
 * <p>★ このテストが守っているのは、開発中に実際に 3 回踏んだ失敗である。
 * どれも例外が出ず、200 が返り、ただ 0 行になる（あるいは他テナントが見える）。
 * 疎通確認では見つからないので、ここで固定する。
 *
 * <ol>
 *   <li>Spring Data の派生クエリメソッドが Spring のトランザクションに入らず、
 *       app.tenant_id が未設定のまま RLS が評価されて常に 0 行になる</li>
 *   <li>テナント未設定で「全部見える」のではなく「1 行も見えない」こと</li>
 *   <li>他テナントを騙った書き込みが拒否されること</li>
 * </ol>
 */
class TenantIsolationTest extends AbstractIntegrationTest {

    @Autowired
    CustomerRepository customers;

    @AfterEach
    void clearContext() {
        // ★ スレッドはテスト間で使い回される。残すと次のテストが
        //   前のテナントとして動き、結果が信用できなくなる
        TenantContext.clear();
    }

    @Test
    @DisplayName("自テナントの顧客だけが見える")
    void seesOnlyOwnTenant() {
        UUID a = createTenant("tenant-a", "A社");
        UUID b = createTenant("tenant-b", "B社");
        createCustomer(a, "Aの顧客1");
        createCustomer(a, "Aの顧客2");
        createCustomer(b, "Bの顧客");

        TenantContext.set(a);
        var seenByA = customers.findAll();
        assertThat(seenByA).hasSize(2);
        assertThat(seenByA).allMatch(c -> c.getTenantId().equals(a));

        TenantContext.set(b);
        var seenByB = customers.findAll();
        assertThat(seenByB).hasSize(1);
        assertThat(seenByB.get(0).getCompanyName()).isEqualTo("Bの顧客");
    }

    @Test
    @DisplayName("★ 派生クエリメソッドでもテナントが効く（実際に踏んだ罠）")
    void derivedQueryMethodsAreTenantScoped() {
        UUID a = createTenant("tenant-a", "A社");
        UUID b = createTenant("tenant-b", "B社");
        createCustomer(a, "Aの顧客");
        createCustomer(b, "Bの顧客");

        TenantContext.set(a);

        // ★ findAll() は SimpleJpaRepository の実装があるので @Transactional が効く。
        //   一方 findAllByOrderByCreatedAtDesc は派生クエリで、
        //   TenantScopedRepository の基底注釈が無いと Spring のトランザクションに
        //   入らず、常に 0 件になる。ここが 0 件になったら基底の継承が外れている
        var page = customers.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 50));

        assertThat(page.getContent())
            .as("派生クエリが 0 件なら、リポジトリが TenantScopedRepository を "
                + "継承していないか、基底の @Transactional が外れている")
            .hasSize(1);
        assertThat(page.getContent().get(0).getCompanyName()).isEqualTo("Aの顧客");
    }

    @Test
    @DisplayName("★ 検索（派生でない @Query）でもテナントが効く")
    void searchIsTenantScoped() {
        UUID a = createTenant("tenant-a", "A社");
        UUID b = createTenant("tenant-b", "B社");
        createCustomer(a, "アルファ商事");
        createCustomer(b, "アルファ工業");

        TenantContext.set(a);
        var found = customers.search("アルファ", PageRequest.of(0, 50));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getCompanyName()).isEqualTo("アルファ商事");
    }

    @Test
    @DisplayName("★ テナント未設定なら全部見えるのではなく 1 行も見えない（fail closed）")
    void failsClosedWithoutTenant() {
        UUID a = createTenant("tenant-a", "A社");
        createCustomer(a, "Aの顧客");

        TenantContext.clear();

        assertThat(customers.findAll())
            .as("未設定で 1 件でも見えたら fail open になっている。"
                + "この場合、認証を通らない経路から全テナントが読める")
            .isEmpty();
    }

    @Test
    @DisplayName("★ 他テナントを騙った書き込みは拒否される")
    void cannotWriteToOtherTenant() {
        UUID a = createTenant("tenant-a", "A社");
        UUID b = createTenant("tenant-b", "B社");

        TenantContext.set(a);

        var intruder = new com.kadensaas.domain.Customer();
        intruder.setTenantId(b);          // ★ 他テナントの id を指定する
        intruder.setCompanyName("侵入");
        intruder.setStatus("new");
        intruder.setTags(new String[0]);

        // RLS の with check が弾く
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            customers.save(intruder);
            customers.flush();
        }).as("他テナントへの書き込みが通ると、テナント分離は無い");
    }
}
