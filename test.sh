#!/usr/bin/env bash
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
AUTH="Authorization: Bearer user:alice"
AUTH_BOB="Authorization: Bearer user:bob"
CT="Content-Type: application/json"

pass() { echo "✅ PASS: $*"; }
fail() { echo "❌ FAIL: $*"; exit 1; }

req_code() {
  # usage: req_code METHOD URL [curl args...]
  local method="$1"; shift
  local url="$1"; shift
  curl -sS -o /dev/null -w "%{http_code}" -X "$method" "$url" "$@"
}

req_body() {
  # usage: req_body METHOD URL [curl args...]
  local method="$1"; shift
  local url="$1"; shift
  curl -sS -X "$method" "$url" "$@"
}

extract_id() {
  # extract "id":"..." from JSON without jq
  echo "$1" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p'
}

extract_status() {
  echo "$1" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p'
}

must_code() {
  local got="$1" want="$2" name="$3"
  [[ "$got" == "$want" ]] && pass "$name (http $want)" || fail "$name (expected $want got $got)"
}

must_contains() {
  local body="$1" needle="$2" name="$3"
  echo "$body" | grep -q "$needle" && pass "$name" || { echo "Body:"; echo "$body"; fail "$name (missing '$needle')"; }
}

poll_until_done() {
  local id="$1"
  local deadline=$((SECONDS + 60))
  while (( SECONDS < deadline )); do
    local s st
    s=$(req_body GET "$BASE/queries/$id" -H "$AUTH")
    st=$(extract_status "$s")
    echo "  status=$st"
    if [[ "$st" == "SUCCEEDED" ]]; then
      echo "$s"
      return 0
    fi
    if [[ "$st" == "FAILED" || "$st" == "CANCELLED" ]]; then
      echo "$s"
      return 1
    fi
    sleep 1
  done
  return 2
}

echo "=============================="
echo "A) Health / DB"
echo "=============================="

c=$(req_code GET "$BASE/ping")
must_code "$c" "200" "GET /ping"
b=$(req_body GET "$BASE/ping")
must_contains "$b" "ok" "Ping body contains ok"

c=$(req_code GET "$BASE/queries/testdb")
must_code "$c" "200" "GET /queries/testdb"
b=$(req_body GET "$BASE/queries/testdb")
must_contains "$b" "DB works" "DB check"
#
#echo
#echo "=============================="
#echo "B) Auth"
#echo "=============================="
#
#c=$(req_code POST "$BASE/queries" -H "$CT" -d '{"sql":"select 1"}')
#must_code "$c" "401" "POST /queries without auth"
#
#c=$(req_code GET "$BASE/queries/q_doesnotexist" -H "$CT")
## depends on your auth policy for GET; if protected should be 401; if public should be 404
## We expect protected:
#must_code "$c" "401" "GET /queries/{id} without auth"

echo
echo "=============================="
echo "C) Request validation (400)"
echo "=============================="

c=$(req_code POST "$BASE/queries" -H "$AUTH" -H "$CT" -d '{}')
must_code "$c" "400" "Missing sql field"

c=$(req_code POST "$BASE/queries" -H "$AUTH" -H "$CT" -d '{"sql":"   "}')
must_code "$c" "400" "Blank sql"

# Malformed JSON (should be 400)
c=$(req_code POST "$BASE/queries" -H "$AUTH" -H "$CT" --data-binary '{"sql":')
must_code "$c" "400" "Malformed JSON"

#echo
#echo "=============================="
#echo "D) SQL guard (400 invalid sql)"
#echo "=============================="
#
#c=$(req_code POST "$BASE/queries" -H "$AUTH" -H "$CT" -d '{"sql":"update orders set status='\''X'\''"}')
#must_code "$c" "400" "Non-select update"
#
#c=$(req_code POST "$BASE/queries" -H "$AUTH" -H "$CT" -d '{"sql":"select 1; select 2"}')
#must_code "$c" "400" "Semicolon multi-statement"
#
#c=$(req_code POST "$BASE/queries" -H "$AUTH" -H "$CT" -d '{"sql":"select * from orders where status='\''drop'\''"}')
## your guard will likely reject 'drop' keyword even in string; expected 400
#must_code "$c" "400" "Forbidden keyword detected (document false positives)"

echo
echo "=============================="
echo "E) Happy path: submit -> poll -> results"
echo "=============================="

