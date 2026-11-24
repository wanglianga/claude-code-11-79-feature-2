# APEX-3 · Dual-Domain Sync & Cross-Border Egress Control

A platform for a multinational clinical trial that keeps China source records resident locally while letting a
global team use **de-identified derived data** only inside an approved purpose, field, site, observation-window and
protocol-version envelope. It implements the consent ledger, protocol catalogue, dual-domain eligibility
evaluator, watermark/back-fill coordination, snapshot delivery compiler, withdrawal propagator and post-outage
recovery verifier, together with the acceptance scenario for subjects A/B/C/D at analysis day 16.

On top of that it adds a **cross-source adverse-event episode reconstruction**: the same clinical process may be
reported in a centre safety report, a hospital summary and a lab abnormality, so counting reports inflates the
rate while a wide merge window swallows a post-resolution recurrence. The safety pipeline rebuilds episode
periods per participant and comparable term, counts first and recurrent episodes against **observable treatment
days**, and propagates linkage ambiguity into the rate interval. Identity is resolved only inside a residence
domain; the global side receives only k-anonymised aggregates with uncertainty bands.

Stack: **Vue 3 (Vite)** · **Spring Boot 3.5 / JDK** · **PostgreSQL**. Single page app is bundled into the Spring
Boot jar and served together with the REST API.

---

## 1. Quick start

### Option A — Docker Compose (one command)

```bash
cd /workspace/79-feature-2
docker compose up --build
# open http://localhost:8080   (Log in as admin / demo123)
```

Compose builds the frontend, bundles it into the Spring Boot jar, starts PostgreSQL and the app, and seeds the
demo scenario on first boot. Health check: `curl http://localhost:8080/actuator/health`.

> The provided container in this workspace has **no Docker daemon**, so Compose was not executed here. The same
> image build was validated by running the packaged jar directly (Option B) plus the integration/UI test suites.

### Option B — Run locally (used for the verification below)

Prerequisites already present in this environment: `/opt/jdk-25`, `/opt/maven`, `/opt/node-22`, PostgreSQL on
`127.0.0.1:5432` (user/db `ccdev`), Redis not required.

```bash
# 1. database (already running in the workspace image)
psql "host=127.0.0.1 user=ccdev password=ccdev dbname=postgres" -c "CREATE DATABASE ctrial;"   # once

# 2. build the SPA, bundle it, and build the backend jar
cd frontend && npm install && npm run build && cd ..
cd backend  && mvn -DskipTests package

# 3. run
java -jar backend/target/ctrial-sync-1.0.0.jar
# open http://localhost:8080  — schema and demo data seed automatically on first start
```

Frontend developer mode (hot reload, proxies `/api` to :8080):

```bash
cd frontend && npm run dev      # http://localhost:5173
```

The backend reads `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`, `DEMO_SEED` from the environment
(defaults in `backend/src/main/resources/application.yml`).

---

## 2. Test accounts (HTTP Basic)

All demo passwords are `demo123`. Roles are enforced server-side.

| Username | Role | Can do |
|----------|------|--------|
| `admin`    | ADMIN | everything |
| `global`   | GLOBAL_ANALYST (+AUDITOR) | compile cross-border deliveries, read snapshots, recompute safety episodes, publish any aggregate |
| `china`    | CN_ANALYST | drive watermark / transfer / link operations, recompute safety episodes on the local plane |
| `auditor`  | AUDITOR | read-only audit, recovery, quarantine, safety read views |
| `sponsor`  | SPONSOR | record consent & protocol versions |

Role checks that matter and were exercised: a China analyst gets **403** on `POST /api/deliveries/**`; an auditor
gets **403** on `POST /api/consents`; a sponsor gets **403** on `POST /api/safety/reconstruct` (reconstruction is
an analyst action).

---

## 3. How the domain model maps to the requirements

