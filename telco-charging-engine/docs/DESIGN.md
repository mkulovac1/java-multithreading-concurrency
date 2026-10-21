# Design Notes

This document explains *why* the code looks the way it does. The source code carries no comments by design; every non-obvious decision is recorded here instead, grouped by module. Each section states the problem, the chosen mechanism, and the alternatives that were rejected.

---

## 1. Guiding principles

| Principle | Where it shows up |
|---|---|
| **Make invalid states unrepresentable** | Immutable `record`s for tariffs, session state, fraud windows; state transitions only through CAS. |
| **Pick the weakest tool that is correct** | `volatile` for independent single fields, atomics/`LongAdder` for counters, locks only where several fields must change together. |
| **Fail fast instead of queuing forever** | `tryLock(timeout)` → 503, bounded buffer with `offer(timeout)`, `Semaphore.tryAcquire()` for simulations. |
| **Every thread has an owner and a way to stop** | Named thread factory, `SmartLifecycle`, `@PreDestroy`, interrupt-based cancellation. |
| **Measure what matters** | `LatencyStats`, `ThreadMXBean`, SMSC in-flight counters exposed via `/api/monitoring/metrics`. |

---

## 2. Cross-cutting decisions

### 2.1 Money
- **`BigDecimal` with scale 4, `HALF_UP`** (`RatingEngine.MONEY_SCALE`). Prices like 0.005 KM per SMS and per-second voice billing need sub-cent precision; `double` would accumulate binary rounding error.
- **Hot aggregates are stored as `long` minor units** (unscaled value at scale 4) in `AtomicLong` / `LongAdder` (`CdrBatch`, `SubscriberUsage`, re-rating totals). Adding to a `long` is a single atomic instruction; accumulating `BigDecimal`s would need a lock or a CAS loop that allocates on every retry.
- `longValueExact()` is used for the conversion so an overflow fails loudly instead of silently wrapping.

### 2.2 Time
- A `Clock` bean (`ClockConfig`, UTC) is injected everywhere instead of calling `Instant.now()`. Tests use a mutable clock to move time forward deterministically (session expiry, fraud windows) without `sleep`.
- `System.nanoTime()` is used for durations and timeouts; it is monotonic, unlike wall-clock time.

### 2.3 Errors
- `BusinessException` carries its HTTP status; subclasses map the domain onto HTTP:

  | Exception | Status | Meaning |
  |---|---|---|
  | `BadRequestException` | 400 | Request is semantically invalid |
  | `InsufficientFundsException` | 402 | Not enough credit |
  | `NotFoundException` | 404 | Unknown subscriber, tariff, session, batch, campaign |
  | `InvalidStateException` | 409 | Transition not allowed (session already closed, campaign finished, duplicate) |
  | `ResourceBusyException` | 503 | Lock timeout, simulation already running, request interrupted |

- `GlobalExceptionHandler` returns RFC 9457 `ProblemDetail`. Field validation errors are returned as an `errors` map (first message per field) so clients can show them next to inputs.
- Whenever an `InterruptedException` is caught and not rethrown, the interrupt flag is restored (`Thread.currentThread().interrupt()`), so code further up can still see that cancellation was requested.

### 2.4 Configuration
- `TelcoProperties` is an immutable, `@Validated` record tree. Invalid configuration (e.g. `workers: 0`, failure rate 1.5) stops the application at startup instead of failing at the first request.

### 2.5 Layering
- `controller → service → repository`, with `entity` (domain objects), `dto` (API contract) and hand-written `mapper`s. The API contract is decoupled from the domain model, so concurrency internals (locks, atomics) never leak into JSON.
- Mappers are written by hand rather than generated (MapStruct): they are trivial, and several of them deliberately read a consistent snapshot (e.g. `Subscriber.balanceSnapshot()`), which a generator would not know to do.

### 2.6 Request threads
- `spring.threads.virtual.enabled: true` runs Tomcat request handling on virtual threads. Several endpoints block on purpose (long-polling a CDR batch for up to 30 s, waiting on account locks); on virtual threads a blocked request costs a few hundred bytes of heap instead of a 1 MB platform stack and a pool slot.
- Java 25 includes JEP 491: `synchronized` and `Object.wait()` no longer pin a virtual thread to its carrier, so monitor-based code (`SimpleCountDownLatch`) is safe to call from virtual threads.

