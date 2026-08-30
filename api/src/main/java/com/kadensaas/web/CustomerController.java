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
import org.springframework.data.domain.PageRequest;
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

    public CustomerController(CustomerRepository customers, CustomerPhoneRepository phones,
                              AuditService audit) {
        this.customers = customers;
        this.phones = phones;
        this.audit = audit;
    }

    @GetMapping
    public List<Customer> list(@RequestParam(required = false) String q,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "50") int size) {
        var pageable = PageRequest.of(page, Math.min(size, 200));
        if (q != null && !q.isBlank()) {
            return customers.search(q, pageable);
        }
        return customers.findAllByOrderByCreatedAtDesc(pageable).getContent();
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