| Requirement | Where it lives |
|-------------|----------------|
| Consent ledger: subject, purpose, field group, region, validity interval | `ConsentEntry` — two clocks: `effectiveFrom`/`effectiveTo` (local signature) and `recordedAt` (receipt) |
| Consent validity uses the **local** signed/withdrawn instant, not receipt time | `EligibilityEngine.resolveConsent`, `EligibilityService.loadWorld` (filters on `effectiveFrom ≤ analysis`) |
| Protocol catalogue: version, approvals, effective time, applicable sites | `ProtocolVersion.fullyApproved/approvedAt/activeAt/appliesToSite` |
| Amendment applies only from explicit effective date, only after ethics **and** sponsor approval | `EligibilityEngine.resolveProtocol` |
| Local event ledger: stable id, event time, receipt time, revision chain, source, void | `LocalEvent` (unique on `(sourceSystem,domain,sourceSeq)` and `(stableId,revision)`; no direct identifiers) |
| No raw identity leaves China | `Subject.localIdentity` never projected; `DeliveryRecord` has only pseudonym + projected fields |
| Eligibility per record: 本地可用 / 全球可用 / 待定 + reasons | `Eligibility.LOCAL_AVAILABLE / GLOBAL_AVAILABLE / PENDING / BLOCKED` + `reasonCodes` |
| Intersection (not union) of consent, protocol, approval, purpose scope | `EligibilityEngine.evaluate` — sequential gates, any failure blocks, any undeterminable pends |
| Watermark coordinator: source-confirmed / transfer-ack / analysis, per domain | `Watermark` (three counters), `StateService`, `coverage` view |
| Gaps are UNCONFIRMED, never zero | `WATERMARK_UNCONFIRMED` reason + analysis cap; UI gap band |
| Snapshot compiler pins versions, member summary, field projection, filter, watermark | `DeliveryService.compile`, `Delivery` |
| Withdrawal propagator stops future queries, applies pre-registered retention/destruction | `RevocationService`, `DispositionRule`, `DispositionExecution`; historical delivery summary kept |
| Recovery verifier: gaps, duplicate re-sends, cross-domain digest agreement | `RecoveryService.run` → `RecoveryCheck` |
| Two reconstruction routes producing identical legal sets | `EligibilityRunService` (MATERIALIZED vs REPLAY) + equivalence test; both call one engine |
| Stale snapshots never authorise a new read; old deliveries not pretended erased | `invalidateRuns`, `Delivery.accessRevoked` with summary/digests intact |
| Retransmission after restore not double-counted | `EventIngestService` transport-identity dedupe + `TransportReceipt` |
| Withdrawal/correction race adjudicated by approved rule, else quarantined | `EventIngestService` + `TieBreakRule` + `QuarantineItem` |

### Reconstruction routes

* **MATERIALIZED** — `POST /api/runs/materialize?day=16` evaluates once and persists one row per candidate. Later
  consent/protocol revisions mark affected runs `STALE`. Queries read the stored rows directly; a stale run is
  never consulted to authorise a read.
* **REPLAY** — `GET /api/runs/replay?day=16` rebuilds from the append-only ledgers at the chosen instant,
  persisting nothing; it requires the watermark completeness proof returned alongside.

Both call the same pure `EligibilityEngine`. The acceptance check `ALL/EQ` and `GET /api/runs/compare?day=16`
assert the two routes produce the same legal membership, verdicts and reason codes. At day 16 both report
**13 legal rows, 0 differences**.

---

## 4. The acceptance scenario (analysis day 16)

Study day 1 = `2026-03-02T09:00Z`; analysis instant for day N = `23:00Z` on that day.
Sites: `CN-01` Shanghai (healthy), `CN-02` Wuhan (link stalled), `SG-01` Singapore (healthy).

| Subject | Timeline | Required outcome |
|---------|----------|------------------|
| **A** | signs China-only consent D10; visit occurs D11; uploaded D15 | **not** global-available (reason = consent scope, not upload delay); locally available |
| **B** | AE occurs D11; withdraws global purpose D12 (signed 09:00, received 20:00); new visit D13 | D11 AE held under restricted retention but reuse globally blocked; D13 visit has no basis; local use continues |
| **C** | protocol v2.0 approved D13, effective D14 at CN-01; imaging D13 and D15 | D13 under v1.2; D15 under v2.0 carrying `IMAGING_ADC`/`IMAGING_TUMOR_VOL`; v2.0 never applies to other sites |
| **D** | CN-02 link stalled after D9; randomisation D5, visit D12 | D5 globally available; D12 **PENDING / UNCONFIRMED** globally, locally available; gap shown, not zero |

If a historical delivery contains B's D11 row (e.g. compile at day 11), propagating B's withdrawal revokes
access to it immediately and applies `DR-RETAIN-25Y` (restricted retention) while the delivery's frozen member
summary and digests stay intact for audit — the delivery is not pretended to have been erased.

---

## 4a. Cross-source safety-event reconstruction (analysis day 26)

Study day 1 = `2026-03-02T09:00Z` (same clock). The safety scenario runs past the day-16 reference because the
recurrence happens on D20 and the next dictionary edition becomes valid on D28. Open **Safety episodes** (its own
analysis-day box defaults to 26; it does not inherit the workspace day-16 toggle).

### Seeded source observations (residence domain `CN` / `SG`)