### 2.7 Housekeeping scheduler
- One shared `ScheduledExecutorService` (`SchedulingConfig`) with **two daemon threads** runs all cleanup jobs (expired reservations, closed sessions, finished batches, stale fraud windows). The jobs are short and infrequent, so two threads are plenty. The second thread exists for isolation: the reaper can wait up to `lock-timeout` per busy account, and with a single thread that wait would delay every other job; with two, a blocked job occupies one thread and the rest still run (`SchedulingConfigTest`). Daemon status guarantees housekeeping can never keep the JVM alive on its own.
- All jobs use `scheduleWithFixedDelay`, not `scheduleAtFixedRate`: if a run is slow, the next one is postponed instead of queuing up back-to-back runs.
- Every scheduled job wraps its body in `try/catch (RuntimeException)`. An exception escaping a periodic task **silently cancels all future runs** of that task; catching and logging keeps the job alive.

### 2.8 Lombok
Lombok removes boilerplate, but only where the generated code is **identical in behaviour** to what was written by hand:

| Annotation | Used for | Rule |
|---|---|---|
| `@Slf4j` | Every class that logs | Replaces the `LoggerFactory.getLogger(...)` field; the field is still called `log`. |
| `@RequiredArgsConstructor` | Controllers, services, mappers and value-holding classes whose constructor only assigns fields | Only where the constructor **only** assigns each parameter to the field of the same name, in field order. Parameter order is part of the API: tests build services by hand (`new SubscriberService(repository, tariffService, locks, clock)`), so field order was checked to match the original constructors. |
| `@Getter` / `@Setter` (field level) | Trivial accessors of `final` or `volatile` fields | Field-level rather than class-level, so a class-level annotation can never silently generate an accessor for a lock, an `AtomicReference` or another internal field. |
| `@AllArgsConstructor` | `MutableClock` in tests | Non-final field, so `@RequiredArgsConstructor` would not include it. |

Deliberately **not** converted:
- **Constructors that do work**: deriving values from `TelcoProperties`, defensive copies (`List.copyOf`), validation (`BoundedBlockingBuffer`, `SimpleCountDownLatch`), creating the `Semaphore`, or `super(...)` calls in exceptions. Lombok cannot express these.
- **Accessors with concurrency semantics**: `SimpleCountDownLatch.getCount()` is `synchronized` so it reads `count` under the monitor that writers use. A Lombok getter would drop that and lose the visibility guarantee. Getters that read atomics (`getStatus()` → `state.get().status()`) or setters with guards (`Subscriber.setStatus` asserts the account lock) stay hand-written for the same reason.
- **Records** (DTOs, `TariffPlan`, session `State`, configuration properties): records already provide constructors, accessors, `equals`/`hashCode` and immutability; `@Value`/`@Data` would add nothing.
- **`@Data` / `@EqualsAndHashCode` on entities**: entities are identity-based, mutable objects shared between threads; value-based equality on mutable state would break them as map keys and invite races in `hashCode`.
- **`@UtilityClass`** (`CampaignContext`, `SyntheticCdrGenerator`): it is an experimental Lombok feature; a private constructor is one line and has no surprises.
- **`@Synchronized`**, **`@Builder`**, **`@SneakyThrows`**: explicit locks are central to this codebase and must stay visible; no class has enough optional parameters for a builder; checked exceptions (`InterruptedException`) are part of the cancellation contract and must not be hidden.

Build setup: Lombok is an `optional` dependency, registered explicitly in `annotationProcessorPaths` (since JDK 23 `javac` no longer runs annotation processors found on the classpath by default) and excluded from the executable JAR, so it never reaches the runtime image. `lombok.config` stops configuration lookup at the project root and marks generated code with `@Generated`, so coverage tools ignore it.

---

## 3. Subscriber accounts (`subscriber`)