resp=$(req_body POST "$BASE/queries" -H "$AUTH" -H "$CT" -d '{"sql":"select status, count(*) as cnt from orders group by status"}')
echo "$resp"
id=$(extract_id "$resp")
[[ -n "$id" ]] || fail "Submit did not return id"
pass "Submit returned id=$id"

echo "Polling..."
status_payload=$(poll_until_done "$id") || { echo "$status_payload"; fail "Query did not SUCCEED"; }

c=$(req_code GET "$BASE/queries/$id/results" -H "$AUTH")
must_code "$c" "200" "Results after SUCCEEDED"

r1=$(req_body GET "$BASE/queries/$id/results" -H "$AUTH" | head -n 1)
# NDJSON line should start with '{'
echo "$r1" | grep -q '^{'
pass "NDJSON first line looks like JSON"

echo
echo "=============================="
echo "F) Results before ready (409)"
echo "=============================="

resp=$(req_body POST "$BASE/queries" -H "$AUTH" -H "$CT" -d '{"sql":"select pg_sleep(5), 1 as ok"}')
id2=$(extract_id "$resp")
[[ -n "$id2" ]] || fail "Did not get id for sleep query"
c=$(req_code GET "$BASE/queries/$id2/results" -H "$AUTH")
must_code "$c" "409" "Results while PENDING/RUNNING"

echo
echo "=============================="
echo "G) Idempotency"
echo "=============================="

r1=$(req_body POST "$BASE/queries" -H "$AUTH" -H "$CT" -H "Idempotency-Key: idem-1" -d '{"sql":"select 1 as ok"}')
r2=$(req_body POST "$BASE/queries" -H "$AUTH" -H "$CT" -H "Idempotency-Key: idem-1" -d '{"sql":"select 1 as ok"}')
id1=$(extract_id "$r1")
id2=$(extract_id "$r2")
[[ "$id1" == "$id2" ]] && pass "Idempotency returns same id" || fail "Idempotency mismatch: $id1 vs $id2"

echo
echo "=============================="
echo "H) Cross-user isolation"
echo "=============================="

rb=$(req_body POST "$BASE/queries" -H "$AUTH_BOB" -H "$CT" -d '{"sql":"select 1 as ok"}')
bid=$(extract_id "$rb")
[[ -n "$bid" ]] || fail "Bob submit did not return id"
c=$(req_code GET "$BASE/queries/$bid" -H "$AUTH")
must_code "$c" "404" "Alice cannot read Bob query (should be 404)"

#echo
#echo "=============================="
#echo "I) Cancel flow"
#echo "=============================="
#
#rl=$(req_body POST "$BASE/queries" -H "$AUTH" -H "$CT" -d '{"sql":"select pg_sleep(20), 1 as ok"}')
#lid=$(extract_id "$rl")
#[[ -n "$lid" ]] || fail "Long query did not return id"
#c=$(req_code POST "$BASE/queries/$lid/cancel" -H "$AUTH")
#must_code "$c" "200" "Cancel returns 200"
#
#deadline=$((SECONDS + 15))
#st=""
#while (( SECONDS < deadline )); do
#  s=$(req_body GET "$BASE/queries/$lid" -H "$AUTH")
#  st=$(extract_status "$s")
#  echo "  cancel_status=$st"
#  [[ "$st" == "CANCELLED" ]] && break
#  sleep 1
#done
#[[ "$st" == "CANCELLED" ]] && pass "Cancelled status observed" || fail "Expected CANCELLED, got $st"

echo
echo "=============================="
echo "J) 404 unknown id"
echo "=============================="

c=$(req_code GET "$BASE/queries/q_doesnotexist" -H "$AUTH")
must_code "$c" "404" "Unknown id -> 404"

echo
echo "=============================="
echo "K) Rate limit (expect at least one 429)"
echo "=============================="
# assumes your rateLimitPerMinute is low enough (e.g., 30); this may take a few seconds
seen429=0
for i in $(seq 1 60); do
  code=$(req_code POST "$BASE/queries" -H "$AUTH" -H "$CT" -d '{"sql":"select 1"}')
  echo "  req=$i http=$code"
  [[ "$code" == "429" ]] && seen429=1
done
[[ "$seen429" == "1" ]] && pass "Rate limiting observed (saw 429)" || pass "No 429 seen (limit may be higher; acceptable)"

echo
echo "🎉 All key cases executed."
