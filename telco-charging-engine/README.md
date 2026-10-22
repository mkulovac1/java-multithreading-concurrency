# Telco Charging Engine

A Spring Boot service for the telecom domain that puts the material of the *Java Multithreading, Concurrency & Performance Optimization* course into practice.

**Stack:** Java 25 (LTS) · Spring Boot 4.1.1 · springdoc-openapi 3.1.1 (Swagger UI) · Jackson 3 · Lombok · JUnit 5 · no database (in-memory state, like an OCS)

The reasoning behind every design decision is documented in [docs/DESIGN.md](docs/DESIGN.md).

## What the system does

| Module | Telecom function | Key concurrency techniques |
|---|---|---|
| `subscriber` | Prepaid accounts, top-up, **Me2U credit transfer** | Per-account `ReentrantLock`, `tryLock(timeout)` → 503, ordered locking (no deadlock) |
| `tariff` | Tariff catalog, `RatingEngine` (60/60, 1/1 billing) | `ReentrantReadWriteLock`, immutable `record`, pure (stateless) function |
| `charging` | **Online charging**: reserve → commit / release (Diameter Gy analogue), reservation expiry | CAS over immutable session state (`AtomicReference`), daemon scheduler (reaper) |
| `cdr` | **CDR mediation**: batch intake, rating, usage aggregation | Producer-consumer, `Lock` + 2× `Condition`, back-pressure, `wait/notifyAll` latch, `LongAdder`, `SmartLifecycle` graceful shutdown |
| `cdr.rating` | **What-if re-rating** of millions of CDRs | Partitioning + `Thread.start/join` (latency), `Semaphore(1)` |
| `campaign` | **Bulk SMS campaigns** towards the SMSC | Virtual threads (thread per message), fair `Semaphore` = SMPP window, `ScopedValue`, cancellation via interrupt |
| `fraud` | Velocity check (SIM-box / Wangiri) | Lock-free CAS counters, Treiber `LockFreeStack` for alerts |
| `monitoring` | Operational metrics | `LongAdder`, CAS min/max, `ThreadMXBean` |

## Running

```bash
mvn spring-boot:run
# or
mvn clean package && java -jar target/telco-charging-engine-1.0.0.jar
```

### Docker

```bash
docker compose up -d --build          # build + start on http://localhost:28989
HOST_PORT=18080 docker compose up -d  # a different host port
docker compose down

# or without compose
docker build -t telco-charging-engine:1.0.0 .
docker run --rm -p 8080:8080 telco-charging-engine:1.0.0
```

Configuration can be overridden with environment variables (relaxed binding), e.g. `-e TELCO_PIPELINE_WORKERS=8 -e TELCO_SEED_ENABLED=false`.

- Swagger UI: http://localhost:8080/swagger-ui.html (Docker: port 28989)
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Health: http://localhost:8080/actuator/health

Demo data is loaded at startup: tariffs `BASIC`, `SMART`, `BUSINESS` and subscribers `38761000001`, `38761000002`, `38762000003`, `38765000004`. Disable it with `telco.seed.enabled=false`.

## Tests

```bash
mvn test        # 155 tests: unit, concurrent stress tests, MockMvc integration, JaCoCo report
mvn verify      # the same, plus a build failure if line or branch coverage drops below 95%
```

The coverage report is written to `target/site/jacoco/index.html` (the build enforces at least 95% of lines and branches, overall and per package).

The concurrent tests use a start gate (`CountDownLatch`) for maximum contention and verify **invariants**:
- money is conserved and there is no overdraft,
- opposite transfers never deadlock,
- every element is processed exactly once,
- every session is settled exactly once, and its API response never mixes two states,
- no transfer completes after a suspension has returned,
- exactly one fraud alert is raised per window,
- the number of in-flight messages never exceeds the SMPP window.

## Demo script (every feature, printed to the console)

```bash
./scripts/demo.sh                      # all 13 sections, ~1 minute
./scripts/demo.sh --step               # section by section, you decide at every step
./scripts/demo.sh charging extend      # only some sections
./scripts/demo.sh --step cdr fraud     # step through only some sections
./scripts/demo.sh --list               # numbered sections with a description
./scripts/demo.sh --rebuild            # rebuild the jar first
```