### 3.1 Per-account lock
- Each `Subscriber` owns a `ReentrantLock`. `balance` and `reserved` must change together (e.g. settling a reservation releases the reserved amount and debits the cost), so they are guarded by one lock rather than made individually atomic.
- **Lock granularity is one account.** A global lock would serialise all traffic; per-account locks let unrelated subscribers proceed in parallel.
- Every mutator calls `assertLocked()` (`lock.isHeldByCurrentThread()`). A forgotten lock becomes an immediate `IllegalStateException` in tests instead of a rare race in production.
- `tariffCode` is `volatile`: it is written independently and never needs to be consistent with another field, so visibility is all that is required.
- `status` is also `volatile`, so it can be read without locking (e.g. for API responses), but it is **written only under the account lock** (`setStatus` asserts this) and **checked only under the account lock** (`requireActive` is called inside `withLock` / `withLocks`). Suspension and money operations are therefore serialised. When `changeStatus(SUSPENDED)` returns, every operation that already passed the check has finished, and every later operation sees the new status. Checking before taking the lock would leave a window where a transfer passes the check, waits for the lock while the account is suspended, and then completes anyway. `SubscriberServiceConcurrencyTest.noTransferCompletesAfterSuspensionReturns` covers this; it fails reliably against the check-before-lock version.
- `balanceSnapshot()` takes the lock and returns an immutable record. Because the lock is reentrant it can be called inside an operation that already holds it (e.g. to return the new balance after a top-up).

### 3.2 `AccountLocks`
- **`tryLock(timeout)` instead of `lock()`.** A "hot" account (e.g. a machine generating traffic) should degrade into fast 503 "busy, retry" answers, not into an ever-growing pile of blocked request threads. Timeouts are counted in a `LongAdder` and exposed as `lockTimeouts` in metrics.
- **Global lock ordering for two accounts.** `withLocks` always locks the account with the lexicographically smaller MSISDN first. Opposite transfers A→B and B→A therefore acquire locks in the same order, which removes the circular-wait condition and makes deadlock impossible. `SubscriberServiceConcurrencyTest` runs exactly this scenario under contention.
- Locking the same account twice is rejected explicitly; the transfer service also rejects "transfer to self" with a 400 before reaching the lock layer.
- Unlock happens in nested `finally` blocks in reverse acquisition order, so a failure after the first lock never leaks it.

### 3.3 Repository
- `ConcurrentHashMap` + `putIfAbsent` makes "create subscriber if MSISDN is free" a single atomic step; a check-then-insert would allow two concurrent activations of the same number.

---

## 4. Tariffs and rating (`tariff`)

### 4.1 `TariffPlan` is an immutable record
- An update replaces the whole object. A reader that obtained a reference always sees one consistent price list, never a mix of old voice price and new SMS price.
- A charging session captures the `TariffPlan` at reservation time. A price change during a call does not change what the subscriber was quoted when the call started.

### 4.2 `ReentrantReadWriteLock` in `TariffRepository`
- The catalog is read on every rated event and written only when product managers change prices: the classic read-mostly case. Any number of rating threads read in parallel; writers get exclusive access.
- A `ConcurrentHashMap` would also be thread-safe for single lookups, but `findAll()` must return a **consistent snapshot of the whole catalog**; CHM iteration is only weakly consistent. The read lock gives that guarantee and also safely publishes the plain `HashMap`.

### 4.3 `RatingEngine` is a pure function
- No fields except constants, no side effects, so it is thread-safe by construction and needs no synchronization, even from millions of re-rating iterations on many threads.
- Voice billing rounds **up** to the started billing increment (`ceil(units / increment) * increment`): 60/60 billing charges 61 s as 120 s, 1/1 billing charges 61 s as 61 s.
- `affordableUnits` uses **binary search** over `[0, requested]` because price is monotonic in units. It finds the largest grant the subscriber can afford in O(log n) price evaluations; this is how a partial grant is computed. A free service (price 0) is granted in full even with zero balance.

---

## 5. Online charging (`charging`)

### 5.1 Reservation model
- Mirrors Diameter Gy: **CCR-Initial** reserves credit before service delivery, **CCR-Terminate** commits actual usage, or the reservation is released/expires. Reserving first guarantees a prepaid subscriber can never consume more than they have.
- If the full request is not affordable, the engine grants as many units as the available credit covers (partial grant), as real OCSs do; only zero affordable units is an error (402).

