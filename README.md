# Insurance Management System

Modern rewrite of the legacy insurance management system: **Spring Boot 3 (Java 17)** backend, **React + TypeScript (Vite)** frontend, **PostgreSQL 16** database.

## Modules

| Area | Backend package / API | Frontend page |
|---|---|---|
| Auth, users, audit, sessions (JWT, lockout after failed logins) | `auth` — `/api/auth`, `/api/admin` | Login, Users & Audit |
| Customers, KYC, documents, addresses, beneficiaries, duplicates/merge | `customer` — `/api/customers` | Customers |
| Product catalogue, table-driven rating, quotes, underwriting reference | `product` — `/api/products`, `/api/quotes`, `/api/reference` | Products & Quotes |
| Policy lifecycle (draft → submit → UW → issue), endorsements, versions | `policy` — `/api/policies` | Policies |
| Billing schedules, payments (idempotent, gateway simulator), late fees, grace/lapse, refunds, double-entry ledger | `billing` — `/api/billing` | Billing |
| Claims FNOL, eligibility, fraud flags, assignment, assessment, decision, settlement, reopen, SLA | `claims` — `/api/claims` | Claims |
| Workflow engine (role inboxes, four-eyes, delegation, SLA) | `workflow` — `/api/workflow` | My Tasks |
| Notification outbox (templates, dedup, retry, dead-letter) | `notification` — `/api/notifications` | Notifications |
| Renewals (NCB, claims loading), cancellations (pro-rata/short-rate, cooling-off), reinstatement | `lifecycle` — `/api/lifecycle` | Renewals & Cancellations |
| Reports (JSON/CSV), dashboard, saved filters, reconciliation | `reporting` — `/api/reports`, `/api/dashboard`, `/api/reconciliation` | Dashboard, Reports, Reconciliation |
| Batch jobs with DB lease locks, run history, item errors, nightly scheduler | `batch` — `/api/batch` | Batch Jobs |

Errors use the legacy taxonomy: `{"status":"ERROR","code":"VALIDATION|NOT_FOUND|DUPLICATE|BUSINESS_RULE|AUTH_FAILED|NOT_AUTHORIZED|SESSION_EXPIRED|SYSTEM","message":...,"details":[...]}`.

Schema lives in Flyway migrations under `backend/src/main/resources/db/migration` (`V001`–`V090`); Hibernate runs with `ddl-auto: validate`.

## Run with Docker Compose

```bash
docker compose up --build
```

Frontend: http://localhost:3000 · API: http://localhost:8080 · Swagger UI: http://localhost:8080/swagger-ui.html

## Run locally (dev)

```bash
docker run -d --name insurance-pg -e POSTGRES_DB=insurance -e POSTGRES_USER=insurance -e POSTGRES_PASSWORD=insurance -p 5432:5432 postgres:16
cd backend && mvn spring-boot:run          # http://localhost:8080
cd frontend && npm install && npm run dev  # http://localhost:5173 (proxies /api to :8080)
```

Configuration via environment variables: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `JWT_TTL_MINUTES`, `CORS_ORIGINS`, `APP_BATCH_SCHEDULER_ENABLED`. **Set a real `JWT_SECRET` and DB password outside development.**

## Demo users

Seeded by `V090__reference_data.sql`, all with password `password` (development only — change or remove before any shared deployment):

| User | Role |
|---|---|
| `admin` | ADMIN |
| `csr1` | CSR |
| `uw1` | UNDERWRITER |
| `adj1`, `adj2` | ADJUSTER |
| `fin1` | FINANCE |
| `mgr1` | MANAGER (claims manager / approver) |

Payment gateway simulator: card numbers ending `0002` are declined; others succeed.

## Tests

```bash
cd backend && mvn test           # unit tests
./scripts/smoke.sh               # end-to-end API smoke test against a running backend (needs curl + jq)
cd frontend && npm run lint && npm run build
```