With `--step` the script stops before every section and asks: `[Enter]` run it, `[s]` skip it, `[a]` run all the remaining ones without asking, `[q]` quit (the application is still shut down cleanly and a summary is printed). After each section it prints how many checks passed. Every section creates its own subscribers, so the sections are independent and can be skipped or selected by name (they always run in the order of the list); each section can be run once per started instance (start the script again to repeat one). The monitoring section only asserts counters for the sections that actually ran.

The script starts its own fresh instance on a free port (known demo data; reservation validity 4 s and a 1 s reaper, so expiry can be shown quickly), then calls the real HTTP API. For every step it prints the request, the response and a `✔` / `✘` line per expectation; it ends with a summary and exits with code 1 if anything failed. It needs `java`, `curl` and `python3` (and `mvn` only if the jar is missing).

| Section | What it shows |
|---|---|
| 1 health | health, OpenAPI document, demo tariffs and subscribers |
| 2 tariffs | create / update / list, duplicates, validation (price cap 1000, increment 60 s), 404 |
| 3 subscribers | activation, duplicates, top-up, tariff change, suspend, paging, validation |
| 4 transfers | Me2U transfer, money conservation, same number, insufficient credit, suspended recipient |
| 5 charging | reserve / commit / release with exact amounts, 60/60 vs 1/1 rounding, partial grant, 402, 409, 404 |
| 6 extend | `extend` renews the deadline, expiry of a non-extended session, reaper, partial extension, 402 / 409 |
| 7 cdr | batch rating, failures, failure-sample cap, usage aggregation, long polling, validation |
| 8 fraud | threshold of 30 events/min, one alert per window, far-future CDR, acknowledge |
| 9 rerating | sequential vs parallel (speed-up, exact match), validation, "only one at a time" (503) |
| 10 campaigns | 3000 messages with at most 50 in flight and ~30 threads, cancellation, accounting |
| 11 concurrency | 60 parallel reservations, 60 opposite transfers (no deadlock), racing commit / release |
| 12 errors | malformed JSON, bad parameters, 404 / 405 / 415 |
| 13 metrics | counters from the previous sections, SMSC window never exceeded, graceful shutdown |

## Demo scenarios (curl)

```bash
# 1) Prepaid call: reserve 10 min, use 90 s
curl -s -XPOST localhost:8080/api/charging/sessions -H 'Content-Type: application/json' \
  -d '{"msisdn":"38761000001","serviceType":"VOICE","requestedUnits":600}'
curl -s -XPOST localhost:8080/api/charging/sessions/<sessionId>/commit -H 'Content-Type: application/json' \
  -d '{"usedUnits":90}'
# long call: before the 5 min validity ends, reserve more units and renew the deadline
curl -s -XPOST localhost:8080/api/charging/sessions/<sessionId>/extend -H 'Content-Type: application/json' \
  -d '{"additionalUnits":300}'

# 2) Me2U credit transfer
curl -s -XPOST localhost:8080/api/subscribers/transfers -H 'Content-Type: application/json' \
  -d '{"fromMsisdn":"38762000003","toMsisdn":"38761000001","amount":5}'

# 3) CDR batch, wait up to 5 s for the result
curl -s -XPOST 'localhost:8080/api/cdrs/batches?waitMillis=5000' -H 'Content-Type: application/json' \
  -d '{"cdrs":[{"recordId":"1","msisdn":"38761000002","serviceType":"VOICE","units":125,"eventTime":"2026-10-21T10:00:00Z"}]}'

# 4) Re-rating: sequential vs. parallel
curl -s -XPOST localhost:8080/api/cdrs/rerating-simulations -H 'Content-Type: application/json' \
  -d '{"tariffCode":"SMART","cdrCount":5000000,"threads":8,"seed":42}'

# 5) SMS campaign to 20,000 numbers from a range, then monitor it
curl -s -XPOST localhost:8080/api/campaigns -H 'Content-Type: application/json' \
  -d '{"name":"Autumn promo","message":"Double data!","numberRange":{"firstMsisdn":"38761000000","count":20000}}'
curl -s localhost:8080/api/monitoring/metrics   # smsc.inFlight = 50, jvm.liveThreads ~30
```