### 5.2 Session state: one immutable `State` behind an `AtomicReference`
- Status, used units, charged amount and close time belong together. Packing them into one immutable record and swapping it with CAS means a single read of `state` always returns values that belong together.
- `getState()` exposes that single read. `ChargingSessionMapper` builds the response from **one** `State` instance rather than calling `getStatus()`, `getUsedUnits()` and `getClosedAt()` separately; separate calls each read the reference again and could combine fields from before and after a concurrent commit (e.g. `ACTIVE` with a close time). The convenience getters remain for code that needs only one field. `ChargingServiceConcurrencyTest.responseNeverMixesStatusAndSettlementOfDifferentStates` maps sessions while they are being committed and asserts every response is internally consistent.
- **Exactly-once settlement.** Commit, release and the expiry reaper can race on the same session. `close()` only succeeds via `compareAndSet(ACTIVE → terminal)`, so exactly one of them wins; the losers get `false` and translate it into a 409.
- `close()` runs **inside the account lock** in `settle()`. The CAS decides *who* settles; the lock makes the status change and the balance change (`releaseReservation` + `debit`) one atomic step with respect to other money operations on that account.

### 5.2.1 Extending a session (`extend`, CCR-Update)
- A call longer than `session-ttl` must renew its reservation before the deadline. `extend(sessionId, additionalUnits)` asks for more units, reserves the credit for them and restarts the validity timer (`now + session-ttl`).
- **Units, reserved amount and deadline live in the same immutable `State` as the status.** They change together, so one read of `state` can never show the new deadline with the old reservation. `extend` is a CAS `ACTIVE -> ACTIVE` with a new `State`; `close` copies the reservation into the closed state, so `settle` always releases the *total* reserved amount, however many times the session was extended.
- **The new reservation is the price of the new total, not the sum of increments.** Voice is rounded up to the billing increment, so `price(40 s) + price(30 s)` differs from `price(70 s)` on a 60 s tariff. The engine computes `grantedTotal = affordableUnits(requestedTotal, reserved + available)` and reserves only the difference `price(grantedTotal) - reserved`. That is a partial grant when credit is short; if not a single extra unit is affordable the call fails with 402 and the session is left untouched (it still expires at its old deadline).
- **Runs under the account lock**, the same lock as `reserve` and `settle`, so extending, committing, releasing and expiring one session are serialised. Inside the lock it re-checks that the session is `ACTIVE` and not past its deadline, and that the subscriber is active. An expired session cannot be revived: once `expiresAt` has passed, `extend` answers 409, exactly like `commit`.
- **The reaper re-checks expiry under the lock** (`settle(..., onlyIfExpired = true)`). Without it the reaper could scan a session as expired, an `extend` could renew it a moment later, and the reaper would still close it, losing a call the network has just extended. `ChargingExtendTest.reaperDoesNotExpireASessionThatWasExtendedAfterTheScan` covers this; it fails against a reaper without the check.
- A session is capped at 1,000,000,000 units in total, and a single request at 10,000,000, so repeated extensions cannot overflow `long`.
- Tests: partial grant, voice rounding, 402 without side effects, expired/closed/suspended/unknown sessions, concurrent extensions (never more than the balance, `reserved == price(granted)`), and 100 rounds of `extend` racing `commit` (no reserved credit is ever leaked).

### 5.3 Reaper (`ChargingSessionReaper`)
- Abandoned sessions (a network element that never sends CCR-T) would otherwise lock credit forever. The reaper releases reservations past their validity time.
- `commit` rejects a session past `expiresAt` (409) even if the reaper has not closed it yet; the check uses the injected `Clock`. `release` stays allowed because freeing credit is always safe. The check is not under the account lock: a commit that passes it just before expiry may settle just after, which is acceptable because the session was valid when the request arrived.
- If an account is busy (lock timeout), the session is simply retried on the next run; losing a race to a concurrent commit (`InvalidStateException`) is expected and ignored.
- Closed sessions are purged after `session-retention` so the in-memory map does not grow without bound.
- `findExpired` is a weakly consistent scan over a `ConcurrentHashMap`: a session created during the scan may be missed and is picked up next time, which is fine for a periodic job.

---

## 6. CDR mediation pipeline (`cdr`)

```
HTTP threads (producers) --offer(timeout)--> [ bounded buffer ] --poll--> N rating workers (consumers)
```

### 6.1 Why producer-consumer
- Ingesting a batch (HTTP) and rating it (CPU) run at different speeds. A buffer decouples them, and a fixed set of workers bounds CPU usage regardless of how many clients submit at once.

