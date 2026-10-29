-- Reference data: roles, users (password "password"), permissions/limits, products & rating,
-- underwriting tables, billing config, claim catalogue, workflows, templates, cancel reasons, jobs.

INSERT INTO user_role (role_id, role_name, description, security_level) VALUES
 (1, 'ADMIN', 'Administrator', 9),
 (2, 'CSR', 'Customer Service Rep', 2),
 (3, 'UNDERWRITER', 'Underwriter', 4),
 (4, 'ADJUSTER', 'Claims Adjuster', 3),
 (5, 'FINANCE', 'Finance Officer', 5),
 (6, 'MANAGER', 'Claims Manager', 6);

INSERT INTO user_account (user_id, username, password_hash, full_name, email, role_id, branch_cd) VALUES
 (1, 'admin', '$2a$10$Y.T8fIGv60Eh829tfQ66IeC/1cfnR7m0DvDSnVbNKjfYFMWgnkdMu', 'System Administrator', 'admin@legacyins.example', 1, 'HQ'),
 (2, 'csr1',  '$2a$10$Y.T8fIGv60Eh829tfQ66IeC/1cfnR7m0DvDSnVbNKjfYFMWgnkdMu', 'Carla Service', 'csr1@legacyins.example', 2, 'NYC'),
 (3, 'uw1',   '$2a$10$Y.T8fIGv60Eh829tfQ66IeC/1cfnR7m0DvDSnVbNKjfYFMWgnkdMu', 'Uma Writer', 'uw1@legacyins.example', 3, 'HQ'),
 (4, 'adj1',  '$2a$10$Y.T8fIGv60Eh829tfQ66IeC/1cfnR7m0DvDSnVbNKjfYFMWgnkdMu', 'Adam Adjuster', 'adj1@legacyins.example', 4, 'NYC'),
 (5, 'adj2',  '$2a$10$Y.T8fIGv60Eh829tfQ66IeC/1cfnR7m0DvDSnVbNKjfYFMWgnkdMu', 'Alice Adjuster', 'adj2@legacyins.example', 4, 'CHI'),
 (6, 'fin1',  '$2a$10$Y.T8fIGv60Eh829tfQ66IeC/1cfnR7m0DvDSnVbNKjfYFMWgnkdMu', 'Frank Finance', 'fin1@legacyins.example', 5, 'HQ'),
 (7, 'mgr1',  '$2a$10$Y.T8fIGv60Eh829tfQ66IeC/1cfnR7m0DvDSnVbNKjfYFMWgnkdMu', 'Maria Manager', 'mgr1@legacyins.example', 6, 'HQ');
SELECT setval(pg_get_serial_sequence('user_account', 'user_id'), 1000);

INSERT INTO role_approval_limit (role_id, limit_type, max_amount) VALUES
 (4, 'CLAIM', 10000.00), (6, 'CLAIM', 100000.00), (5, 'CLAIM', 250000.00), (1, 'CLAIM', 1000000.00),
 (5, 'REFUND', 50000.00), (6, 'REFUND', 20000.00), (1, 'REFUND', 1000000.00);

INSERT INTO kyc_checklist (customer_type, doc_type, mandatory) VALUES
 ('INDIVIDUAL', 'NATIONAL_ID', 'Y'), ('INDIVIDUAL', 'PROOF_ADDRESS', 'Y'), ('INDIVIDUAL', 'INCOME', 'N'),
 ('CORPORATE', 'REGISTRATION', 'Y'), ('CORPORATE', 'PROOF_ADDRESS', 'Y');

INSERT INTO insurance_product (product_id, product_code, product_name, line_of_business, description, base_rate,
    status, min_age, max_age, min_sum_insured, max_sum_insured, min_term_months, max_term_months) VALUES
 (1, 'LIFE-T20', 'Term Life 20', 'LIFE', '20-year level term life cover', 1.2000, 'A', 18, 65, 50000, 5000000, 12, 240),
 (2, 'HLTH-STD', 'Health Standard', 'HEALTH', 'Standard individual health cover', 25.0000, 'A', 0, 75, 5000, 500000, 12, 12),
 (3, 'MOTR-COMP', 'Motor Comprehensive', 'MOTOR', 'Comprehensive private motor cover', 32.0000, 'A', 18, 85, 2000, 250000, 12, 12),
 (4, 'HOME-PRO', 'Home Protect', 'HOME', 'Buildings and contents cover', 2.5000, 'A', 18, 99, 20000, 3000000, 12, 12);
SELECT setval(pg_get_serial_sequence('insurance_product', 'product_id'), 1000);

