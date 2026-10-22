#!/usr/bin/env bash
#
# Telco Charging Engine - end-to-end demo / smoke test.
#
# Starts a fresh instance of the application (isolated, known demo data; section 6 starts a second one with a
# short reservation validity so that expiry can be demonstrated), exercises every feature over HTTP and prints each request, each response and a
# PASS/FAIL line for every expectation. Exit code is 0 only if every expectation held.
#
#   ./scripts/demo.sh                    run every section
#   ./scripts/demo.sh --step             go section by section: before each one choose
#                                        [Enter] run, [s] skip, [a] run all remaining, [q] quit
#   ./scripts/demo.sh charging extend    run only the named sections
#   ./scripts/demo.sh --step cdr fraud   step through only the named sections
#   ./scripts/demo.sh --rebuild          rebuild the jar first
#   ./scripts/demo.sh --list             list sections with a short description
#
# Requires: java 25, curl, python3 (and maven, only if the jar has to be built).

ALL_SECTIONS="health tariffs subscribers transfers charging extend cdr fraud rerating campaigns concurrency errors metrics"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
describe() {
  case "$1" in
    health)      echo "health, OpenAPI document, demo tariffs and subscribers" ;;
    tariffs)     echo "tariff catalog: create, update, list, validation, price cap" ;;
    subscribers) echo "subscribers: activation, top-up, tariff change, suspend, paging" ;;
    transfers)   echo "Me2U credit transfers, money conservation, rejected transfers" ;;
    charging)    echo "online charging: reserve, commit, release, rounding, partial grant" ;;
    extend)      echo "long calls: extend, expiry of a non-extended session, the reaper (own instance, takes ~25 s)" ;;
    cdr)         echo "CDR mediation: batch rating, failures, usage aggregation, long polling, 200,000-CDR load" ;;
    fraud)       echo "fraud detection: 30 events/min threshold, alerts, far-future CDR" ;;
    rerating)    echo "what-if re-rating of 10,000,000 CDRs: sequential vs parallel, only one at a time" ;;
    campaigns)   echo "bulk SMS campaign to 100,000 numbers: SMPP window, virtual threads, cancellation" ;;
    concurrency) echo "thousands of parallel requests: no overspending, no deadlock, exactly-once settlement" ;;
    errors)      echo "malformed JSON, bad parameters, 404 / 405 / 415" ;;
    metrics)     echo "monitoring counters from the sections that ran, graceful shutdown" ;;
  esac
}

REBUILD=0
STEP=0
SELECTED=""
for arg in "$@"; do
  case "$arg" in
    --rebuild) REBUILD=1 ;;
    --step|-s) STEP=1 ;;
    --list) n=0; for s in $ALL_SECTIONS; do n=$((n+1)); printf "%2d  %-12s %s\n" "$n" "$s" "$(describe "$s")"; done; exit 0 ;;
    -h|--help) awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"; exit 0 ;;
    -*) echo "unknown option: $arg (see --help)" >&2; exit 2 ;;
    *) case " $ALL_SECTIONS " in *" $arg "*) SELECTED="$SELECTED $arg" ;; *) echo "unknown section: $arg (see --list)" >&2; exit 2 ;; esac ;;
  esac
done
[ -z "$SELECTED" ] && SELECTED="$ALL_SECTIONS"
EXECUTED=""
SKIPPED=""

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BLUE=$'\033[34m'; DIM=$'\033[2m'; BOLD=$'\033[1m'; RESET=$'\033[0m'
else
  RED=""; GREEN=""; YELLOW=""; BLUE=""; DIM=""; BOLD=""; RESET=""
fi

WORK="$(mktemp -d)"
LOG="$WORK/app.log"
PY="$WORK/h.py"
RESP="$WORK/resp"
APP_PID=""
PIDS=""
PASS=0
FAIL=0
FAILED_LIST=""
CURRENT=""
STATUS=""
BODY=""

cat > "$PY" <<'PYEOF'
import collections, json, os, re, socket, sys, urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal as D, InvalidOperation


def load():
    return json.loads(os.environ.get("BODY") or "null", parse_float=D)


def T(s):
    """ISO-8601 instant (any fraction length, trailing Z) -> epoch seconds."""
    from datetime import datetime, timezone
    m = re.match(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d+))?Z$", s)
    frac = ((m.group(2) or "0") + "000000")[:6]
    dt = datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
    return dt.timestamp() + int(frac) / 1e6