### 6.2 `BoundedBlockingBuffer`
- Built from one `ReentrantLock` and **two `Condition`s** (`notFull`, `notEmpty`). It is functionally equivalent to `ArrayBlockingQueue` and exists to show the mechanism.
- Two conditions allow `signal()` instead of `signalAll()`: a put only wakes a consumer, a take only wakes a producer, so no thread is woken just to go back to sleep.
- Waits are in `while` loops, never `if`, to handle spurious wakeups and other threads taking the slot first.
- Timed variants use `awaitNanos`, which returns the remaining time, so the total wait never exceeds the requested timeout across multiple wakeups.
- `lockInterruptibly()` keeps the buffer responsive to cancellation even while waiting for the lock.

### 6.3 Back-pressure and fail-fast
- The buffer is bounded (`buffer-capacity`). When workers fall behind, producers wait up to `offer-timeout`; if there is still no space, the record is **rejected** instead of growing memory without limit.
- Once one record of a batch times out, the rest of that batch is rejected immediately. Otherwise a 10,000-record batch against a saturated pipeline could hold an HTTP thread for 10,000 × 500 ms.
- Rejections are counted per batch (`rejected`) and globally, so clients know exactly what to resend.

### 6.4 Workers
- **Platform threads**, a fixed count (`workers`). Rating is CPU-bound; virtual threads bring no benefit for CPU work, and a fixed count caps CPU usage.
- Created through `NamedThreadFactory`: readable names in thread dumps (`cdr-rater-1`) and an uncaught-exception handler so a dying worker is always logged.
- Non-daemon: they must be stopped deliberately and drained, not killed abruptly at JVM exit.
- Workers use `poll(200 ms)` rather than `take()` so they periodically check the `accepting` flag and can exit when intake is closed and the buffer is empty.
- `BusinessException` (unknown subscriber, etc.) marks the CDR as failed with its message. Any other exception is logged with a stack trace and recorded as an internal error; the worker keeps running.

### 6.5 Graceful shutdown via `SmartLifecycle`
- `stop()`: stop accepting → workers drain the buffer → `join` with one shared 10 s deadline → interrupt whoever is still alive.
- **Phase `DEFAULT_PHASE - 4096`.** Lower phases start first and stop last. The pipeline starts before the web server accepts requests and stops only after the web server's graceful shutdown has let in-flight HTTP requests finish. Requests that are already submitting CDRs are not cut off midway.

### 6.6 `CdrBatch` and `SimpleCountDownLatch`
- A batch is updated concurrently by many workers, so all counters are atomics.
- `SimpleCountDownLatch` (monitor + `wait/notifyAll`) lets an HTTP caller block until every record has an outcome (`waitMillis`). It shows the monitor-based way of building a latch.
- `countDown()` returns `true` only for the call that reached zero, so `completedAt` is set by exactly one thread.
- Every record ends in exactly one of rated / failed / rejected, and each outcome counts the latch down once. The batch therefore always completes, even when it is partially rejected.
- Failure samples are capped at 20 (an `AtomicInteger` ticket decides whether a sample is stored), so a batch of 10,000 bad records does not create a 10,000-entry response.
- Finished batches are purged after `batch-retention`.

### 6.7 Usage aggregation
- `ConcurrentHashMap.computeIfAbsent` is atomic, so two workers seeing a new MSISDN at the same time still share one `SubscriberUsage`.
- `SubscriberUsage` uses `LongAdder`s: written by every worker, read rarely. `LongAdder` spreads updates over per-thread cells, avoiding CAS contention on one memory location; the cost (a non-atomic `sum()`) is irrelevant for statistics.

---

## 7. What-if re-rating (`cdr.rating`)

### 7.1 Goal: latency
- "How much revenue would N historical CDRs generate under this tariff?" is a CPU-bound, embarrassingly parallel job. The goal is to answer one request fast (latency), so the work is split across cores.

### 7.2 Mechanism
- The index range is split into equal partitions, one per platform thread (`Thread.ofPlatform()`); the last partition takes the remainder.
- Each thread writes its result to **its own slot** in `long[] partialTotals`; there is no shared accumulator and no synchronization in the hot loop. Each slot is written once at the end, so false sharing on adjacent slots does not matter.
- **`join()` is the happens-before edge** that makes each `partialTotals[i]` visible to the coordinating thread; no `volatile` or atomics are needed.
- The number of threads is limited to 2 × available processors. More threads than cores only adds context switching for CPU-bound work.
- If the coordinator is interrupted while joining, it interrupts all workers. Workers check the interrupt flag every 65,536 iterations: rarely enough to stay off the hot path, often enough to stop quickly.