| Participant | Sources and timing | Expected reconstruction |
|-------------|--------------------|-------------------------|
| **P11** (`L-CN-01-011`) | D3 centre safety report "nausea"; D4 hospital summary "nausea" (overlaps D3); D7 centre report "nausea" **resolved** + treatment interruption; **D20** centre report "nausea" again | D3+D4+D7 collapse to **one index episode**; D20 is a **second, recurrence** episode |
| **P12** (`L-CN-01-012`) | D3 and D4 "nausea", same time window as P11, **different participant** | its own two reports merge; it is **never** linked to P11 |
| **P13** (`L-CN-01-013`) | D5 "rash" (ends D5 18:00) + D5 "dermatitis" (same SOC, different PT) + a D7 follow-up note "rash" filed late, **no time overlap** | deterministic links dermatitis only by SOC via edge but splits the delayed rash note; probabilistic joins the delayed note to the D5 rash |
| **P14** (`L-CN-01-014`) | D6 lab "neutropenia" → D6 centre "febrile neutropenia" (same HLT) | kept as an **ambiguous candidate**, never force-merged |
| **P21/P22/P23** | nausea in CN-01, SG-01, CN-02 | give the nausea term ≥3 participants so it clears the k-anonymity floor and is publishable |

### What the pipeline does

* **Terminology mapping** pins a coding edition (MedDRA 25.1 active; 26.0 valid from D28). A mapping is used only
  when the occurrence falls inside its validity window; an out-of-window occurrence is reported `OUT_OF_VALIDITY`
  and excluded, never silently re-mapped.
* **Episode linker** — *deterministic* clusters on same participant + same preferred term + explicit time overlap
  (easy to audit, but a delayed back-report with a non-overlapping stamp is split in two). *Probabilistic* scores
  term similarity (PT/HLT/SOC), time proximity, resolution (which argues for a **new** episode) and treatment
  interruption (which supports one continuous episode); pairs inside `[0.50, 0.75)` are retained as **candidates**
  and never force-merged.
* **Counter** — the denominator is **observable treatment days** (interval length minus unobservable holds, capped
  at the analysis day), never the nominal span. First (index) and recurrence rates are per 100 observable
  person-days, and the rate interval widens to cover the merge-all / split-reversible recount, so linkage
  ambiguity is propagated into the band.
* **Versioning** — every run writes a new `SafetyEpisodeRun` + immutable `SafetyStatsVersion`. Re-running after a
  mapping change produces a new version that `supersedes` the prior one by id; the prior digest is untouched and
  **no parent-task `Delivery` / `EligibilityRow` is ever written**.
* **Isolation** — participant identity is a hard gate and lives only inside a residence domain. `GET
  /api/safety/observations?domain=CN` returns CN participants only; there is no cross-domain per-participant read.
  The global plane receives only `GET /api/safety/aggregates/**`, where cells below the k-floor (k=3) are
  suppressed and only the total is reported.

### Safety acceptance checks (`GET /api/safety/acceptance?day=26`) — 7/7

| Code | Proves |
|------|--------|
| **E1** | P11 D3+D4+D7 form one index episode (not four reports, not three) |
| **E2** | P11 D20 is a second episode flagged as a recurrence after documented resolution |
| **E3** | the same term/time from P12 is never linked to P11; P12's own reports still merge |
| **X1** | denominator = observable treatment days (P11 observable 22 = nominal 25 − 3 unobservable) |
| **P1** | the probabilistic strategy keeps ambiguous pairs as candidates and never force-merges |
| **V1** | a mapping change creates a new version, leaves prior versions intact, writes nothing to the parent task |
| **G1** | the global aggregate is k-anonymised (nausea published at ≥3 subjects, small cells suppressed), no identifiers |

---

## 5. Verification (what was actually run here)

All commands below were executed in this workspace against a live instance. Results are quoted verbatim.

### 5.1 Backend integration tests — 25/25 pass

```bash
cd backend && mvn test
```
```
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
Covers: A/B/C/D rules; intersection semantics; both reconstruction routes agreeing (read-only and persisted);
stale-snapshot invalidation; transport dedupe; conflicting-duplicate quarantine; withdrawal/correction race
quarantine; delivery projection has no identity fields and survives revocation as an intact summary. Plus the 10
safety tests: three-source single episode + recurrence; cross-participant non-linkage; observable-day denominator;
ambiguous candidates never applied; deterministic-vs-probabilistic split/merge of a delayed report; mapping-change
versioning with no parent write-back; k-anonymised aggregate; interval widening; all acceptance checks; and
domain-scoped identity reads.

### 5.2 Frontend render tests (live backend) — 8/8 pass

```bash
cd frontend && npm run test        # vitest + happy-dom, mounts the real views against http://127.0.0.1:8080
```
```
Test Files  1 passed (1)
     Tests  8 passed (8)