INSERT INTO rate_base (product_id, age_from, age_to, risk_class, rate_per_1000) VALUES
 (1, 18, 30, 'PREF', 0.6500), (1, 18, 30, 'STD', 0.8000), (1, 18, 30, 'SUB', 1.2000), (1, 18, 30, 'HIGH', 1.8000),
 (1, 31, 45, 'PREF', 1.0500), (1, 31, 45, 'STD', 1.3000), (1, 31, 45, 'SUB', 1.9500), (1, 31, 45, 'HIGH', 2.9000),
 (1, 46, 65, 'PREF', 2.6000), (1, 46, 65, 'STD', 3.2000), (1, 46, 65, 'SUB', 4.8000), (1, 46, 65, 'HIGH', 7.0000),
 (2, 0, 17, 'STD', 18.0000), (2, 18, 40, 'STD', 22.0000), (2, 41, 60, 'STD', 34.0000), (2, 61, 75, 'STD', 52.0000),
 (2, 18, 40, 'PREF', 19.0000), (2, 41, 60, 'SUB', 45.0000),
 (3, 18, 25, 'STD', 48.0000), (3, 26, 65, 'STD', 30.0000), (3, 66, 85, 'STD', 38.0000), (3, 26, 65, 'PREF', 26.0000),
 (4, 18, 99, 'STD', 2.5000), (4, 18, 99, 'PREF', 2.1000), (4, 18, 99, 'HIGH', 3.6000);

INSERT INTO rating_rule (product_id, rule_order, rule_name, rule_kind, condition_field, condition_op, condition_value, amount_type, amount) VALUES
 (1, 10, 'Smoker loading', 'LOADING', 'SMOKER', 'EQ', 'Y', 'PCT', 50.0000),
 (1, 20, 'Hazardous occupation surcharge', 'SURCHARGE', 'OCC_CLASS', 'GTE', '3', 'PCT', 25.0000),
 (1, 30, 'High sum insured discount', 'DISCOUNT', 'SUM_INSURED', 'GTE', '1000000', 'PCT', 10.0000),
 (1, 90, 'Policy fee', 'FEE', 'ANY', 'ANY', NULL, 'FLAT', 25.0000),
 (1, 99, 'Premium tax', 'TAX', 'ANY', 'ANY', NULL, 'PCT', 2.0000),
 (2, 10, 'Smoker loading', 'LOADING', 'SMOKER', 'EQ', 'Y', 'PCT', 30.0000),
 (2, 90, 'Policy fee', 'FEE', 'ANY', 'ANY', NULL, 'FLAT', 15.0000),
 (2, 99, 'Premium tax', 'TAX', 'ANY', 'ANY', NULL, 'PCT', 5.0000),
 (3, 10, 'Young driver loading', 'LOADING', 'AGE', 'LT', '25', 'PCT', 35.0000),
 (3, 90, 'Policy fee', 'FEE', 'ANY', 'ANY', NULL, 'FLAT', 20.0000),
 (3, 99, 'Premium tax', 'TAX', 'ANY', 'ANY', NULL, 'PCT', 8.0000),
 (4, 10, 'High value home surcharge', 'SURCHARGE', 'SUM_INSURED', 'GT', '1500000', 'PCT', 15.0000),
 (4, 90, 'Policy fee', 'FEE', 'ANY', 'ANY', NULL, 'FLAT', 20.0000),
 (4, 99, 'Premium tax', 'TAX', 'ANY', 'ANY', NULL, 'PCT', 6.0000);

INSERT INTO rounding_rule (product_id, scale_digits, rounding_mode, min_premium) VALUES
 (1, 2, 'HALF_UP', 100.00), (2, 2, 'HALF_UP', 150.00), (3, 0, 'NEAREST_5', 200.00), (4, 2, 'UP', 120.00);

INSERT INTO product_eligibility_rule (product_id, rule_type, rule_value, message) VALUES
 (1, 'KYC_REQUIRED', 'Y', 'Customer KYC must be verified for life cover'),
 (1, 'MAX_OPEN_CLAIMS', '0', 'Customer has open claims'),
 (2, 'MAX_OPEN_CLAIMS', '2', 'Too many open claims'),
 (3, 'BLOCKED_STATUS', 'B', 'Blocked customers are not eligible'),
 (4, 'BLOCKED_STATUS', 'B', 'Blocked customers are not eligible');

INSERT INTO billing_product_config (product_id, late_fee_pct, late_fee_flat, grace_days, max_retries, refund_scale, cancel_fee_pct) VALUES
 (1, 2.000, 0, 30, 3, 'PRORATA', 10.000),
 (2, 1.500, 5.00, 30, 3, 'PRORATA', 10.000),
 (3, 0.000, 15.00, 15, 2, 'SHORTRATE', 0.000),
 (4, 2.000, 0, 30, 3, 'PRORATA', 10.000);