### 7.3 Measuring honestly
- A JIT warm-up run (up to 200,000 CDRs) runs before timing, so the first measured run is not penalised by interpretation and compilation.
- Both a sequential and a parallel run are measured, and `speedup` is reported, so Amdahl's law can be observed directly.

### 7.4 Deterministic input
- `SyntheticCdrGenerator` derives each CDR from `(seed, index)` with a SplitMix64 finalizer. It is allocation-free, and CDR *i* is identical no matter which thread computes it.
- Sums are exact integers (minor units), so sequential and parallel results can be compared for **exact equality** (`resultsMatch`), which proves the parallel version is correct, not just fast.

### 7.5 Protecting the server
- A `Semaphore(1)` with `tryAcquire()` allows one simulation at a time; a second caller gets an immediate 503. Otherwise the endpoint could be used to occupy every core and starve real traffic.

---

## 8. Bulk SMS campaigns (`campaign`)

### 8.1 Virtual threads, one per message
- Sending an SMS is I/O-bound: the thread spends almost all its time waiting for the SMSC. Virtual threads make "one thread per message" affordable: 100,000 messages means 100,000 cheap virtual threads but only ~N carrier threads (N = cores).
- Code stays plain and blocking (`submit()` returns a result) instead of being rewritten into callbacks or reactive chains.

### 8.2 The SMPP window is a `Semaphore`
- An SMSC bind allows a fixed number of unacknowledged `submit_sm` requests (the window). Exceeding it gets throttled (`ESME_RTHROTTLED`). A semaphore with `smsc-window-size` permits enforces this exactly.
- **One semaphore for all campaigns**, because the window belongs to the SMSC connection, not to a campaign. Two campaigns running together share the window instead of each assuming it has all of it.
- **Fair semaphore**: permits are handed out FIFO, so a large campaign cannot starve a small one that started later.
- `acquire()` happens *before* the `try` whose `finally` releases, so a permit is released only if it was actually acquired.
- Virtual threads limit *threads*, not *concurrency against a resource*. The semaphore is what turns "100,000 threads" into "at most 50 requests in flight", which `CampaignServiceTest` verifies.

### 8.3 Coordinator and lifecycle
- Each campaign has a coordinator virtual thread that submits one task per recipient into a `newVirtualThreadPerTaskExecutor()` inside try-with-resources. `ExecutorService.close()` waits for all tasks, so "the coordinator finished" means "every message has an outcome".
- Running campaigns are tracked in a map of coordinator `Thread`s. Registering and starting the coordinator, and removing it on completion, are both done while holding the map's monitor. Without that, a very short campaign could finish and try to deregister *before* `start()` had registered it, leaving a stale entry forever.
- The coordinator is a plain virtual `Thread`, not a task submitted to an executor. `Future.cancel(true)` on a task that has not started running yet marks it cancelled and its body **never runs**, so the `finally` that deregisters the campaign and the loop that counts skipped messages would never execute. Interrupting a thread cannot prevent its body from running; the body sees the cancellation and cleans up. `CampaignServiceTest.cancelImmediatelyAfterStartStillAccountsForEveryRecipient` covers this; it fails reliably against the `Future`-based version.
- `@PreDestroy` interrupts every running coordinator.

### 8.4 Cancellation
- Status changes are CAS on `AtomicReference<CampaignStatus>`: `RUNNING → CANCELLED` and `RUNNING → COMPLETED` cannot both succeed. If cancel wins, the coordinator's later `complete()` is simply a no-op.
- `cancel()` then **interrupts the coordinator thread**. When an interrupted thread is inside `ExecutorService.close()`, close calls `shutdownNow()`, which interrupts every in-flight message task. Interruption therefore spreads from the campaign to every pending message.
- Messages that had not been sent yet (or were interrupted while waiting for the window or the SMSC) are counted as `skipped`, so `sent + failed + skipped = total` always holds.