```
Asserts the Overview shows **15/15**, the Acceptance cards list A1/B1/C1/D2, the Eligibility pivot renders both
planes and subject D as Pending, the Consent / Deliveries / Watermarks views render without error, and the Safety
view renders the P11 episode and recurrence with the acceptance strip.

### 5.3 End-to-end API acceptance (live jar)

| Step | Result |
|------|--------|
| `GET /api/acceptance?day=16` | **15/15 acceptance checks pass** (A1–A3, B1–B5, C1–C3, D1–D3, EQ) |
| `POST /api/deliveries/compile {day:11}` | `DLV-D11-001`, 2 members incl. `PSN-B2`, digest `a5110f88…` |
| `POST /api/deliveries/compile {day:16, route:REPLAY}` | `DLV-D16-001`, 4 members (C×3, D randomisation) |
| `POST /api/revocations/propagate {consentEntryId:3}` | 2 snapshot runs invalidated, 1 delivery access-revoked, 1 row restricted under `DR-RETAIN-25Y` |
| `GET /api/deliveries/DLV-D11-001` after revocation | `readable=false`, `status=ACCESS_REVOKED`, content digest intact, 2 summary lines kept |
| `GET /api/deliveries/DLV-D16-001` | `readable=true`, `status=ACTIVE` (unaffected) |
| `POST /api/recovery/run {site:CN-02}` | `status=FINDINGS`, 2 gaps, 0 duplicate resends, 0 digest mismatches |
| exact re-send of `VST-D4-V1` seq 12 | `duplicate=true` — "Retransmission … acknowledged; counted once." event count stays 9 |
| conflicting re-send (different payload) | `duplicate=true, quarantined=true` → `Q-0001-DUP` |
| `GET /api/watermarks?day=16` | CN-02 source confirmed 12 / ack 9 / analysis 9 → D12 **UNCONFIRMED** |

The pinned watermark string for day 16 is
`CN-01:AE=15(src=15,ack=15); … CN-02:VISIT=12(src=12,ack=9)…` (the CN-02 analysis stays at 9).

### 5.4 Safety reconstruction end-to-end (live jar, analysis day 26)

| Step | Result |
|------|--------|
| `GET /api/safety/acceptance?day=26` | **7/7 checks pass** (E1, E2, E3, X1, P1, V1, G1) |
| `POST /api/safety/reconstruct {strategy:DETERMINISTIC, version:25.1}` | `SR-DET-D26-001`, **11 episodes**, 0 candidates |
| `POST /api/safety/reconstruct {strategy:PROBABILISTIC, version:25.1}` | `SR-PRB-D26-001`, **10 episodes**, **3 ambiguous candidates** (none applied) |
| deterministic P11 members | index = `A,B,C`; recurrence #2 = `D` |
| `GET /api/safety/stats` | 3 versions: `SV-D26-001` (25.1), `SV-D26-002` (25.1 prob), `SV-D26-003` (26.0, supersedes prior) |
| `POST /api/safety/reconstruct {version:26.0}` then re-check | new `SV-D26-003` supersedes `SV-D26-002`; `SV-D26-001/002` digests unchanged; deliveries + eligibility row counts unchanged |
| `POST /api/safety/aggregates/publish {statsCode:SV-D26-001, approvalCode:DUA-GLOBAL-01, kFloor:3}` | `AG-…-001`, **1 published / 4 suppressed**; Nausea 5 index / 1 recurrence over 104 subject-days, rate 4.808 [1.549, 11.219] per 100 person-days |
| `GET /api/safety/observations?domain=SG` | only `L-SG-01-022` — no CN participant crosses the domain |
| `GET /api/safety/observations` (no domain) | **400** — per-participant data is never returned undeclared |
| `POST /api/safety/reconstruct` as `sponsor` | **403** |

The exposure denominator for day 26 is **143 observable person-days** across 10 participants (P11 contributes 22
of a nominal 25: three unobservable dose-hold days earn no credit).

---

## 6. Using the console

Open `http://localhost:8080` and sign in (the login screen lists the demo accounts; click one to prefill).

* **Overview** — acceptance score, watermark gaps, deliveries, subjects.
* **Acceptance** — the four subject cards with expected vs observed and reason codes.
* **Consent & protocol** — append grants/withdrawals and protocol versions; per-subject timelines; approved
  envelopes. Recording a withdrawal invalidates affected snapshot runs immediately.