INSERT INTO uw_occupation (occ_code, description, risk_class, points) VALUES
 ('OFFICE', 'Office / clerical', 1, 0), ('TEACHER', 'Teacher', 1, 0), ('ENGINEER', 'Engineer', 1, 0),
 ('DRIVER', 'Professional driver', 2, 10), ('NURSE', 'Nurse', 2, 5), ('CONSTRUCT', 'Construction worker', 3, 25),
 ('MINER', 'Miner', 4, 45), ('PILOT', 'Commercial pilot', 3, 30), ('STUDENT', 'Student', 1, 0), ('RETIRED', 'Retired', 1, 0);

INSERT INTO uw_medical_code (med_code, description, points, decline_flag) VALUES
 ('HTN', 'Hypertension (controlled)', 15, 'N'), ('DM2', 'Type 2 diabetes', 30, 'N'),
 ('ASTH', 'Asthma', 10, 'N'), ('CAD', 'Coronary artery disease', 60, 'N'),
 ('CANC_ACT', 'Active cancer treatment', 0, 'Y'), ('HIV', 'HIV positive', 40, 'N');

INSERT INTO uw_rule (rule_code, field, op, threshold, action, points, message) VALUES
 ('AGE_OVER_60', 'AGE', 'GT', '60', 'POINTS', 20, 'Applicant over 60'),
 ('SMOKER', 'SMOKER', 'EQ', 'Y', 'POINTS', 15, 'Smoker'),
 ('HIGH_SI', 'SUM_INSURED', 'GT', '2000000', 'REFER', 0, 'Sum insured above auto-acceptance limit'),
 ('OCC_CLASS_4', 'OCC_CLASS', 'GTE', '4', 'REFER', 0, 'Occupation class 4 requires referral'),
 ('AGE_OVER_70', 'AGE', 'GT', '70', 'DECLINE', 0, 'Applicant age exceeds underwriting maximum');

INSERT INTO uw_authority (level_no, description, max_sum_insured) VALUES
 (1, 'Junior underwriter', 1000000), (2, 'Senior underwriter', 3000000), (3, 'Chief underwriter', 99999999);
INSERT INTO uw_reviewer (user_id, level_no) VALUES (3, 2), (1, 3);

INSERT INTO claim_category (category_code, line_of_business, description, waiting_days, reporting_days, sla_days, deductible, coverage_pct, excluded_causes) VALUES
 ('DEATH', 'LIFE', 'Death benefit', 0, 365, 30, 0, 100, 'SUICIDE_Y1'),
 ('TERMINAL', 'LIFE', 'Terminal illness', 90, 180, 30, 0, 100, NULL),
 ('HOSPITAL', 'HEALTH', 'Hospitalisation', 30, 60, 14, 250, 90, 'COSMETIC'),
 ('OUTPATIENT', 'HEALTH', 'Outpatient treatment', 30, 30, 7, 50, 80, 'COSMETIC'),
 ('COLLISION', 'MOTOR', 'Collision damage', 0, 30, 14, 500, 100, 'DUI,RACING'),
 ('THEFT', 'MOTOR', 'Vehicle theft', 0, 7, 21, 1000, 100, 'KEYS_LEFT'),
 ('FIRE', 'HOME', 'Fire damage', 0, 30, 21, 1000, 100, 'ARSON_INSURED'),
 ('WATER', 'HOME', 'Water damage', 30, 30, 14, 500, 100, 'GRADUAL_LEAK'),
 ('BURGLARY', 'HOME', 'Burglary / contents theft', 0, 14, 14, 250, 100, NULL);

INSERT INTO workflow_definition (def_code, subject_type, description, four_eyes, sla_hours) VALUES
 ('CLAIM_STD', 'CLAIM', 'Standard claim approval', 'S', 48),
 ('UW_REFERRAL', 'UW_REFERRAL', 'Underwriting referral review', 'Y', 72),
 ('CANCELLATION', 'CANCELLATION', 'Policy cancellation approval', 'Y', 48),
 ('REFUND', 'REFUND', 'Refund approval', 'Y', 24),
 ('RENEWAL_EXCEPTION', 'RENEWAL_EXCEPTION', 'Renewal re-underwriting exception', 'Y', 72);

INSERT INTO workflow_step (def_code, step_no, step_name, role_code, min_amount) VALUES
 ('CLAIM_STD', 1, 'Claims manager approval', 'MANAGER', 0),
 ('CLAIM_STD', 2, 'Finance sign-off', 'FINANCE', 50000),
 ('UW_REFERRAL', 1, 'Underwriter review', 'UNDERWRITER', 0),
 ('CANCELLATION', 1, 'Manager approval', 'MANAGER', 0),
 ('CANCELLATION', 2, 'Finance approval', 'FINANCE', 10000),
 ('REFUND', 1, 'Finance approval', 'FINANCE', 0),
 ('RENEWAL_EXCEPTION', 1, 'Underwriter review', 'UNDERWRITER', 0);