### 8.5 `ScopedValue` for the campaign id
- The campaign id is bound with `ScopedValue` (final in Java 25) and read in log statements deep inside the SMSC gateway without passing it through every method.
- Preferred over `ThreadLocal`: it is immutable, automatically unbound when the scope ends (no leaks), and cheap to bind for hundreds of thousands of short-lived virtual threads.

### 8.6 SMSC gateway as a port
- `SmscGateway` is an interface (port); `SimulatedSmscGateway` is one adapter. A production adapter (e.g. jSMPP) can replace it without touching campaign logic.
- The simulation uses `Thread.sleep` for network latency. On a virtual thread, `sleep` unmounts it from its carrier exactly like blocking socket I/O would, so the simulation exercises the same scheduling behaviour as the real thing.
- `inFlight` / `maxInFlight` (`AtomicInteger`, `accumulateAndGet(Math::max)`) prove from the outside that the window is never exceeded.

---

## 9. Fraud detection (`fraud`)

### 9.1 Velocity check
- SIM-box and Wangiri fraud show up as one subscriber generating an unusual number of events per minute. Each CDR is checked against a per-subscriber fixed one-minute window; exceeding `max-events-per-minute` raises an alert.

### 9.2 Lock-free window counter
- Per subscriber: an immutable `Window(startMillis, count, alerted)` behind an `AtomicReference`, updated in a CAS retry loop. The fraud check runs inline on every rating worker; a lock here would serialise the workers on popular subscribers.
- The `alerted` flag lives inside the same immutable state as the counter, so "count exceeded **and** no alert raised yet" is decided atomically: exactly one alert per subscriber per window, which a test verifies under contention.
- The alert is pushed **after** the successful CAS, never inside the retry loop, so a retry can never produce a duplicate alert.
- A CDR for an older window than the current one (late delivery) is ignored rather than reopening a closed window.
- **Event time comes from the network element, so it is clamped to "now".** A CDR dated in the future would otherwise move the subscriber's window forward; every later (correct) event would then count as late and be ignored, and the purge job would never remove that window because its start lies in the future. One bad or malicious timestamp would blind detection for that subscriber permanently. Clamping counts such an event in the current window. `FraudFutureEventsTest` covers both effects.
- Windows untouched for 10 minutes are purged. A purge racing with an update can lose at most one count of a window that is already 10 minutes old, which is acceptable for a velocity heuristic and avoids locking the hot path.

### 9.3 Alerts on a Treiber stack (`LockFreeStack`)
- Analysts want the newest alert first: LIFO. The Treiber stack is the canonical lock-free LIFO: one `AtomicReference` head, push/pop via CAS.
- **ABA safety**: nodes are immutable and never reused; with garbage collection a node's address cannot be recycled while any thread still holds a reference, so the classic ABA problem cannot occur.
- `LockSupport.parkNanos(1)` after a failed CAS is a minimal back-off that reduces cache-line contention under heavy contention.
- "Acknowledge all" is `head.getAndSet(null)`: one atomic step detaches the whole stack, so no alert can be both acknowledged and still visible.
- `size` is a separate `AtomicInteger` and therefore only approximate under contention; it is used for metrics only, never for logic (`isEmpty()` reads the head directly).

---

## 10. Monitoring (`monitoring`)

- Counters on hot paths are `LongAdder`; min/max latency uses explicit CAS loops (`MinMaxMetric`) that exit early when the new value cannot improve the extreme, so the common case performs no write.
- `LatencyStats` combines both. Its snapshot is not one atomic read across all fields; for monitoring, approximate-but-cheap is the right trade-off.
- Thread statistics come from `ThreadMXBean`, which counts **platform threads only**. Virtual threads are deliberately invisible there, which makes the point of the campaign demo visible: 20,000 messages in flight while `liveThreads` stays around 30.

---

## 11. Testing strategy

- Concurrency tests build the object graph by hand (`TestFixtures`) without Spring: they start in milliseconds and can run many iterations.
- A **start gate** (`CountDownLatch`) releases all threads at the same instant to maximise contention; the first failure from any thread is rethrown in the test thread.
- Tests assert **invariants**, not timings: money is conserved, no overdraft, no deadlock, no money movement after a suspension returns, each item processed exactly once, each session settled exactly once, session responses are never torn, exactly one alert per window, in-flight messages ≤ window.
- A regression test for a race is only trusted after it has been seen to **fail** against the buggy code; both race tests above were verified that way.
- `ApiIntegrationTest` (MockMvc) covers the HTTP contract: status codes, validation messages and `ProblemDetail` bodies.

