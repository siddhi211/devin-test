#!/usr/bin/env bash
# End-to-end API smoke test against a running backend (default http://localhost:8080).
set -euo pipefail
API=${API:-http://localhost:8080/api}
PASSWORD=${DEMO_PASSWORD:-password}
TODAY=$(date +%F)
fail() { echo "FAIL: $*" >&2; exit 1; }
login() { curl -sf -X POST "$API/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PASSWORD\"}" | jq -r .token; }
call() { local tok=$1 method=$2 path=$3 body=${4:-}
  if [ -n "$body" ]; then curl -s -X "$method" "$API$path" -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' -d "$body"
  else curl -s -X "$method" "$API$path" -H "Authorization: Bearer $tok"; fi; }
expect() { local got; got=$(jq -r "$2" <<<"$1"); [ "$got" = "$3" ] || fail "$4: expected $3, got $got :: $1"; echo "ok  - $4"; }

ADMIN=$(login admin); CSR=$(login csr1); UW=$(login uw1); ADJ=$(login adj1); FIN=$(login fin1); MGR=$(login mgr1)
[ "$ADMIN" != null ] || fail "admin login"
echo "ok  - logins"

r=$(curl -s -X POST "$API/auth/login" -H 'Content-Type: application/json' -d '{"username":"admin","password":"wrong"}')
expect "$r" .code AUTH_FAILED "bad password rejected with AUTH_FAILED"
r=$(call "$CSR" GET /admin/users); expect "$r" .code NOT_AUTHORIZED "CSR blocked from admin API"

SUFFIX=$RANDOM$RANDOM
r=$(call "$CSR" POST /customers "{\"customerType\":\"INDIVIDUAL\",\"firstName\":\"Smoke\",\"lastName\":\"Test$SUFFIX\",\"nationalId\":\"NID$SUFFIX\",\"dateOfBirth\":\"1985-04-12\",\"email\":\"smoke$SUFFIX@example.com\",\"phone\":\"5551234\",\"occupationCode\":\"OFFICE\"}")
CUST=$(jq -r .id <<<"$r"); [ "$CUST" != null ] || fail "create customer: $r"; echo "ok  - customer $CUST"
for d in NATIONAL_ID PROOF_ADDRESS; do
  doc=$(call "$CSR" POST /customers/$CUST/documents "{\"docType\":\"$d\",\"docNumber\":\"D$SUFFIX\",\"fileName\":\"$d.pdf\",\"expiryDt\":\"2035-01-01\"}" | jq -r .id)
  call "$UW" POST /customers/$CUST/documents/$doc/review '{"status":"VERIFIED","note":"ok"}' >/dev/null
done
call "$CSR" POST /customers/$CUST/kyc/open >/dev/null
r=$(call "$UW" POST /customers/$CUST/kyc/decision '{"approve":true,"note":"docs verified"}'); expect "$r" .kycStatus VERIFIED "KYC verified"

r=$(call "$CSR" POST /quotes "{\"productId\":4,\"customerId\":$CUST,\"sumInsured\":200000,\"termMonths\":12,\"paymentFrequency\":\"QUARTERLY\",\"riskClass\":\"STD\"}")
[ "$(jq -r .annualPremium <<<"$r")" != null ] || fail "quote: $r"; echo "ok  - quote $(jq -r .annualPremium <<<"$r")"

r=$(call "$CSR" POST /policies "{\"customerId\":$CUST,\"productId\":4,\"sumInsured\":200000,\"termMonths\":12,\"paymentFrequency\":\"QUARTERLY\",\"riskClass\":\"STD\",\"startDt\":\"$TODAY\"}")
POL=$(jq -r .id <<<"$r"); expect "$r" .status DRAFT "policy created"
r=$(call "$CSR" POST /policies/$POL/submit); expect "$r" .policy.status UW_APPROVED "auto underwriting approval"
r=$(call "$CSR" POST /policies/$POL/issue); expect "$r" .status ACTIVE "policy issued"

r=$(call "$CSR" GET /billing/policies/$POL); INST=$(jq -r '.schedule[0].amount_due' <<<"$r")
[ "$(jq '.schedule|length' <<<"$r")" = 4 ] || fail "expected 4 quarterly installments: $r"; echo "ok  - schedule generated ($INST/qtr)"
r=$(call "$FIN" POST /billing/policies/$POL/payments "{\"amount\":$INST,\"method\":\"CARD\",\"instrumentRef\":\"4111111111110002\",\"idempotencyKey\":\"smk-d-$SUFFIX\"}")
expect "$r" .status FAILED "card 0002 declined"
BODY="{\"amount\":$INST,\"method\":\"CARD\",\"instrumentRef\":\"4111111111111111\",\"idempotencyKey\":\"smk-$SUFFIX\"}"
r=$(call "$FIN" POST /billing/policies/$POL/payments "$BODY"); expect "$r" .status POSTED "payment posted"
PAY=$(jq -r .paymentId <<<"$r")
r=$(call "$FIN" POST /billing/policies/$POL/payments "$BODY"); expect "$r" .paymentId "$PAY" "idempotent replay"
r=$(call "$FIN" POST /billing/policies/$POL/payments "{\"amount\":1,\"method\":\"CASH\",\"idempotencyKey\":\"smk-$SUFFIX\"}")
expect "$r" .code DUPLICATE "idempotency key conflict"
r=$(call "$FIN" GET /billing/trial-balance); expect "$r" '([.[].debit]|add) == ([.[].credit]|add)' true "ledger balanced"

CAT=$(call "$ADJ" GET /reference/claim-categories | jq -r '[.[]|select(.line_of_business=="HOME" and .waiting_days==0)][0].category_code')
r=$(call "$CSR" POST /claims "{\"policyId\":$POL,\"categoryCode\":\"$CAT\",\"incidentDt\":\"$TODAY\",\"description\":\"Burst pipe\",\"claimedAmount\":30000}")
CLM=$(jq -r .claim.id <<<"$r"); expect "$r" .claim.status REGISTERED "claim registered ($CAT)"
r=$(call "$MGR" POST /claims/$CLM/assign '{}'); expect "$r" .status ASSIGNED "claim auto-assigned"
r=$(call "$ADJ" POST /claims/$CLM/assess '{"excludedAmount":1000,"depreciationPct":10}'); expect "$r" .claim.status ASSESSED "claim assessed"
r=$(call "$ADJ" POST /claims/$CLM/decide '{"decision":"APPROVE","reason":"covered"}'); expect "$r" .routedToApproval true "over-limit claim routed to approval"
TASK=$(call "$MGR" GET /workflow/inbox | jq -r "[.[]|select(.subject_type==\"CLAIM\" and .subject_id==$CLM)][0].task_id")
r=$(call "$ADJ" POST /workflow/tasks/$TASK/decide '{"decision":"APPROVE","note":"self"}'); expect "$r" .code NOT_AUTHORIZED "four-eyes blocks requester / role"
r=$(call "$MGR" POST /workflow/tasks/$TASK/decide '{"decision":"APPROVE","note":"approved"}')
r=$(call "$MGR" GET /claims/$CLM); expect "$r" .claim.status APPROVED "manager approved claim"
r=$(call "$FIN" POST /claims/$CLM/settle '{"method":"TRANSFER"}'); expect "$r" .claim.status SETTLED "claim settled"

r=$(call "$CSR" POST /lifecycle/cancellations/preview "{\"policyId\":$POL,\"cancelType\":\"INSURED\",\"reasonCode\":\"CUST_REQUEST\"}")
expect "$r" .coolingOff true "cooling-off preview"
r=$(call "$ADMIN" POST /reconciliation/run); echo "ok  - reconciliation $(jq -c . <<<"$r")"
r=$(call "$ADMIN" POST /batch/jobs/OUTBOX_FLUSH/run); expect "$r" .status SUCCESS "outbox flush job"
r=$(call "$FIN" GET "/reports/POLICY_REGISTER"); [ "$(jq .rowCount <<<"$r")" -ge 1 ] || fail "report: $r"; echo "ok  - policy register report"
r=$(call "$ADMIN" GET /dashboard); [ "$(jq .openTasks <<<"$r")" != null ] || fail "dashboard"; echo "ok  - dashboard"
echo "SMOKE PASSED"