INSERT INTO notification_template (template_code, channel, subject_tpl, body_tpl) VALUES
 ('POLICY_ISSUED', 'EMAIL', 'Your policy {policyNo} is active', 'Dear {name}, your policy {policyNo} is now active from {startDt} to {endDt}. Annual premium: {premium}.'),
 ('PAYMENT_RECEIVED', 'EMAIL', 'Payment received - {receiptNo}', 'Dear {name}, we received {amount} for policy {policyNo}. Receipt: {receiptNo}.'),
 ('PAYMENT_FAILED', 'EMAIL', 'Payment failed for {policyNo}', 'Dear {name}, your payment of {amount} for policy {policyNo} failed: {reason}.'),
 ('POLICY_GRACE', 'EMAIL', 'Premium overdue - {policyNo}', 'Dear {name}, policy {policyNo} has an overdue premium and is now in its grace period.'),
 ('POLICY_LAPSED', 'EMAIL', 'Policy lapsed - {policyNo}', 'Dear {name}, policy {policyNo} has lapsed due to non-payment.'),
 ('CLAIM_REGISTERED', 'EMAIL', 'Claim {claimNo} registered', 'Dear {name}, your claim {claimNo} has been registered and will be reviewed.'),
 ('CLAIM_DECIDED', 'EMAIL', 'Claim {claimNo} decision', 'Dear {name}, your claim {claimNo} status is now {status}.'),
 ('CLAIM_SETTLED', 'EMAIL', 'Claim {claimNo} settled', 'Dear {name}, {amount} has been paid for claim {claimNo}.'),
 ('RENEWAL_OFFER', 'EMAIL', 'Renewal offer for {policyNo}', 'Dear {name}, your renewal premium for {policyNo} is {premium}. Offer valid until {expires}.'),
 ('POLICY_CANCELLED', 'EMAIL', 'Policy {policyNo} cancelled', 'Dear {name}, policy {policyNo} was cancelled effective {effectiveDt}. Refund: {refund}.'),
 ('TASK_ASSIGNED', 'INAPP', 'New approval task', 'A new {defCode} task is waiting for role {role}: {subjectRef}.');

INSERT INTO lc_cancel_reason (reason_code, description, cancel_type, refundable) VALUES
 ('CUST_REQUEST', 'Customer request', 'INSURED', 'Y'),
 ('SOLD_ASSET', 'Insured asset sold', 'INSURED', 'Y'),
 ('FOUND_CHEAPER', 'Found cheaper cover', 'INSURED', 'Y'),
 ('FRAUD', 'Misrepresentation / fraud', 'INSURER', 'N'),
 ('RISK_CHANGE', 'Material change in risk', 'INSURER', 'Y'),
 ('NON_PAYMENT', 'Non-payment of premium', 'NONPAY', 'N');

INSERT INTO batch_job_def (job_name, description, schedule_type, schedule_spec, run_order, max_runtime_min) VALUES
 ('EXPIRY', 'Expire ended policies, lapse stale renewal offers, auto-renew', 'DAILY', '01:30', 10, 30),
 ('OVERDUE', 'Mark overdue installments', 'DAILY', '02:00', 20, 45),
 ('BILLING_LATE_FEE', 'Late fees per product configuration', 'DAILY', '02:10', 22, 30),
 ('BILLING_LAPSE', 'Grace and lapse policies past grace period', 'DAILY', '02:15', 23, 30),
 ('BILLING_RETRY', 'Retry failed automatic payments', 'DAILY', '02:30', 25, 30),
 ('RENEWAL_OFFERS', 'Generate renewal offers for policies nearing expiry', 'DAILY', '03:00', 30, 30),
 ('CLAIM_SLA_SWEEP', 'Escalate claims past SLA due date', 'DAILY', '04:05', 41, 20),
 ('WORKFLOW_SLA', 'Approval task SLA breaches', 'DAILY', '04:10', 44, 20),
 ('RECONCILIATION', 'Cross-check payments, ledger, schedules and payouts', 'DAILY', '04:30', 50, 30),
 ('REPORT_REFRESH', 'Rebuild RPT_* summary tables', 'DAILY', '05:00', 60, 20),
 ('OUTBOX_FLUSH', 'Deliver queued notifications', 'EVERY', '15', 90, 10),
 ('DATA_PURGE', 'Purge aged session logs and delivered notifications', 'WEEKLY', 'SUN 23:00', 99, 120);