---

## 12. Container image

- **Multi-stage build**: Maven + JDK only in the build stage; the runtime image contains just a JRE and the application.
- Maven dependencies are resolved in their own layer before the sources are copied, so code changes do not re-download dependencies. A BuildKit cache mount keeps `~/.m2` between builds.
- The Spring Boot JAR is **extracted into layers** (`-Djarmode=tools extract --layers --launcher`). Dependencies rarely change, application classes change often; pushing or pulling a new version usually transfers only the small application layer.
- Runs as a **non-root** system user.
- `-XX:MaxRAMPercentage=75` sizes the heap from the container's memory limit; `-XX:+ExitOnOutOfMemoryError` makes the JVM exit on OOM so the orchestrator restarts it instead of leaving a half-broken process running.
- `ENTRYPOINT` in exec form makes `java` PID 1, so it receives `SIGTERM` directly and `server.shutdown: graceful` plus the pipeline's `SmartLifecycle` drain actually run on `docker stop`. `stop_grace_period: 30s` gives them time.
- The runtime image has neither `curl` nor `wget`; the Compose health check uses bash's `/dev/tcp` against `/actuator/health` instead of adding packages to the image.
- The host port is configurable (`HOST_PORT`, default 28989); the container always listens on 8080.

---

## 13. Known gaps and trade-offs

| Area | Gap | Why it is acceptable now / how to fix |
|---|---|---|
| Persistence | All state is in memory. | Matches how an OCS keeps hot balances; production adds write-behind to a database or a data grid. |
| Horizontal scaling | JVM locks protect one instance. | Partition by MSISDN (single writer per subscriber) or use conditional `UPDATE`s in the database. |
| Session expiry | `commit` after `expiresAt` is rejected with 409. The reserved credit stays blocked until the reaper closes the session (up to `reaper-interval`, 5 s). `release` is still accepted. | Strict expiry: a late CCR-Terminate cannot charge a session whose validity has ended. The unbilled usage of such a call is lost; a production OCS would need reservation renewal (below) or post-paid reconciliation. |
| Session lifetime | `extend` renews the deadline but there is no absolute maximum session length. | A session needs credit for every extension, so it cannot run for free on a priced service. Free services (price 0) can be extended indefinitely; add a maximum lifetime if that matters. |
| CDR duplicates | `recordId` is not used for de-duplication; resubmitting a batch rates and counts its records again. | The pipeline reports exactly which records were rejected, so a well-behaved client resends only those. A production mediation layer keeps a window of seen record ids (or a unique key in a database) and drops repeats. |
| Retention | Closed sessions, finished batches and idle fraud windows are purged (1 h, 1 h, 10 min). | Bounds memory; afterwards lookups return 404. Persist results if they must outlive the retention. |
| Housekeeping | Two daemon threads run every cleanup job. | Jobs are short, so two threads are enough, and one blocked job (the reaper waiting for a busy account) no longer stops the others. Two jobs blocking at once would still delay the rest; enlarge the pool (`SchedulingConfig.HOUSEKEEPING_THREADS`) if jobs are added that can block. |
| Input limits | CDR `units` is capped at 1,000,000,000 per record (`@Max`); `RatingEngine.billableUnits` uses `Math.multiplyExact`, so an overflow fails loudly instead of producing a negative cost. Tariff prices are capped at 1000 per unit (`@DecimalMax`). | Keeps a single CDR's cost (at most 10^9 units × 1000) far below the `long` limit of the minor-unit aggregates (`CdrBatch`, `SubscriberUsage`). The sum of many maximal CDRs could still exceed it (roughly 1000 CDRs at both maxima in one batch); real traffic is many orders of magnitude below that. |
| Listing | `findPage` sorts all subscribers per request; `countActive` scans all sessions. | This project is a demo, so simplicity wins over scalability at these volumes. For millions of rows use a sorted index (`ConcurrentSkipListMap`, key-based paging) and a `LongAdder` of active sessions updated on `save` and on a successful `close`, or a database. |
| Security | No authentication. | Spring Security / OAuth2 is the next step. |