* **Eligibility** — the dual-plane pivot (replay or materialised), with a live route-equivalence badge and the
  source event ledger. The top-bar **analysis day** changes the instant for every screen.
* **Watermarks** — per-domain three-counter bars with an UNCONFIRMED gap band; confirm source / ack transfer /
  fold analysis / break & restore link; run a recovery check.
* **Deliveries** — compile by either route, inspect the pinned envelope, frozen member summary and projected
  records; revocation state is visible.
* **Safety episodes** — the cross-source reconstruction. Select a residence domain, re-run the deterministic or
  probabilistic linker, and inspect episodes, member observations, candidate edges and reasons, the observable-day
  exposure denominator, per-term first/recurrence rates with uncertainty bands, the terminology editions, and the
  k-anonymised global aggregate. This screen uses its own analysis-day box (default 26), independent of the
  workspace day-16 toggle, because the safety scenario extends to D20/D28.
* **Withdrawal** — propagate a recorded withdrawal, register disposition and tie-break rules, see executions.
* **Recovery** — recovery reports (expand for per-domain digests), simulate a post-restore re-send, resolve
  quarantined races.
* **Audit** — the append-only audit trail of every regulator-relevant transition.

### Try the headline flow

1. As **global**, Deliveries → set analysis day `11` → *Compile delivery* (contains B's D11 AE).
2. As **admin**, Withdrawal → *Propagate* B's withdrawal.
3. Back in Deliveries → open `DLV-D11-001`: content is revoked, but the frozen summary and digests remain.
4. Set day `16`, Acceptances → all checks pass; Eligibility → D's D12 visit is Pending globally, available locally.
5. Open **Safety episodes** (day 26): the acceptance strip shows 7/7; the deterministic table shows P11's
   three-source index episode and its D20 recurrence; switch to **Probabilistic** to see the ambiguous candidates;
   publish the k-anonymised global aggregate.

---

## 7. Design decisions worth noting

* **One engine, two routes.** Materialisation is a cache over the same `EligibilityEngine`; it can never diverge
  because it is not a second implementation. Snapshots are `STALE`d on later changes and are refused for new
  reads, so an old delivery is never a standing authorisation.
* **Intersection, literally.** Every gate must pass; a single failing gate blocks, a single undeterminable gate
  pends. There is no union anywhere in the evaluator.
* **Two clocks, on purpose.** All validity uses the local signature/withdrawal instant; the receipt instant is
  only an ordering tie-breaker. B's withdrawal is effective at the moment it was signed, not when it reached us.
* **Gaps are first-class.** The analysis watermark can never fold past the transfer acknowledgement, and anything
  above it is `PENDING`, never reported as absent. The UI draws the unconfirmed band instead of a zero.
* **History is preserved.** Revoking a delivery sets `accessRevoked` and applies the registered disposition, but
  the legal-member summary and digests are frozen and kept. Nothing claims the delivery never existed.
* **Isolate, don't guess.** A withdrawal/correction race with equal effective instants and no registered
  tie-break rule is quarantined; it never silently picks a side.
* **Episodes, not reports.** The safety counter divides reconstructed episodes by observable treatment days. The
  same clinical process seen in three sources is one episode; a re-appearance after documented resolution is a
  new recurrence. Ambiguous pairs are kept as candidates and widen the rate interval rather than being force-merged.
* **Version, don't overwrite.** A terminology change produces a new stats version that supersedes the prior one; the
  old digest is frozen, and nothing is written back to the parent task's deliveries or eligibility rows.
* **Identity stays in its domain.** Participant keys are resolved only inside a residence domain; the global plane
  receives k-anonymised aggregates only, with small cells suppressed.

---

## 8. Project layout

```
backend/
  src/main/java/com/ctrial/
    model/        JPA entities + Enums (the rule vocabulary) + Safety* entities
    service/      EligibilityEngine (pure), EligibilityService, EligibilityRunService,
                  DeliveryService, RevocationService, RecoveryService, EventIngestService,
                  StateService, AcceptanceService, AuditService
    service/safety/  TerminologyMapper, EpisodeLinker (pure), LocalSafetyResolver (pure),
                     SafetyRateCalculator (pure), SafetyReconstructionService,
                     SafetyAggregateService, SafetyQueryService, SafetyAcceptanceService
    web/          ReadController, CommandController, SafetyController, SpaConfig
    config/       SecurityConfig, DemoSeeder, SafetyDemoSeeder
  src/test/java/com/ctrial/AcceptanceRulesTest.java, SafetyReconstructionTest.java
frontend/
  src/            App, views/*, components/*, lib/api.js, lib/store.js, ui.test.js
Dockerfile, compose.yaml
```
