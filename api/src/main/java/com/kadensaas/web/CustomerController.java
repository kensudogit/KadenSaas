package com.kadensaas.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kadensaas.domain.Customer;
import com.kadensaas.domain.CustomerPhone;
import com.kadensaas.repository.CustomerPhoneRepository;
import com.kadensaas.repository.CustomerRepository;
import com.kadensaas.service.AuditService;
import com.kadensaas.service.PhoneNumbers;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    public record CreateRequest(String companyName, String contactName, String email,
                                @NotBlank String phone, String[] tags, String note) {
    }

    public record AddPhoneRequest(@NotBlank String phone, String kind, boolean primary) {
    }

    private final CustomerRepository customers;
    private final CustomerPhoneRepository phones;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public CustomerController(CustomerRepository customers, CustomerPhoneRepository phones,
                              AuditService audit, JdbcTemplate jdbc) {
        this.customers = customers;
        this.phones = phones;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /**
     * 顧客リスト。
     *
     * <p>★ 主電話番号と担当者を一緒に返す。画面が行ごとに追加問い合わせを
     * するのを避けるためだが、それ以上に「架電」ボタンを出すのに番号が要る。
     * 番号の無い顧客にボタンを出しても、押した先で失敗するだけ。
     *
     * <p>★ 再勧誘拒否かどうかも返す。DNC の相手に架電ボタンを出しても
     * 関門が止めるので実害は無いが、押してから止められるより、
     * 最初から押せないほうが分かりやすい。
     *
     * <p>★ 番号は主番号（is_primary）を 1 件だけ。複数ある顧客もいるが、
     * 一覧で全部出すと行の高さが揃わず読みにくい。詳細で見せる。
     */
    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(@RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        int limit = Math.min(size, 200);
        int offset = Math.max(0, page) * limit;

        String filter = q != null && !q.isBlank()
            ? " and (c.company_name ilike ? or c.contact_name ilike ? or p.e164 ilike ?) "
            : "";

        String sql = """
            select c.id, c.company_name, c.contact_name, c.status, c.created_at,
                   c.owner_id, u.display_name as owner_name,
                   p.id   as phone_id,
                   p.e164 as phone_e164,
                   p.raw_number as phone_raw,
                   exists (select 1 from do_not_call_entries d where d.e164 = p.e164)
                     as do_not_call
              from customers c
              left join users u on u.id = c.owner_id
              left join lateral (
                select ph.id, ph.e164, ph.raw_number
                  from customer_phones ph
                 where ph.customer_id = c.id
                 order by ph.is_primary desc, ph.created_at
                 limit 1
              ) p on true
             where true
            """ + filter + " order by c.created_at desc limit ? offset ?";

        Object[] args;
        if (filter.isEmpty()) {
            args = new Object[] {limit, offset};
        } else {
            String like = "%" + q.trim() + "%";
            args = new Object[] {like, like, like, limit, offset};
        }
        return jdbc.queryForList(sql, args);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        Customer c = customers.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("顧客が見つかりません"));
        return Map.of("customer", c, "phones", phones.findByCustomerId(id));
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody CreateRequest req) {
        var user = CurrentUser.require();

        // ★ 正規化を先に済ませる。ここで弾いておかないと、
        //   DNC 照合が通らない番号がリストに紛れ込む
        String e164 = PhoneNumbers.toE164Jp(req.phone());

        Customer c = new Customer();
        c.setTenantId(user.tenantId());
        c.setCompanyName(req.companyName());
        c.setContactName(req.contactName());
        c.setEmail(req.email());
        c.setStatus("new");
        c.setOwnerId(user.userId());
        c.setTags(req.tags() != null ? req.tags() : new String[0]);
        c.setNote(req.note());
        customers.save(c);
        customers.flush();

        CustomerPhone p = new CustomerPhone();
        p.setTenantId(user.tenantId());
        p.setCustomerId(c.getId());
        p.setRawNumber(req.phone());
        p.setE164(e164);
        p.setKind("main");
        p.setPrimaryNumber(true);
        phones.save(p);

        audit.record(user, "customer.created", "customer", c.getId());
        return Map.of("id", c.getId());
    }

    @PostMapping("/{id}/phones")
    @Transactional
    public Map<String, Object> addPhone(@PathVariable UUID id, @RequestBody AddPhoneRequest req) {
        var user = CurrentUser.require();
        customers.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("顧客が見つかりません"));

        CustomerPhone p = new CustomerPhone();
        p.setTenantId(user.tenantId());
        p.setCustomerId(id);
        p.setRawNumber(req.phone());
        p.setE164(PhoneNumbers.toE164Jp(req.phone()));
        p.setKind(req.kind() != null ? req.kind() : "other");
        p.setPrimaryNumber(req.primary());
        phones.save(p);

        audit.record(user, "customer.phone_added", "customer", id);
        return Map.of("id", p.getId());
    }
}