def show(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    return "null" if v is None else str(v)


cmd = sys.argv[1]
if cmd == "jv":
    cur = load()
    for tok in re.findall(r"[^.\[\]]+", sys.argv[2]):
        cur = cur[int(tok)] if isinstance(cur, list) else cur[tok]
    print(show(cur))
elif cmd == "eq":
    a, b = sys.argv[2], sys.argv[3]
    try:
        sys.exit(0 if D(a) == D(b) else 1)
    except InvalidOperation:
        sys.exit(0 if a == b else 1)
elif cmd == "expr":
    j = load()
    try:
        print("1" if eval(sys.argv[2]) else "0")
    except Exception as e:
        print("error: %s" % e)
elif cmd == "calc":
    print(eval(sys.argv[2]))
elif cmd == "sleepuntil":
    import time
    delay = T(sys.argv[2]) + float(sys.argv[3]) - time.time()
    if delay > 0:
        time.sleep(delay)
elif cmd == "port":
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    print(s.getsockname()[1])
elif cmd == "cpus":
    print(os.cpu_count() or 1)
elif cmd == "batch":
    n, msisdn, stype, units, when, prefix = sys.argv[2:8]
    print(json.dumps({"cdrs": [{"recordId": "%s-%d" % (prefix, i), "msisdn": msisdn, "serviceType": stype,
                                "units": int(units), "eventTime": when} for i in range(int(n))]}))
elif cmd == "cdrload":
    import time
    base, first, subs, batches, size, clients, when = sys.argv[2:9]
    first, subs, batches, size, clients = int(first), int(subs), int(batches), int(size), int(clients)

    def post(path, payload, timeout):
        rq = urllib.request.Request(base + path, data=json.dumps(payload).encode(), method="POST",
                                    headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(rq, timeout=timeout) as resp:
                return resp.status, json.loads(resp.read(), parse_float=D)
        except urllib.error.HTTPError as e:
            return e.code, None

    numbers = [str(first + i) for i in range(subs)]
    with ThreadPoolExecutor(20) as ex:
        list(ex.map(lambda n: post("/api/subscribers", {"msisdn": n, "name": "Load " + n, "tariffCode": "SMART",
                                                        "initialBalance": 0}, 30), numbers))

    def send(b):
        cdrs = [{"recordId": "load-%d-%d" % (b, i), "msisdn": numbers[(b * size + i) % subs], "serviceType": "SMS",
                 "units": 1, "eventTime": when} for i in range(size)]
        return post("/api/cdrs/batches?waitMillis=30000", {"cdrs": cdrs}, 120)

    started = time.time()
    with ThreadPoolExecutor(int(clients)) as ex:
        results = list(ex.map(send, range(batches)))
    elapsed = time.time() - started
    ok = [r for st, r in results if st == 202 and r]
    print(json.dumps({"requests": batches, "sent": batches * size, "errors": batches - len(ok),
                      "rated": sum(r["rated"] for r in ok), "failed": sum(r["failed"] for r in ok),
                      "rejected": sum(r["rejected"] for r in ok), "revenue": str(sum(D(str(r["revenue"])) for r in ok)),
                      "completed": sum(1 for r in ok if r["status"] == "COMPLETED"), "seconds": round(elapsed, 2)}))
elif cmd == "par":
    base, spec, threads = sys.argv[2], sys.argv[3], int(sys.argv[4])
    reqs = [l.rstrip("\n").split("\t") for l in open(spec) if l.strip()]

    def fire(r):
        m, p, b = r
        rq = urllib.request.Request(base + p, data=b.encode() if b else None, method=m,
                                    headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(rq, timeout=30) as resp:
                return str(resp.status)
        except urllib.error.HTTPError as e:
            return str(e.code)
        except Exception:
            return "error"

    with ThreadPoolExecutor(threads) as ex:
        print(json.dumps(collections.Counter(ex.map(fire, reqs))))
PYEOF

die() { printf "%s\n" "${RED}$*${RESET}" >&2; exit 2; }
selected() { case " $SELECTED " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
ran() { case " $EXECUTED " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

is_windows() { case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) return 0 ;; *) return 1 ;; esac; }

stop_app() { # pid - SIGTERM, wait up to 10 s, then SIGKILL
  local pid="$1" i=0
  [ -n "$pid" ] || return 0
  kill "$pid" 2>/dev/null
  while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 100 ]; do sleep 0.1; i=$((i+1)); done
  kill -9 "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null
}

cleanup() {
  local pid
  for pid in $PIDS; do stop_app "$pid"; done
}
trap cleanup EXIT

# ---------------------------------------------------------------------------------------------- output helpers
section() { CURRENT="$1"; printf "\n${BOLD}${BLUE}━━━ %s ━━━${RESET}\n" "$1"; }
note()    { printf "  ${DIM}%s${RESET}\n" "$1"; }
step()    { printf "\n  ${BOLD}%s${RESET}\n" "$1"; }
ok()      { PASS=$((PASS+1)); printf "    ${GREEN}✔${RESET} %s\n" "$1"; }
bad()     { FAIL=$((FAIL+1)); printf "    ${RED}✘ FAIL${RESET} %s\n" "$1"; FAILED_LIST="$FAILED_LIST
  - [$CURRENT] $1"; }
trunc()   { if [ "${#1}" -gt 260 ]; then printf "%s…" "${1:0:260}"; else printf "%s" "$1"; fi; }

call() { # METHOD PATH [BODY]   -> STATUS, BODY (no output)
  local m="$1" p="$2" b="${3-}"
  if [ -n "$b" ]; then
    STATUS=$(curl -s -m 60 -o "$RESP" -w '%{http_code}' -X "$m" "$BASE$p" -H 'Content-Type: application/json' -d "$b")
  else
    STATUS=$(curl -s -m 60 -o "$RESP" -w '%{http_code}' -X "$m" "$BASE$p")
  fi
  BODY=$(cat "$RESP")
}

req() { # METHOD PATH [BODY]   -> prints request and response
  printf "    ${YELLOW}→${RESET} %s %s %s\n" "$1" "$2" "$(trunc "${3-}")"
  call "$@"
  printf "    ${DIM}←${RESET} %s %s\n" "$STATUS" "$(trunc "$BODY")"
}

jv() { BODY="$BODY" python3 "$PY" jv "$1" 2>/dev/null; }
dec() { python3 "$PY" calc "$1"; }

expect_status() { if [ "$STATUS" = "$1" ]; then ok "$2 (HTTP $STATUS)"; else bad "$2 - expected HTTP $1, got $STATUS: $(trunc "$BODY")"; fi; }
expect_eq() { # path expected label
  local actual; actual=$(jv "$1")
  if python3 "$PY" eq "$actual" "$2"; then ok "$3  [$1 = $actual]"; else bad "$3 - $1: expected $2, got '$actual'"; fi
}
expect_val() { # actual expected label
  if python3 "$PY" eq "$1" "$2"; then ok "$3  [$1]"; else bad "$3 - expected $2, got '$1'"; fi
}
expect_expr() { # python expression over j (parsed body), label
  local out; out=$(BODY="$BODY" python3 "$PY" expr "$1")
  if [ "$out" = "1" ]; then ok "$2"; else bad "$2 - expression false ($out): $1 on $(trunc "$BODY")"; fi
}

wait_for() { # PATH python-expression seconds   (polls quietly, leaves STATUS/BODY of the last response)
  local path="$1" expr="$2" limit="$3" i=0
  while [ "$i" -lt $((limit * 5)) ]; do
    call GET "$path"
    [ "$(BODY="$BODY" python3 "$PY" expr "$expr")" = "1" ] && return 0
    sleep 0.2; i=$((i+1))
  done
  return 1
}

new_sub() { # msisdn tariff balance [name]
  call POST /api/subscribers "{\"msisdn\":\"$1\",\"name\":\"${4:-Demo user}\",\"tariffCode\":\"$2\",\"initialBalance\":$3}"
  if [ "$STATUS" = "201" ]; then note "setup: subscriber $1 on $2 with balance $3"; else bad "setup of subscriber $1 failed: HTTP $STATUS $BODY"; fi
}
now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }
batch_json() { python3 "$PY" batch "$@"; }
sub_balance() { call GET "/api/subscribers/$1"; }

# ---------------------------------------------------------------------------------------------- application
launch_app() { # session-ttl reaper-interval logfile   (empty = application defaults); sets BASE, APP_PID, LOG
  LOG="$3"
  command -v java >/dev/null || die "java is required"
  command -v curl >/dev/null || die "curl is required"
  command -v python3 >/dev/null || die "python3 is required"
  local jar
  jar=$(ls -t "$ROOT"/target/telco-charging-engine-*.jar 2>/dev/null | grep -v '\.original$' | head -1)
  if [ -z "$jar" ] || [ "$REBUILD" = 1 ]; then
    command -v mvn >/dev/null || die "no jar found and maven is not installed"
    note "building the application (mvn package -DskipTests) ..."
    (cd "$ROOT" && mvn -q -B package -DskipTests -Djacoco.skip=true) >"$WORK/build.log" 2>&1 || { cat "$WORK/build.log"; die "build failed"; }
    jar=$(ls -t "$ROOT"/target/telco-charging-engine-*.jar | grep -v '\.original$' | head -1)
  fi
  local port; port=$(python3 "$PY" port)
  BASE="http://localhost:$port"
  local opts=(--server.port="$port" --spring.main.banner-mode=off)
  [ -n "$1" ] && opts+=(--telco.charging.session-ttl="$1")
  [ -n "$2" ] && opts+=(--telco.charging.reaper-interval="$2")
  java -jar "$jar" "${opts[@]}" >"$LOG" 2>&1 </dev/null &
  APP_PID=$!
  PIDS="$PIDS $APP_PID"
  local i=0
  while [ "$i" -lt 120 ]; do
    kill -0 "$APP_PID" 2>/dev/null || { cat "$LOG"; die "application exited during startup"; }
    curl -s "$BASE/actuator/health" 2>/dev/null | grep -q '"UP"' && break
    sleep 0.5; i=$((i+1))
  done
  [ "$i" -lt 120 ] || die "application did not become healthy in 60 s (log: $LOG)"
  printf "${DIM}application: %s (pid %s), jar %s\nlog: %s${RESET}\n" "$BASE" "$APP_PID" "$(basename "$jar")" "$LOG"
}

# ================================================================================================ sections
s_health() {
  section "1. Health, API documentation and demo data"
  step "Application health"
  req GET /actuator/health
  expect_status 200 "health endpoint answers"
  expect_eq status UP "application is UP"
  step "OpenAPI document is published"
  req GET /v3/api-docs
  expect_status 200 "OpenAPI JSON available (Swagger UI: $BASE/swagger-ui.html)"
  expect_eq info.title "Telco Charging Engine API" "API title"
  step "Tariffs loaded at startup (BASIC, SMART, BUSINESS)"
  req GET /api/tariffs
  expect_status 200 "tariff list"
  expect_expr "{'BASIC','SMART','BUSINESS'} <= {t['code'] for t in j}" "all three demo tariffs exist"
  step "Subscribers loaded at startup"
  req GET "/api/subscribers?limit=100"
  expect_status 200 "subscriber list"
  expect_expr "{'38761000001','38761000002','38762000003','38765000004'} <= {s['msisdn'] for s in j}" "all four demo subscribers exist"
  req GET /api/subscribers/38761000001
  expect_eq name "Amra Hodžić" "demo subscriber details"
}

s_tariffs() {
  section "2. Tariff catalog"
  step "Create a tariff"
  req POST /api/tariffs '{"code":"API_PLAN","name":"Api plan","voicePricePerMinute":0.10,"smsPrice":0.05,"dataPricePerMb":0.01,"voiceBillingIncrementSeconds":30}'
  expect_status 201 "tariff created"
  step "Creating the same code again is a conflict"
  req POST /api/tariffs '{"code":"API_PLAN","name":"Dup","voicePricePerMinute":0.10,"smsPrice":0.05,"dataPricePerMb":0.01,"voiceBillingIncrementSeconds":30}'
  expect_status 409 "duplicate rejected"
  step "Validation: lowercase code, price above the 1000 cap, billing increment above 60 s"
  req POST /api/tariffs '{"code":"bad","name":"x","voicePricePerMinute":0.1,"smsPrice":0.05,"dataPricePerMb":0.01,"voiceBillingIncrementSeconds":30}'
  expect_status 400 "invalid code rejected"
  expect_expr "'code' in j['errors']" "error is reported for the field 'code'"
  req POST /api/tariffs '{"code":"EXPENSIVE","name":"x","voicePricePerMinute":1000.01,"smsPrice":0.05,"dataPricePerMb":0.01,"voiceBillingIncrementSeconds":30}'
  expect_status 400 "price above 1000 rejected"
  expect_eq errors.voicePricePerMinute "Price per minute can be at most 1000." "message names the limit"
  req POST /api/tariffs '{"code":"SLOW","name":"x","voicePricePerMinute":0.1,"smsPrice":0.05,"dataPricePerMb":0.01,"voiceBillingIncrementSeconds":61}'
  expect_status 400 "billing increment above 60 s rejected"
  step "Read, update and list"
  req GET /api/tariffs/API_PLAN
  expect_eq voiceBillingIncrementSeconds 30 "billing increment as created"
  req PUT /api/tariffs/API_PLAN '{"name":"Renamed plan","voicePricePerMinute":0.20,"smsPrice":0.06,"dataPricePerMb":0.02,"voiceBillingIncrementSeconds":1}'
  expect_status 200 "tariff updated (whole price list replaced atomically)"
  expect_eq name "Renamed plan" "new name"
  req GET /api/tariffs
  expect_expr "any(t['code']=='API_PLAN' for t in j)" "updated tariff is listed"
  step "Unknown tariffs"
  req GET /api/tariffs/NOPE
  expect_status 404 "get unknown tariff"
  req PUT /api/tariffs/NOPE '{"name":"x","voicePricePerMinute":0.2,"smsPrice":0.06,"dataPricePerMb":0.02,"voiceBillingIncrementSeconds":1}'
  expect_status 404 "update unknown tariff"
}

s_subscribers() {
  section "3. Subscribers"
  step "Activate a subscriber"
  req POST /api/subscribers '{"msisdn":"38761999003","name":"Test Korisnik","tariffCode":"SMART","initialBalance":5.00}'
  expect_status 201 "subscriber created"
  expect_eq availableBalance 5 "available balance equals the initial balance"
  step "Duplicate number, unknown tariff, invalid input"
  req POST /api/subscribers '{"msisdn":"38761999003","name":"Dup","tariffCode":"SMART","initialBalance":1}'
  expect_status 409 "duplicate number rejected"
  req POST /api/subscribers '{"msisdn":"38761999004","name":"X","tariffCode":"NOPE","initialBalance":1}'
  expect_status 400 "unknown tariff rejected"
  req POST /api/subscribers '{"msisdn":"123","name":"","tariffCode":"SMART","initialBalance":-1}'
  expect_status 400 "invalid data rejected"
  expect_expr "{'msisdn','name','initialBalance'} <= set(j['errors'])" "every invalid field is reported"
  step "Top-up"
  req POST /api/subscribers/38761999003/top-up '{"amount":2.50}'
  expect_status 200 "top-up accepted"
  expect_eq balance 7.5 "balance after top-up"
  req POST /api/subscribers/38761999003/top-up '{"amount":0}'
  expect_status 400 "zero top-up rejected"
  step "Change tariff"
  req PATCH /api/subscribers/38761999003/tariff '{"tariffCode":"BASIC"}'
  expect_status 200 "tariff changed"
  expect_eq tariffCode BASIC "subscriber is on BASIC"
  req PATCH /api/subscribers/38761999003/tariff '{"tariffCode":"NOPE"}'
  expect_status 400 "unknown tariff rejected"
  step "Suspend and re-activate"
  req PATCH /api/subscribers/38761999003/status '{"status":"SUSPENDED"}'
  expect_eq status SUSPENDED "subscriber suspended"
  req PATCH /api/subscribers/38761999003/status '{"status":"ACTIVE"}'
  expect_eq status ACTIVE "subscriber active again"
  step "Lookup and paging"
  req GET /api/subscribers/38760000000
  expect_status 404 "unknown subscriber"
  req GET "/api/subscribers?offset=0&limit=2"
  expect_expr "len(j) == 2 and j[0]['msisdn'] < j[1]['msisdn']" "page of 2, sorted by number"
  req GET "/api/subscribers?limit=0"
  expect_status 400 "limit 0 rejected"
}

s_transfers() {
  section "4. Me2U credit transfers"
  new_sub 38761999011 BASIC 100.00 "Sender"
  new_sub 38761999012 SMART 50.00 "Receiver"
  step "Transfer 25.00"
  req POST /api/subscribers/transfers '{"fromMsisdn":"38761999011","toMsisdn":"38761999012","amount":25.00}'
  expect_status 200 "transfer accepted"
  expect_eq from.balance 75 "sender balance"
  expect_eq to.balance 75 "receiver balance"
  step "Money is conserved"
  sub_balance 38761999011; local a; a=$(jv balance)
  sub_balance 38761999012; local b; b=$(jv balance)
  expect_val "$(dec "D('$a') + D('$b')")" 150 "sum of both balances is unchanged (100 + 50)"
  step "Rejected transfers"
  req POST /api/subscribers/transfers '{"fromMsisdn":"38761999011","toMsisdn":"38761999011","amount":1}'
  expect_status 400 "transfer to the same number"
  req POST /api/subscribers/transfers '{"fromMsisdn":"38761999012","toMsisdn":"38761999011","amount":500}'
  expect_status 402 "insufficient credit"
  req POST /api/subscribers/transfers '{"fromMsisdn":"38761999011","toMsisdn":"38761999012","amount":0}'
  expect_status 400 "zero amount"
  req POST /api/subscribers/transfers '{"fromMsisdn":"38760000000","toMsisdn":"38761999012","amount":1}'
  expect_status 404 "unknown sender"
  call PATCH /api/subscribers/38761999012/status '{"status":"SUSPENDED"}'
  req POST /api/subscribers/transfers '{"fromMsisdn":"38761999011","toMsisdn":"38761999012","amount":1}'
  expect_status 409 "recipient is suspended"
  call PATCH /api/subscribers/38761999012/status '{"status":"ACTIVE"}'
}

s_charging() {
  section "5. Online charging: reserve, commit, release"
  new_sub 38761999101 BASIC 10.00 "Caller"
  local C1=38761999101
  step "Reserve 10 minutes (600 s) of voice on BASIC (0.12 per minute, billed per started minute)"
  req POST /api/charging/sessions "{\"msisdn\":\"$C1\",\"serviceType\":\"VOICE\",\"requestedUnits\":600}"
  expect_status 201 "reservation created"
  expect_eq grantedUnits 600 "full request granted"
  expect_eq reservedAmount 1.2 "10 min x 0.12 = 1.20 reserved"
  local SID; SID=$(jv sessionId)
  sub_balance $C1
  expect_eq reservedAmount 1.2 "credit is blocked on the account"
  expect_eq availableBalance 8.8 "available = 10.00 - 1.20"
  step "Commit 90 s: billed 2 started minutes = 120 s"
  req POST "/api/charging/sessions/$SID/commit" '{"usedUnits":90}'
  expect_status 200 "commit accepted"
  expect_eq status COMMITTED "session committed"
  expect_eq chargedAmount 0.24 "charged 0.24, not the whole reservation"
  sub_balance $C1
  expect_eq balance 9.76 "balance = 10.00 - 0.24"
  expect_eq reservedAmount 0 "reservation fully released"
  step "A settled session cannot be settled again"
  req POST "/api/charging/sessions/$SID/commit" '{"usedUnits":90}'
  expect_status 409 "second commit rejected"
  req POST "/api/charging/sessions/$SID/release"
  expect_status 409 "release after commit rejected"
  req GET "/api/charging/sessions/$SID"
  expect_eq status COMMITTED "status is unchanged"

  step "Rounding differs per tariff: 61 s on SMART (per second) vs BASIC (per minute)"
  new_sub 38761999102 SMART 10.00 "Smart caller"
  call POST /api/charging/sessions '{"msisdn":"38761999102","serviceType":"VOICE","requestedUnits":120}'
  local S2; S2=$(jv sessionId)
  req POST "/api/charging/sessions/$S2/commit" '{"usedUnits":61}'
  expect_eq chargedAmount 0.0813 "SMART: 61 s x 0.08/60 = 0.0813"
  call POST /api/charging/sessions "{\"msisdn\":\"$C1\",\"serviceType\":\"VOICE\",\"requestedUnits\":120}"
  local S3; S3=$(jv sessionId)
  req POST "/api/charging/sessions/$S3/commit" '{"usedUnits":61}'
  expect_eq chargedAmount 0.24 "BASIC: 61 s is billed as 2 minutes = 0.24"

  step "Release without charging (call was not established)"
  req POST /api/charging/sessions "{\"msisdn\":\"$C1\",\"serviceType\":\"SMS\",\"requestedUnits\":5}"
  expect_eq reservedAmount 0.4 "5 SMS x 0.08 reserved"
  local S4; S4=$(jv sessionId)
  req POST "/api/charging/sessions/$S4/release"
  expect_eq status RELEASED "released"
  expect_eq chargedAmount 0 "nothing charged"
  sub_balance $C1
  expect_eq reservedAmount 0 "credit is available again"

  step "Partial grant: only 0.50 credit for a 600 s request on SMART (0.08/min)"
  new_sub 38761999103 SMART 0.50 "Almost empty"
  req POST /api/charging/sessions '{"msisdn":"38761999103","serviceType":"VOICE","requestedUnits":600}'
  expect_status 201 "reservation created"
  expect_eq grantedUnits 375 "granted 375 s (the most that 0.50 buys), not 600"
  expect_eq requestedUnits 600 "requested units are kept for reference"
  local S5; S5=$(jv sessionId)
  req POST /api/charging/sessions '{"msisdn":"38761999103","serviceType":"SMS","requestedUnits":1}'
  expect_status 402 "no credit left for a second reservation"
  call POST "/api/charging/sessions/$S5/release"

  step "No credit at all"
  new_sub 38761999104 BASIC 0 "Broke"
  req POST /api/charging/sessions '{"msisdn":"38761999104","serviceType":"SMS","requestedUnits":1}'
  expect_status 402 "payment required"

  step "Cannot use more than was granted"
  req POST /api/charging/sessions "{\"msisdn\":\"$C1\",\"serviceType\":\"SMS\",\"requestedUnits\":2}"
  local S6; S6=$(jv sessionId)
  req POST "/api/charging/sessions/$S6/commit" '{"usedUnits":5}'
  expect_status 400 "used units above granted units rejected"
  req POST "/api/charging/sessions/$S6/commit" '{"usedUnits":2}'
  expect_status 200 "commit with a valid amount still works afterwards"
  expect_eq chargedAmount 0.16 "2 SMS x 0.08"

  step "Unknown sessions and subscribers, suspended subscriber, validation"
  req GET /api/charging/sessions/does-not-exist
  expect_status 404 "unknown session"
  req POST /api/charging/sessions/does-not-exist/commit '{"usedUnits":1}'
  expect_status 404 "commit on unknown session"
  req POST /api/charging/sessions/does-not-exist/release
  expect_status 404 "release on unknown session"
  req POST /api/charging/sessions '{"msisdn":"38760000000","serviceType":"SMS","requestedUnits":1}'
  expect_status 404 "unknown subscriber"
  call PATCH /api/subscribers/$C1/status '{"status":"SUSPENDED"}'
  req POST /api/charging/sessions "{\"msisdn\":\"$C1\",\"serviceType\":\"SMS\",\"requestedUnits\":1}"
  expect_status 409 "suspended subscriber cannot reserve"
  call PATCH /api/subscribers/$C1/status '{"status":"ACTIVE"}'
  req POST /api/charging/sessions "{\"msisdn\":\"$C1\",\"serviceType\":\"SMS\",\"requestedUnits\":0}"
  expect_status 400 "zero units rejected"
  expect_expr "'requestedUnits' in j['errors']" "field is named in the error"
  req POST /api/charging/sessions "{\"msisdn\":\"$C1\",\"serviceType\":\"SMS\",\"requestedUnits\":10000001}"
  expect_status 400 "more than 10,000,000 units rejected"
  req POST /api/charging/sessions "{\"msisdn\":\"$C1\",\"requestedUnits\":1}"
  expect_status 400 "missing service type rejected"
}

s_extend() {
  section "6. Long calls: extend, expiry and the reaper (a separate instance, reservation validity 10 s)"
  note "starting a second instance with 10 s reservation validity and a 1 s reaper, so that expiry can be shown quickly"
  local main_base="$BASE" main_pid="$APP_PID" main_log="$LOG"
  launch_app 10s 1s "$WORK/app-extend.log"
  extend_scenario
  step "Counters of this instance"
  req GET /api/monitoring/metrics
  expect_expr "j['charging']['extensions'] >= 2 and j['charging']['expirations'] >= 1" "extensions and the expiry were counted"
  stop_app "$APP_PID"
  BASE="$main_base"; APP_PID="$main_pid"; LOG="$main_log"
}

extend_scenario() {
  new_sub 38761999201 SMART 25.00 "Long call"
  local E=38761999201
  step "Two sessions of 10 SMS (0.50 each) start together"
  req POST /api/charging/sessions "{\"msisdn\":\"$E\",\"serviceType\":\"SMS\",\"requestedUnits\":10}"
  local A; A=$(jv sessionId)
  local A_EXP; A_EXP=$(jv expiresAt)
  req POST /api/charging/sessions "{\"msisdn\":\"$E\",\"serviceType\":\"SMS\",\"requestedUnits\":10}"
  local B; B=$(jv sessionId)
  local B_EXP; B_EXP=$(jv expiresAt)
  note "waiting until 5 s after the sessions were created (their deadline is 10 s), then extending only session B ..."
  python3 "$PY" sleepuntil "$A_EXP" -5
  step "Extend B by 10 units: reserves only the difference and renews the deadline"
  req POST "/api/charging/sessions/$B/extend" '{"additionalUnits":10}'
  expect_status 200 "extension accepted"
  expect_eq grantedUnits 20 "granted 10 + 10"
  expect_eq reservedAmount 1 "reservation is now the price of 20 units = 1.00"
  expect_expr "T(j['expiresAt']) > T('$B_EXP') + 1" "deadline moved forward"
  sub_balance $E
  expect_eq reservedAmount 1.5 "account blocks 0.50 (A) + 1.00 (B)"
  step "Validation"
  req POST "/api/charging/sessions/$B/extend" '{"additionalUnits":0}'
  expect_status 400 "zero additional units rejected"
  req POST /api/charging/sessions/does-not-exist/extend '{"additionalUnits":1}'
  expect_status 404 "unknown session"
  note "waiting until A has passed its original deadline (B was extended by 5 s, so it is still valid) ..."
  python3 "$PY" sleepuntil "$A_EXP" 0.5
  step "A was not extended: it has expired"
  req POST "/api/charging/sessions/$A/commit" '{"usedUnits":10}'
  expect_status 409 "commit after expiry rejected"
  req POST "/api/charging/sessions/$A/extend" '{"additionalUnits":1}'
  expect_status 409 "an expired session cannot be revived"
  step "B was extended: it can still be committed, long after its first deadline"
  req POST "/api/charging/sessions/$B/commit" '{"usedUnits":20}'
  expect_status 200 "commit accepted"
  expect_eq chargedAmount 1 "20 SMS x 0.05 = 1.00"
  note "waiting for the reaper to close A ..."
  if wait_for "/api/charging/sessions/$A" "j['status']=='EXPIRED'" 15; then
    printf "    ${DIM}←${RESET} %s %s\n" "$STATUS" "$(trunc "$BODY")"
    ok "reaper closed session A as EXPIRED"
  else
    bad "session A was not expired by the reaper within 15 s: $(trunc "$BODY")"
  fi
  sub_balance $E
  expect_eq reservedAmount 0 "A's blocked credit was released"
  expect_eq balance 24 "only B was charged: 25.00 - 1.00"

  step "Partial extension and running out of credit (0.30 credit, SMS 0.05)"
  new_sub 38761999202 SMART 0.30 "Little credit"
  req POST /api/charging/sessions '{"msisdn":"38761999202","serviceType":"SMS","requestedUnits":4}'
  local P; P=$(jv sessionId)
  req POST "/api/charging/sessions/$P/extend" '{"additionalUnits":10}'
  expect_status 200 "extension granted partially"
  expect_eq grantedUnits 6 "6 units is all that 0.30 buys"
  expect_eq reservedAmount 0.3 "all credit is reserved"
  req POST "/api/charging/sessions/$P/extend" '{"additionalUnits":1}'
  expect_status 402 "no more credit: extension refused"
  req GET "/api/charging/sessions/$P"
  expect_eq grantedUnits 6 "a refused extension changes nothing"
  call POST "/api/charging/sessions/$P/release"

  step "Closed sessions and suspended subscribers cannot be extended"
  req POST "/api/charging/sessions/$P/extend" '{"additionalUnits":1}'
  expect_status 409 "released session cannot be extended"
  call POST /api/charging/sessions "{\"msisdn\":\"$E\",\"serviceType\":\"SMS\",\"requestedUnits\":1}"
  local Q; Q=$(jv sessionId)
  call PATCH /api/subscribers/$E/status '{"status":"SUSPENDED"}'
  req POST "/api/charging/sessions/$Q/extend" '{"additionalUnits":1}'
  expect_status 409 "suspended subscriber cannot extend"
  call PATCH /api/subscribers/$E/status '{"status":"ACTIVE"}'
  call POST "/api/charging/sessions/$Q/release"
}

s_cdr() {
  section "7. CDR mediation: batch intake, rating and usage aggregation"
  new_sub 38761999301 SMART 0 "CDR subscriber"
  local M=38761999301 NOW; NOW=$(now_iso)
  step "A mixed batch: 125 s voice, 2 SMS, 2048 KB data - and one record for an unknown number"
  local body="{\"cdrs\":[
    {\"recordId\":\"v1\",\"msisdn\":\"$M\",\"serviceType\":\"VOICE\",\"units\":125,\"eventTime\":\"$NOW\"},
    {\"recordId\":\"s1\",\"msisdn\":\"$M\",\"serviceType\":\"SMS\",\"units\":2,\"eventTime\":\"$NOW\"},
    {\"recordId\":\"d1\",\"msisdn\":\"$M\",\"serviceType\":\"DATA\",\"units\":2048,\"eventTime\":\"$NOW\"},
    {\"recordId\":\"x1\",\"msisdn\":\"38760000000\",\"serviceType\":\"SMS\",\"units\":1,\"eventTime\":\"$NOW\"}]}"
  req POST "/api/cdrs/batches?waitMillis=5000" "$body"
  expect_status 202 "batch accepted (and awaited for up to 5 s)"
  expect_eq status COMPLETED "every record has an outcome"
  expect_eq rated 3 "3 records rated"
  expect_eq failed 1 "1 record failed"
  expect_eq rejected 0 "none rejected (no back-pressure)"
  expect_eq revenue 0.2867 "0.1667 (voice) + 0.10 (SMS) + 0.02 (data)"
  expect_expr "len(j['failureSamples']) == 1 and 'does not exist' in j['failureSamples'][0]" "failure reason is reported"
  local BID; BID=$(jv batchId)
  step "Batch lookup and aggregated usage"
  req GET "/api/cdrs/batches/$BID"
  expect_eq status COMPLETED "batch can be read again"
  req GET "/api/cdrs/usage/$M"
  expect_eq voiceSeconds 125 "voice seconds"
  expect_eq smsCount 2 "SMS count"
  expect_eq dataKb 2048 "data KB"
  expect_eq cdrCount 3 "CDRs counted"
  expect_eq revenue 0.2867 "revenue"
  req GET "/api/cdrs/usage/top?limit=5"
  expect_expr "any(u['msisdn']=='$M' for u in j)" "subscriber appears in the revenue ranking"
  step "Failure samples are capped at 20 for a batch with 25 bad records"
  req POST "/api/cdrs/batches?waitMillis=5000" "$(batch_json 25 38760000000 SMS 1 "$NOW" bad)"
  expect_eq failed 25 "25 failures counted"
  expect_expr "len(j['failureSamples']) == 20" "but only 20 samples returned"
  step "Asynchronous submit, then long-polling for the result"
  req POST /api/cdrs/batches "$(batch_json 1 $M VOICE 60 "$NOW" async)"
  expect_status 202 "accepted without waiting"
  BID=$(jv batchId)
  req GET "/api/cdrs/batches/$BID?waitMillis=5000"
  expect_eq status COMPLETED "long poll returned the finished batch"
  step "Load: 200,000 CDRs in 20 batches of 10,000 (the API maximum), 8 clients in parallel, 100 subscribers"
  call GET /api/monitoring/metrics; local rated_before; rated_before=$(jv cdrPipeline.rated)
  note "creating 100 subscribers and posting 20 x 10,000 CDRs (buffer 50,000, 4 rating workers) ..."
  BODY=$(python3 "$PY" cdrload "$BASE" 38761990000 100 20 10000 8 "$NOW")
  printf "    ${DIM}result: %s${RESET}\n" "$BODY"
  expect_expr "j['errors'] == 0 and j['completed'] == 20" "all 20 requests were answered and every batch completed"
  expect_expr "j['rated'] + j['failed'] + j['rejected'] == j['sent'] == 200000" "every one of the 200,000 records has exactly one outcome (rated $(jv rated), failed $(jv failed), rejected $(jv rejected))"
  expect_expr "D(j['revenue']) == j['rated'] * D('0.05')" "revenue equals rated records x 0.05 (SMART SMS price), to the last decimal"
  printf "    ${DIM}throughput: %s CDRs/s (%s s)${RESET}\n" "$(python3 "$PY" calc "round(200000 / D('$(jv seconds)'))")" "$(jv seconds)"
  local rated_load; rated_load=$(jv rated)
  call GET /api/monitoring/metrics
  expect_val "$(python3 "$PY" calc "$(jv cdrPipeline.rated) - $rated_before")" "$rated_load" "pipeline counter grew by exactly the number of rated records"
  req GET "/api/cdrs/usage/top?limit=100"
  expect_expr "sum(u['cdrCount'] for u in j if u['msisdn'].startswith('387619900')) == $rated_load" "usage aggregated per subscriber adds up to the rated records (no lost updates)"
  step "Validation and unknown ids"
  req POST /api/cdrs/batches '{"cdrs":[]}'
  expect_status 400 "empty batch rejected"
  req POST /api/cdrs/batches "$(batch_json 1 $M VOICE 1000000001 "$NOW" big)"
  expect_status 400 "units above 1,000,000,000 rejected"
  req POST /api/cdrs/batches "$(batch_json 1 $M VOICE -5 "$NOW" neg)"
  expect_status 400 "negative units rejected"
  req POST /api/cdrs/batches '{"cdrs":[{"recordId":"m1","serviceType":"SMS","units":1,"eventTime":"2026-01-01T00:00:00Z"}]}'
  expect_status 400 "missing number rejected"
  req GET "/api/cdrs/batches/nope"
  expect_status 404 "unknown batch"
  req GET "/api/cdrs/batches/nope?waitMillis=abc"
  expect_status 400 "non-numeric waitMillis rejected"
  req GET "/api/cdrs/usage/38760000999"
  expect_status 404 "no usage recorded"
  req GET "/api/cdrs/usage/top?limit=0"
  expect_status 400 "limit 0 rejected"
  note "Back-pressure (batch records rejected when the buffer is full) is covered by CdrPipelineEdgeCasesTest."
}

s_fraud() {
  section "8. Fraud detection: more than 30 events per minute"
  local NOW; NOW=$(now_iso)
  step "Start with an empty alert list"
  req POST /api/fraud/alerts/acknowledge-all
  expect_status 200 "all alerts acknowledged"
  req GET /api/fraud/alerts
  expect_expr "j == []" "no open alerts"
  step "30 events in one minute: no alert (the threshold is exceeded only above 30)"
  new_sub 38761999401 SMART 0 "Normal user"
  call POST "/api/cdrs/batches?waitMillis=5000" "$(batch_json 30 38761999401 SMS 1 "$NOW" ok)"
  req GET /api/fraud/alerts
  expect_expr "j == []" "still no alert"
  step "35 events in one minute: exactly one alert for the window"
  new_sub 38761999402 SMART 0 "SIM-box"
  req POST "/api/cdrs/batches?waitMillis=5000" "$(batch_json 35 38761999402 SMS 1 "$NOW" box)"
  expect_eq rated 35 "all 35 CDRs rated"
  req GET /api/fraud/alerts
  expect_expr "len(j) == 1 and j[0]['msisdn']=='38761999402' and j[0]['eventsInWindow']==31" "one alert, raised at the 31st event"
  expect_expr "'Wangiri' in j[0]['description']" "alert explains the suspicion"
  step "A CDR dated in the far future must not blind detection"
  new_sub 38761999403 SMART 0 "Time traveller"
  call POST "/api/cdrs/batches?waitMillis=5000" "$(batch_json 1 38761999403 SMS 1 2126-01-01T00:00:00Z future)"
  req POST "/api/cdrs/batches?waitMillis=5000" "$(batch_json 35 38761999403 SMS 1 "$NOW" late)"
  req GET /api/fraud/alerts
  expect_expr "len(j) == 2 and j[0]['msisdn']=='38761999403'" "alert still raised; newest alert comes first"
  step "Acknowledge the newest alert, then all the rest"
  req POST /api/fraud/alerts/acknowledge-latest
  expect_eq msisdn 38761999403 "newest alert acknowledged"
  req POST /api/fraud/alerts/acknowledge-all
  expect_expr "len(j) == 1 and j[0]['msisdn']=='38761999402'" "remaining alert acknowledged atomically"
  req POST /api/fraud/alerts/acknowledge-latest
  expect_status 404 "nothing left to acknowledge"
  req GET "/api/fraud/alerts?limit=0"
  expect_status 400 "limit 0 rejected"
}

s_rerating() {
  section "9. What-if re-rating: sequential vs parallel"
  local cpus threads; cpus=$(python3 "$PY" cpus); threads=4; [ "$cpus" -lt 2 ] && threads=2
  note "this machine reports $cpus cores; using $threads threads"
  step "Re-rate 10,000,000 synthetic CDRs (the maximum) on SMART"
  req POST /api/cdrs/rerating-simulations "{\"tariffCode\":\"SMART\",\"cdrCount\":10000000,\"threads\":$threads,\"seed\":42}"
  expect_status 200 "simulation finished"
  expect_eq resultsMatch true "parallel total equals sequential total (exact integer arithmetic)"
  printf "    ${DIM}sequential %s ms, parallel %s ms, speed-up x%s${RESET}\n" "$(jv sequentialMillis)" "$(jv parallelMillis)" "$(jv speedup)"
  step "Same seed, same result: the simulation is deterministic"
  local first; first=$(jv totalRevenue)
  call POST /api/cdrs/rerating-simulations "{\"tariffCode\":\"SMART\",\"cdrCount\":10000000,\"threads\":2,\"seed\":42}"
  expect_val "$(jv totalRevenue)" "$first" "total revenue is identical with a different thread count"
  step "Validation"
  req POST /api/cdrs/rerating-simulations '{"tariffCode":"SMART","cdrCount":1000,"threads":0,"seed":1}'
  expect_status 400 "0 threads rejected"
  req POST /api/cdrs/rerating-simulations '{"tariffCode":"SMART","cdrCount":1000,"threads":1000000,"seed":1}'
  expect_status 400 "more than 2 x cores threads rejected"
  req POST /api/cdrs/rerating-simulations '{"tariffCode":"SMART","cdrCount":1,"threads":2,"seed":1}'
  expect_status 400 "fewer CDRs than threads rejected"
  req POST /api/cdrs/rerating-simulations '{"tariffCode":"NOPE","cdrCount":1000,"threads":2,"seed":1}'
  expect_status 404 "unknown tariff"
  step "Only one simulation at a time: a second one is refused immediately"
  curl -s -m 120 -o "$WORK/long.out" -w '%{http_code}' -X POST "$BASE/api/cdrs/rerating-simulations" \
       -H 'Content-Type: application/json' -d '{"tariffCode":"SMART","cdrCount":10000000,"threads":1,"seed":7}' >"$WORK/long.status" &
  local long_pid=$! i=0 got=""
  sleep 0.3
  while [ "$i" -lt 30 ]; do
    call POST /api/cdrs/rerating-simulations '{"tariffCode":"SMART","cdrCount":10,"threads":1,"seed":1}'
    got="$STATUS"; [ "$STATUS" = "503" ] && break
    sleep 0.1; i=$((i+1))
  done
  printf "    ${YELLOW}→${RESET} POST /api/cdrs/rerating-simulations (while the first one is running)\n    ${DIM}←${RESET} %s %s\n" "$STATUS" "$(trunc "$BODY")"
  expect_val "$got" 503 "second simulation refused with 503"
  wait "$long_pid"
  expect_val "$(cat "$WORK/long.status")" 200 "the first simulation still finished normally"
}

s_campaigns() {
  section "10. Bulk SMS campaigns: virtual threads and the SMPP window (max 50 in flight)"
  step "A small campaign with an explicit recipient list"
  req POST /api/campaigns '{"name":"Welcome","message":"Dobrodosli!","recipients":["38761000001","38761000002","38761000003"]}'
  expect_status 202 "campaign accepted, runs asynchronously"
  expect_eq totalRecipients 3 "3 recipients"
  local SMALL; SMALL=$(jv campaignId)
  if wait_for "/api/campaigns/$SMALL" "j['status']=='COMPLETED'" 10; then
    printf "    ${DIM}←${RESET} %s %s\n" "$STATUS" "$(trunc "$BODY")"
    ok "campaign completed"
  else bad "small campaign did not complete in 10 s: $(trunc "$BODY")"; fi
  expect_expr "j['sent'] + j['failed'] == 3 and j['skipped'] == 0 and j['progressPercent'] == 100" "every message has an outcome (sent + failed = total)"

  step "A 100,000-number range (the maximum): watch the window and the thread count while it runs"
  req POST /api/campaigns '{"name":"Autumn promo","message":"Double data!","numberRange":{"firstMsisdn":"38761000000","count":100000}}'
  expect_eq totalRecipients 100000 "range expanded to 100,000 recipients"
  local BIG; BIG=$(jv campaignId)
  local i=0 max_in_flight=0 max_threads=0 v t
  while [ "$i" -lt 6 ]; do
    sleep 0.25
    call GET /api/monitoring/metrics
    v=$(jv smsc.inFlight); t=$(jv jvm.liveThreads)
    printf "    ${DIM}sample %d: messages in flight = %s, live platform threads = %s, waiting for the window = %s${RESET}\n" \
      $((i+1)) "$v" "$t" "$(jv campaigns.messagesWaitingForWindow)"
    [ "$v" -gt "$max_in_flight" ] && max_in_flight=$v
    [ "$t" -gt "$max_threads" ] && max_threads=$t
    i=$((i+1))
  done
  expect_expr "$max_in_flight > 0 and $max_in_flight <= 50" "in-flight messages never exceed the SMPP window (max seen: $max_in_flight)" 
  expect_expr "$max_threads < 100" "100,000 messages without 100,000 platform threads (max live threads: $max_threads)"

  step "Cancel the running campaign"
  req DELETE "/api/campaigns/$BIG"
  expect_status 200 "cancel accepted"
  expect_eq status CANCELLED "status is CANCELLED"
  if wait_for "/api/campaigns/$BIG" "j['sent']+j['failed']+j['skipped']==100000" 60; then
    printf "    ${DIM}←${RESET} %s %s\n" "$STATUS" "$(trunc "$BODY")"
    ok "every message accounted for after cancellation (sent + failed + skipped = 100,000)"
  else bad "messages not fully accounted for: $(trunc "$BODY")"; fi
  expect_expr "j['skipped'] > 0" "unsent messages were skipped, not lost"

  step "Rules"
  req DELETE "/api/campaigns/$SMALL"
  expect_status 409 "a finished campaign cannot be cancelled"
  req GET /api/campaigns
  expect_expr "len(j) >= 2" "campaign list (newest first)"
  req GET /api/campaigns/nope
  expect_status 404 "unknown campaign"
  req DELETE /api/campaigns/nope
  expect_status 404 "cancel unknown campaign"
  req POST /api/campaigns '{"name":"Both","message":"x","recipients":["38761000001"],"numberRange":{"firstMsisdn":"38761000000","count":3}}'
  expect_status 400 "list and range together rejected"
  req POST /api/campaigns '{"name":"Neither","message":"x"}'
  expect_status 400 "neither list nor range rejected"
  req POST /api/campaigns '{"name":"Bad","message":"x","recipients":["12345"]}'
  expect_status 400 "recipient with a bad format rejected"
  req POST /api/campaigns '{"name":"Huge","message":"x","numberRange":{"firstMsisdn":"38761000000","count":100001}}'
  expect_status 400 "range above 100,000 numbers rejected"
}

s_concurrency() {
  section "11. Concurrency: many simultaneous requests, invariants must hold"
  step "5,000 parallel reservations of 1 SMS (0.05) on an account with 50.00 credit: at most 1,000 can succeed"
  new_sub 38761999501 SMART 50.00 "Contended"
  local spec="$WORK/spec1" n
  : > "$spec"
  for n in $(seq 1 5000); do
    printf 'POST\t/api/charging/sessions\t{"msisdn":"38761999501","serviceType":"SMS","requestedUnits":1}\n' >> "$spec"
  done
  BODY=$(python3 "$PY" par "$BASE" "$spec" 100)
  printf "    ${DIM}HTTP status counts: %s${RESET}\n" "$BODY"
  local granted; granted=$(jv 201); [ "$granted" = "null" ] && granted=0
  expect_expr "$granted <= 1000 and set(j) <= {'201','402','503'}" "at most 1,000 granted ($granted), the rest refused with 402 or 503"
  sub_balance 38761999501
  expect_val "$(jv reservedAmount)" "$(dec "D('0.05') * $granted")" "reserved amount equals exactly granted x 0.05"
  expect_expr "j['availableBalance'] >= 0" "available balance never negative (no overspending)"

  step "4,000 parallel transfers in opposite directions between two accounts (deadlock test)"
  new_sub 38761999502 BASIC 10000.00 "Account P"
  new_sub 38761999503 BASIC 10000.00 "Account Q"
  : > "$spec"
  for n in $(seq 1 2000); do
    printf 'POST\t/api/subscribers/transfers\t{"fromMsisdn":"38761999502","toMsisdn":"38761999503","amount":1.00}\n' >> "$spec"
    printf 'POST\t/api/subscribers/transfers\t{"fromMsisdn":"38761999503","toMsisdn":"38761999502","amount":1.00}\n' >> "$spec"
  done
  BODY=$(python3 "$PY" par "$BASE" "$spec" 100)
  printf "    ${DIM}HTTP status counts: %s${RESET}\n" "$BODY"
  expect_expr "'error' not in j and set(j) <= {'200','503'}" "every request finished (no deadlock, no hung request)"
  sub_balance 38761999502; local p; p=$(jv balance)
  sub_balance 38761999503; local q; q=$(jv balance)
  expect_val "$(dec "D('$p') + D('$q')")" 20000 "total money is conserved (P + Q = 20,000.00)"

  step "1,000 parallel commits and releases of the same session: exactly one settles it"
  new_sub 38761999504 BASIC 10.00 "Settle race"
  call POST /api/charging/sessions '{"msisdn":"38761999504","serviceType":"SMS","requestedUnits":5}'
  local sid; sid=$(jv sessionId)
  : > "$spec"
  for n in $(seq 1 500); do
    printf 'POST\t/api/charging/sessions/%s/commit\t{"usedUnits":5}\n' "$sid" >> "$spec"
    printf 'POST\t/api/charging/sessions/%s/release\t\n' "$sid" >> "$spec"
  done
  BODY=$(python3 "$PY" par "$BASE" "$spec" 100)
  printf "    ${DIM}HTTP status counts: %s${RESET}\n" "$BODY"
  expect_expr "j.get('200', 0) == 1 and j.get('409', 0) + j.get('503', 0) == 999" "exactly one request settled the session, the other 999 got 409 (or 503)"
  sub_balance 38761999504
  expect_eq reservedAmount 0 "reservation released exactly once"
  expect_expr "j['balance'] in (D('10'), D('9.6'))" "balance is either untouched (released) or charged once (9.60)"
}

s_errors() {
  section "12. Error handling"
  step "Malformed and unsupported requests"
  req POST /api/subscribers '{not json'
  expect_status 400 "malformed JSON"
  expect_eq detail "Request body is not valid JSON." "clear message"
  req GET "/api/subscribers?limit=abc"
  expect_status 400 "non-numeric parameter"
  req GET /api/does-not-exist
  expect_status 404 "unknown endpoint"
  req DELETE /api/tariffs
  expect_status 405 "method not allowed"
  call_text=$(curl -s -m 30 -o /dev/null -w '%{http_code}' -X POST "$BASE/api/subscribers" -H 'Content-Type: text/plain' -d 'hello')
  printf "    ${YELLOW}→${RESET} POST /api/subscribers (Content-Type: text/plain)\n    ${DIM}←${RESET} %s\n" "$call_text"
  expect_val "$call_text" 415 "unsupported media type"
  note "Business errors seen in the earlier sections: 402 insufficient credit, 404 not found, 409 invalid state, 503 busy."
}

s_metrics() {
  section "13. Monitoring"
  req GET /api/monitoring/metrics
  expect_status 200 "metrics snapshot"
  expect_eq cdrPipeline.running true "CDR pipeline is running"
  expect_expr "j['smsc']['maxInFlightObserved'] <= 50" "SMSC window was never exceeded (max in flight: $(jv smsc.maxInFlightObserved))"
  ran charging && expect_expr "j['charging']['commits'] >= 4 and j['charging']['releases'] >= 2" "commits and releases were counted"
  ran cdr && expect_expr "j['cdrPipeline']['rated'] >= 3 and j['cdrPipeline']['failed'] >= 26" "CDRs rated and failed were counted"
  ran fraud && expect_expr "j['fraud']['alertsRaised'] >= 2" "fraud alerts were counted"
  printf "    ${DIM}charging: %s\n    pipeline: %s\n    jvm: %s${RESET}\n" "$(jv charging)" "$(jv cdrPipeline)" "$(jv jvm)"
}

# ================================================================================================ main
printf "${BOLD}Telco Charging Engine - demo and end-to-end checks${RESET}\n"
launch_app "" "" "$LOG"
note "reservation validity is the application default (5 min); section 6 starts its own instance with a short one"

ask() { # section index total -> sets ANSWER (re-asks until the choice is valid)
  printf "\n${BOLD}Next section %s/%s: %s${RESET} - %s\n" "$2" "$3" "$1" "$(describe "$1")"
  while true; do
    printf "  [Enter] run   [s] skip   [a] run all remaining   [q] quit > "
    if ! read -r ANSWER; then ANSWER="q"; printf "\n"; note "no more input - quitting"; return; fi
    case "$ANSWER" in ""|s|S|a|A|q|Q) return ;; esac
    note "unknown choice '$ANSWER' - press Enter, s, a or q"
  done
}

total=0; for s in $ALL_SECTIONS; do selected "$s" && total=$((total+1)); done
idx=0; AUTO=0; STOPPED=0
for s in $ALL_SECTIONS; do
  selected "$s" || continue
  idx=$((idx+1))
  if [ "$STEP" = 1 ] && [ "$AUTO" = 0 ]; then
    ask "$s" "$idx" "$total"
    case "$ANSWER" in
      s|S) SKIPPED="$SKIPPED $s"; note "skipped $s"; continue ;;
      a|A) AUTO=1 ;;
      q|Q) STOPPED=1; break ;;
    esac
  fi
  before_pass=$PASS; before_fail=$FAIL
  "s_$s"
  EXECUTED="$EXECUTED $s"
  printf "\n  ${DIM}section %s: %d checks passed, %d failed${RESET}\n" "$s" $((PASS-before_pass)) $((FAIL-before_fail))
done

section "Shutdown"
stop_app "$APP_PID"
APP_PID=""
if is_windows; then
  note "graceful shutdown is not verified on Windows: stopping a process there does not run the JVM shutdown hooks"
elif grep -q "CDR pipeline stopped" "$LOG"; then
  ok "graceful shutdown: the CDR pipeline drained and stopped"
else
  bad "no graceful shutdown message in the log"
fi
warns=$(grep -cE "^[0-9T:.+-]+ +WARN" "$LOG")
errors=$(grep -cE "^[0-9T:.+-]+ +ERROR" "$LOG")
note "application log: $warns WARN lines (fraud alerts are logged at WARN), $errors ERROR lines"
if [ "$errors" -gt 0 ]; then grep -E "^[0-9T:.+-]+ +ERROR" "$LOG" | head -5; fi

TOTAL=$((PASS+FAIL))
printf "\n${BOLD}━━━ Summary ━━━${RESET}\n"
printf "sections run:%s\n" "${EXECUTED:- none}"
[ -n "$SKIPPED" ] && printf "sections skipped:%s\n" "$SKIPPED"
[ "$STOPPED" = 1 ] && printf "${YELLOW}stopped early at your request${RESET}\n"
if [ "$FAIL" -eq 0 ]; then
  printf "${GREEN}${BOLD}ALL %d CHECKS PASSED${RESET}\n" "$TOTAL"
else
  printf "${RED}${BOLD}%d of %d CHECKS FAILED${RESET}%s\n" "$FAIL" "$TOTAL" "$FAILED_LIST"
fi
if [ "$FAIL" -eq 0 ]; then
  rm -rf "$WORK"
  exit 0
fi
printf "${DIM}application log kept for inspection: %s${RESET}\n" "$LOG"
exit 1