## Configuration (`application.yml`)

| Key | Default | Meaning |
|---|---|---|
| `telco.charging.lock-timeout` | `200ms` | How long to wait for an account lock before answering 503 |
| `telco.charging.session-ttl` | `5m` | Validity of a reservation |
| `telco.charging.reaper-interval` | `5s` | How often expired reservations are released |
| `telco.charging.session-retention` | `1h` | How long closed sessions stay queryable |
| `telco.pipeline.buffer-capacity` | `50000` | Buffer capacity between HTTP and the rating workers |
| `telco.pipeline.workers` | `4` | Number of rating worker threads |
| `telco.pipeline.offer-timeout` | `500ms` | How long a producer waits for space before rejecting |
| `telco.pipeline.batch-retention` | `1h` | How long a finished batch stays queryable |
| `telco.campaign.smsc-window-size` | `50` | SMPP window (max messages in flight) |
| `telco.campaign.smsc-min-latency` / `smsc-max-latency` | `20ms` / `120ms` | Simulated SMSC latency |
| `telco.campaign.smsc-failure-rate` | `0.02` | Simulated SMSC failure rate |
| `telco.campaign.max-recipients` | `100000` | Max recipients per campaign |
| `telco.fraud.max-events-per-minute` | `30` | Fraud alert threshold |

## Package structure

```
ba.telco.ocs
├── common/{config, exception, concurrency, metrics}
├── subscriber/{controller, service, repository, entity, dto, mapper}
├── tariff/{controller, service, repository, entity, dto, mapper}
├── charging/{controller, service, repository, entity, dto, mapper}
├── cdr/{controller, service, entity, dto, mapper, pipeline, rating}
├── campaign/{controller, service, repository, entity, dto, mapper, smsc}
├── fraud/{controller, service, entity, dto, mapper}
└── monitoring/{controller, service, dto}
```

## Known limitations (deliberate decisions)

- State lives in memory and a restart wipes it. Production would add a write-behind to a database or an in-memory data grid.
- JVM locks protect a single instance only. For multiple instances: partition by MSISDN (single writer) or use a conditional `UPDATE` in the database.
- `SimulatedSmscGateway` simulates the SMSC. A real adapter would use an SMPP library (e.g. jSMPP) behind the `SmscGateway` interface.
- No authentication (Spring Security / OAuth2 is the next step).
- A charging session cannot be committed after its validity time has passed (409); the reaper then releases the credit (it runs every 5 s, so reserved credit stays blocked until then). `release` is still accepted after expiry. A session is valid for `session-ttl` (5 min); a longer call must call `POST /api/charging/sessions/{id}/extend` before the deadline, otherwise it expires and the call cannot be billed through that session. Each extension reserves more credit (partial grant, 402 if none is left) and restarts the 5 min timer; the credit of an abandoned session stays blocked for at most one `session-ttl` after its last extension. There is no absolute maximum call length: a session can be extended as long as the subscriber has credit (a single session is capped at 1,000,000,000 units).
- CDRs are not de-duplicated: `recordId` is only used in failure messages, so a batch that is submitted twice (e.g. a client retry after a timeout) is rated and counted twice. Clients must not resend records that were accepted; check `rejected` in the batch response and resend only those.
- Closed charging sessions, finished CDR batches and idle fraud windows are purged after their retention (1 h, 1 h, 10 min); afterwards lookups return 404.
- All housekeeping jobs share a pool of two daemon threads. A job that blocks (e.g. the reaper waiting for a busy account) occupies one thread, so the others still run; if two jobs block at the same time, the rest wait.
- CDR `units` are limited to 1,000,000,000 per record; larger values are rejected with 400. Tariff prices are limited to 1000 per unit (minute, SMS, MB).
- Listing does not scale: `GET /api/subscribers` sorts all subscribers on every request and `/api/monitoring/metrics` scans all charging sessions to count the active ones. This is a demo, so both are kept simple; for millions of records use a sorted index (e.g. `ConcurrentSkipListMap`) and a running counter for active sessions.
