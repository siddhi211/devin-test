package com.legacyins.insurance.customer;

import com.legacyins.insurance.auth.AuditService;
import com.legacyins.insurance.common.ApiException;
import com.legacyins.insurance.common.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CustomerService {
    private static final Map<String, Set<String>> STATUS_TRANSITIONS = Map.of(
            "A", Set.of("I", "B", "D"),
            "I", Set.of("A", "B", "D"),
            "B", Set.of("A", "D"),
            "D", Set.of(),
            "M", Set.of());

    private final CustomerRepository customers;
    private final CustomerAddressRepository addresses;
    private final BeneficiaryRepository beneficiaries;
    private final CustomerDocumentRepository documents;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public record CustomerRequest(String customerType, String firstName, String lastName, String companyName,
                                  String nationalId, LocalDate dateOfBirth, String gender, String email, String phone,
                                  String occupationCode, String riskClass, String segment) {
    }

    public record AddressRequest(String addressType, String line1, String line2, String city, String state,
                                 String postalCode, String country, Boolean primary) {
    }

    public record BeneficiaryRequest(String fullName, String relationship, BigDecimal sharePct, LocalDate dateOfBirth) {
    }

    public record DocumentRequest(String docType, String docNumber, String fileName, LocalDate issueDt, LocalDate expiryDt) {
    }

    public record CustomerDetail(Customer customer, String displayName, List<CustomerAddress> addresses,
                                 List<Beneficiary> beneficiaries, List<CustomerDocument> documents,
                                 List<Map<String, Object>> statusHistory, List<Map<String, Object>> kycCases,
                                 List<Map<String, Object>> policies, List<String> kycMissing) {
    }

    public Customer get(Long id) {
        return customers.findById(id).orElseThrow(() -> ApiException.notFound("Customer", id));
    }

    public PageResponse<Customer> search(String q, String status, int page, int size) {
        String like = (q == null || q.isBlank()) ? null : "%" + normalize(q) + "%";
        String st = (status == null || status.isBlank()) ? null : status;
        return PageResponse.of(customers.search(like, st,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "id"))), c -> c);
    }

    public CustomerDetail detail(Long id) {
        Customer c = get(id);
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT * FROM customer_status_history WHERE customer_id=? ORDER BY history_id DESC", id);
        List<Map<String, Object>> cases = jdbc.queryForList(
                "SELECT * FROM kyc_case WHERE customer_id=? ORDER BY case_id DESC", id);
        List<Map<String, Object>> pols = jdbc.queryForList("""
                SELECT p.policy_id, p.policy_no, p.status, p.sum_insured, p.annual_premium, p.start_dt, p.end_dt,
                       pr.product_code, pr.product_name
                FROM policy p JOIN insurance_product pr ON pr.product_id = p.product_id
                WHERE p.customer_id=? ORDER BY p.policy_id DESC""", id);
        return new CustomerDetail(c, c.displayName(), addresses.findByCustomerIdOrderByIdAsc(id),
                beneficiaries.findByCustomerIdOrderByIdAsc(id), documents.findByCustomerIdOrderByIdAsc(id),
                history, cases, pols, missingKycDocs(c));
    }

    @Transactional
    public Customer create(CustomerRequest r, String actor) {
        Customer c = new Customer();
        apply(c, r);
        validate(c);
        if (c.getNormNid() != null && !customers.findByNormNidAndStatusNot(c.getNormNid(), "M").isEmpty()) {
            throw ApiException.duplicate("A customer with this national ID already exists");
        }
        c.setCreatedBy(actor);
        c.setCustomerNo("TMP-" + UUID.randomUUID().toString().substring(0, 12));
        customers.saveAndFlush(c);
        c.setCustomerNo(String.format("CUS%07d", c.getId()));
        audit.log(actor, "CUSTOMER_CREATE", "CUSTOMER", c.getId(), c.getCustomerNo());
        return c;
    }

    @Transactional
    public Customer update(Long id, CustomerRequest r, String actor) {
        Customer c = get(id);
        if ("M".equals(c.getStatus()) || "D".equals(c.getStatus())) {
            throw ApiException.rule("Merged or deceased customers cannot be edited");
        }
        String oldNid = c.getNormNid();
        apply(c, r);
        validate(c);
        if (c.getNormNid() != null && !c.getNormNid().equals(oldNid)
                && customers.findByNormNidAndStatusNot(c.getNormNid(), "M").stream().anyMatch(o -> !o.getId().equals(id))) {
            throw ApiException.duplicate("A customer with this national ID already exists");
        }
        c.setUpdatedDt(LocalDateTime.now());
        audit.log(actor, "CUSTOMER_UPDATE", "CUSTOMER", id, null);
        return c;
    }

    @Transactional
    public Customer changeStatus(Long id, String newStatus, String reason, String actor) {
        Customer c = get(id);
        Set<String> allowed = STATUS_TRANSITIONS.getOrDefault(c.getStatus(), Set.of());
        if (!allowed.contains(newStatus)) {
            throw ApiException.rule("Customer status cannot change from " + c.getStatus() + " to " + newStatus);
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.validation("Reason is required");
        }
        jdbc.update("INSERT INTO customer_status_history (customer_id, old_status, new_status, reason, changed_by) VALUES (?,?,?,?,?)",
                id, c.getStatus(), newStatus, reason, actor);
        c.setStatus(newStatus);
        c.setUpdatedDt(LocalDateTime.now());
        audit.log(actor, "CUSTOMER_STATUS", "CUSTOMER", id, newStatus + ": " + reason);
        return c;
    }

    public List<Customer> findDuplicates(Long id) {
        Customer c = get(id);
        List<Customer> result = new ArrayList<>();
        if (c.getNormNid() != null) {
            result.addAll(customers.findByNormNidAndStatusNot(c.getNormNid(), "M"));
        }
        if (c.getNormName() != null && c.getDateOfBirth() != null) {
            result.addAll(jdbcCustomers("SELECT customer_id FROM customer WHERE norm_name=? AND date_of_birth=? AND status<>'M'",
                    c.getNormName(), c.getDateOfBirth()));
        }
        Map<Long, Customer> unique = new LinkedHashMap<>();
        result.stream().filter(o -> !o.getId().equals(id)).forEach(o -> unique.put(o.getId(), o));
        return new ArrayList<>(unique.values());
    }

    private List<Customer> jdbcCustomers(String sql, Object... args) {
        return jdbc.queryForList(sql, Long.class, args).stream().map(this::get).toList();
    }

    @Transactional
    public Customer merge(Long survivorId, Long duplicateId, String reason, String actor) {
        if (survivorId.equals(duplicateId)) {
            throw ApiException.validation("Survivor and duplicate must differ");
        }
        Customer survivor = get(survivorId);
        Customer dup = get(duplicateId);
        if ("M".equals(survivor.getStatus()) || "M".equals(dup.getStatus())) {
            throw ApiException.rule("Customer already merged");
        }
        String snapshot = String.format("{\"customerNo\":\"%s\",\"name\":\"%s\",\"status\":\"%s\"}",
                dup.getCustomerNo(), dup.displayName().replace("\"", ""), dup.getStatus());
        int moved = jdbc.update("UPDATE policy SET customer_id=? WHERE customer_id=?", survivorId, duplicateId);
        jdbc.update("UPDATE customer_address SET customer_id=?, primary_flag='N' WHERE customer_id=?", survivorId, duplicateId);
        jdbc.update("UPDATE beneficiary SET customer_id=? WHERE customer_id=?", survivorId, duplicateId);
        jdbc.update("UPDATE customer_document SET customer_id=? WHERE customer_id=?", survivorId, duplicateId);
        jdbc.update("INSERT INTO customer_merge_log (survivor_id, duplicate_id, reason, snapshot, merged_by) VALUES (?,?,?,?,?)",
                survivorId, duplicateId, reason == null ? "Duplicate" : reason, snapshot, actor);
        jdbc.update("INSERT INTO customer_status_history (customer_id, old_status, new_status, reason, changed_by) VALUES (?,?,?,?,?)",
                duplicateId, dup.getStatus(), "M", "Merged into " + survivor.getCustomerNo(), actor);
        dup.setStatus("M");
        dup.setMergedIntoId(survivorId);
        dup.setNormNid(null);
        dup.setUpdatedDt(LocalDateTime.now());
        audit.log(actor, "CUSTOMER_MERGE", "CUSTOMER", survivorId, "merged " + dup.getCustomerNo() + ", policies moved=" + moved);
        return survivor;
    }

    @Transactional
    public CustomerAddress addAddress(Long customerId, AddressRequest r, String actor) {
        get(customerId);
        if (r.line1() == null || r.line1().isBlank() || r.city() == null || r.city().isBlank()) {
            throw ApiException.validation("Address line1 and city are required");
        }
        boolean primary = Boolean.TRUE.equals(r.primary()) || addresses.findByCustomerIdOrderByIdAsc(customerId).isEmpty();
        if (primary) {
            jdbc.update("UPDATE customer_address SET primary_flag='N' WHERE customer_id=?", customerId);
        }
        CustomerAddress a = new CustomerAddress();
        a.setCustomerId(customerId);
        a.setAddressType(r.addressType() == null ? "HOME" : r.addressType());
        a.setLine1(r.line1());
        a.setLine2(r.line2());
        a.setCity(r.city());
        a.setState(r.state());
        a.setPostalCode(r.postalCode());
        a.setCountry(r.country() == null ? "US" : r.country());
        a.setPrimaryFlag(primary ? "Y" : "N");
        a.setEffectiveFrom(LocalDate.now());
        addresses.save(a);
        audit.log(actor, "ADDRESS_ADD", "CUSTOMER", customerId, a.getLine1());
        return a;
    }

    @Transactional
    public void deleteAddress(Long customerId, Long addressId, String actor) {
        CustomerAddress a = addresses.findById(addressId)
                .filter(x -> x.getCustomerId().equals(customerId))
                .orElseThrow(() -> ApiException.notFound("Address", addressId));
        addresses.delete(a);
        audit.log(actor, "ADDRESS_DELETE", "CUSTOMER", customerId, String.valueOf(addressId));
    }

    @Transactional
    public Beneficiary addBeneficiary(Long customerId, BeneficiaryRequest r, String actor) {
        get(customerId);
        if (r.fullName() == null || r.fullName().isBlank() || r.relationship() == null || r.sharePct() == null) {
            throw ApiException.validation("Name, relationship and share are required");
        }
        if (r.sharePct().signum() <= 0 || r.sharePct().compareTo(new BigDecimal("100")) > 0) {
            throw ApiException.validation("Share must be between 0 and 100");
        }
        BigDecimal total = beneficiaries.findByCustomerIdOrderByIdAsc(customerId).stream()
                .map(Beneficiary::getSharePct).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.add(r.sharePct()).compareTo(new BigDecimal("100")) > 0) {
            throw ApiException.rule("Total beneficiary share would exceed 100% (current " + total + "%)");
        }
        Beneficiary b = new Beneficiary();
        b.setCustomerId(customerId);
        b.setFullName(r.fullName());
        b.setRelationship(r.relationship());
        b.setSharePct(r.sharePct());
        b.setDateOfBirth(r.dateOfBirth());
        beneficiaries.save(b);
        audit.log(actor, "BENEFICIARY_ADD", "CUSTOMER", customerId, b.getFullName());
        return b;
    }

    @Transactional
    public void deleteBeneficiary(Long customerId, Long beneficiaryId, String actor) {
        Beneficiary b = beneficiaries.findById(beneficiaryId)
                .filter(x -> x.getCustomerId().equals(customerId))
                .orElseThrow(() -> ApiException.notFound("Beneficiary", beneficiaryId));
        beneficiaries.delete(b);
        audit.log(actor, "BENEFICIARY_DELETE", "CUSTOMER", customerId, String.valueOf(beneficiaryId));
    }

    @Transactional
    public CustomerDocument addDocument(Long customerId, DocumentRequest r, String actor) {
        get(customerId);
        if (r.docType() == null || r.docType().isBlank()) {
            throw ApiException.validation("Document type is required");
        }
        if (r.expiryDt() != null && r.expiryDt().isBefore(LocalDate.now())) {
            throw ApiException.rule("Document is already expired");
        }
        CustomerDocument d = new CustomerDocument();
        d.setCustomerId(customerId);
        d.setDocType(r.docType());
        d.setDocNumber(r.docNumber());
        d.setFileName(r.fileName());
        d.setIssueDt(r.issueDt());
        d.setExpiryDt(r.expiryDt());
        documents.save(d);
        audit.log(actor, "DOC_UPLOAD", "CUSTOMER", customerId, d.getDocType());
        return d;
    }

    @Transactional
    public CustomerDocument reviewDocument(Long customerId, Long documentId, String status, String note, String actor) {
        if (!List.of("VERIFIED", "REJECTED").contains(status)) {
            throw ApiException.validation("Review status must be VERIFIED or REJECTED");
        }
        CustomerDocument d = documents.findById(documentId)
                .filter(x -> x.getCustomerId().equals(customerId))
                .orElseThrow(() -> ApiException.notFound("Document", documentId));
        d.setStatus(status);
        d.setReviewNote(note);
        d.setReviewedBy(actor);
        d.setReviewedDt(LocalDateTime.now());
        audit.log(actor, "DOC_REVIEW", "CUSTOMER", customerId, d.getDocType() + "=" + status);
        return d;
    }

    public List<String> missingKycDocs(Customer c) {
        List<String> mandatory = jdbc.queryForList(
                "SELECT doc_type FROM kyc_checklist WHERE customer_type=? AND mandatory='Y' ORDER BY doc_type",
                String.class, c.getCustomerType());
        Set<String> verified = new HashSet<>();
        LocalDate today = LocalDate.now();
        for (CustomerDocument d : documents.findByCustomerIdOrderByIdAsc(c.getId())) {
            if ("VERIFIED".equals(d.getStatus()) && (d.getExpiryDt() == null || !d.getExpiryDt().isBefore(today))) {
                verified.add(d.getDocType());
            }
        }
        return mandatory.stream().filter(m -> !verified.contains(m)).toList();
    }

    @Transactional
    public Map<String, Object> openKycCase(Long customerId, String actor) {
        Customer c = get(customerId);
        Integer open = jdbc.queryForObject("SELECT count(*) FROM kyc_case WHERE customer_id=? AND status='OPEN'", Integer.class, customerId);
        if (open != null && open > 0) {
            throw ApiException.rule("A KYC case is already open");
        }
        jdbc.update("INSERT INTO kyc_case (customer_id) VALUES (?)", customerId);
        audit.log(actor, "KYC_OPEN", "CUSTOMER", customerId, null);
        return Map.of("customerId", customerId, "missing", missingKycDocs(c));
    }

    @Transactional
    public Customer decideKyc(Long customerId, boolean approve, String note, String actor) {
        Customer c = get(customerId);
        if (approve) {
            List<String> missing = missingKycDocs(c);
            if (!missing.isEmpty()) {
                throw new ApiException(com.legacyins.insurance.common.ErrorCode.BUSINESS_RULE,
                        "Mandatory KYC documents not verified", missing);
            }
        }
        String caseStatus = approve ? "APPROVED" : "REJECTED";
        int updated = jdbc.update("UPDATE kyc_case SET status=?, closed_dt=now(), decided_by=?, decision_note=? WHERE customer_id=? AND status='OPEN'",
                caseStatus, actor, note, customerId);
        if (updated == 0) {
            jdbc.update("INSERT INTO kyc_case (customer_id, status, closed_dt, decided_by, decision_note) VALUES (?,?,now(),?,?)",
                    customerId, caseStatus, actor, note);
        }
        c.setKycStatus(approve ? "VERIFIED" : "REJECTED");
        c.setUpdatedDt(LocalDateTime.now());
        audit.log(actor, "KYC_DECISION", "CUSTOMER", customerId, caseStatus);
        return c;
    }

    private void apply(Customer c, CustomerRequest r) {
        if (r.customerType() != null) {
            c.setCustomerType(r.customerType());
        }
        c.setFirstName(trim(r.firstName()));
        c.setLastName(trim(r.lastName()));
        c.setCompanyName(trim(r.companyName()));
        c.setNationalId(trim(r.nationalId()));
        c.setDateOfBirth(r.dateOfBirth());
        c.setGender(trim(r.gender()));
        c.setEmail(trim(r.email()));
        c.setPhone(trim(r.phone()));
        c.setOccupationCode(trim(r.occupationCode()));
        if (r.riskClass() != null && !r.riskClass().isBlank()) {
            c.setRiskClass(r.riskClass());
        }
        if (r.segment() != null && !r.segment().isBlank()) {
            c.setSegment(r.segment());
        }
        c.setNormName(normalize(c.displayName()));
        c.setNormNid(c.getNationalId() == null ? null : c.getNationalId().replaceAll("[^A-Za-z0-9]", "").toUpperCase());
    }

    private void validate(Customer c) {
        List<String> errors = new ArrayList<>();
        if (!List.of("INDIVIDUAL", "CORPORATE").contains(c.getCustomerType())) {
            errors.add("customerType must be INDIVIDUAL or CORPORATE");
        }
        if ("INDIVIDUAL".equals(c.getCustomerType())) {
            if (c.getFirstName() == null || c.getLastName() == null) {
                errors.add("firstName and lastName are required");
            }
            if (c.getDateOfBirth() == null) {
                errors.add("dateOfBirth is required");
            } else if (c.getDateOfBirth().isAfter(LocalDate.now())) {
                errors.add("dateOfBirth cannot be in the future");
            } else if (c.getDateOfBirth().isBefore(LocalDate.now().minusYears(120))) {
                errors.add("dateOfBirth is not plausible");
            }
        } else if (c.getCompanyName() == null) {
            errors.add("companyName is required for corporate customers");
        }
        if (c.getEmail() != null && !c.getEmail().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            errors.add("email is invalid");
        }
        if (c.getPhone() != null && !c.getPhone().matches("^[+0-9 ()-]{6,20}$")) {
            errors.add("phone is invalid");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(com.legacyins.insurance.common.ErrorCode.VALIDATION, "Customer validation failed", errors);
        }
    }

    static String normalize(String s) {
        return s == null ? null : s.toLowerCase().replaceAll("[^a-z0-9@. ]", "").replaceAll("\\s+", " ").trim();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
