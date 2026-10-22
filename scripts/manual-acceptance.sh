#!/usr/bin/env bash
set -euo pipefail

# Start the service first. Override BASE_URL to test a different local port.
BASE_URL=${BASE_URL:-http://127.0.0.1:8080}
RUN_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
A="accept-${RUN_ID}-a"
B="accept-${RUN_ID}-b"
C="accept-${RUN_ID}-c"
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

request() {
  local expected=$1 method=$2 path=$3 data=${4-} code
  if [[ -n "$data" ]]; then
    code=$(curl -sS -o "$WORK_DIR/response.json" -w '%{http_code}' -X "$method" \
      -H 'Content-Type: application/json' --data "$data" "$BASE_URL$path")
  else
    code=$(curl -sS -o "$WORK_DIR/response.json" -w '%{http_code}' -X "$method" "$BASE_URL$path")
  fi
  if [[ "$code" != "$expected" ]]; then
    printf 'Expected HTTP %s, got %s for %s %s\n' "$expected" "$code" "$method" "$path" >&2
    cat "$WORK_DIR/response.json" >&2
    exit 1
  fi
  cat "$WORK_DIR/response.json"
}

wait_done() {
  local id=$1 status
  for _ in {1..60}; do
    request 200 GET "/api/documents/$id/task" > "$WORK_DIR/task.json"
    status=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' < "$WORK_DIR/task.json")
    if [[ "$status" == SUCCEEDED ]]; then return; fi
    if [[ "$status" == FAILED ]]; then cat "$WORK_DIR/task.json" >&2; exit 1; fi
    sleep 1
  done
  printf 'Task for %s did not finish within 60 seconds\n' "$id" >&2
  exit 1
}

printf 'Submitting %s, %s, %s\n' "$A" "$B" "$C"
request 202 POST /api/documents "{\"documentId\":\"$A\",\"text\":\"Java 后端\"}" > /dev/null
request 202 POST /api/documents "{\"documentId\":\"$B\",\"text\":\"中文渠道\",\"channel\":\"招聘\"}" > /dev/null
request 202 POST /api/documents "{\"documentId\":\"$C\",\"text\":\"\"}" > /dev/null
wait_done "$A"
wait_done "$B"
wait_done "$C"

request 409 POST /api/documents "{\"documentId\":\"$A\",\"text\":\"duplicate\"}" > /dev/null
request 200 POST /api/search '{"text":"","topK":2,"channel":"default"}' > "$WORK_DIR/search.json"
python3 - "$A" "$C" "$WORK_DIR/search.json" <<'PY'
import json, sys
a, c, path = sys.argv[1:]
body = json.load(open(path, encoding="utf-8"))
assert body["returnedCount"] == 2, body
assert [hit["document"]["documentId"] for hit in body["results"]] == sorted([a, c]), body
assert all(hit["score"] == 0 for hit in body["results"]), body
PY

request 200 PUT "/api/documents/$A/invalidation" > "$WORK_DIR/invalidated.json"
request 200 POST /api/search '{"text":"Java","topK":2,"channel":"default"}' > "$WORK_DIR/after.json"
request 200 GET "/api/documents/$A" > "$WORK_DIR/document.json"
python3 - "$A" "$B" "$C" "$WORK_DIR/invalidated.json" "$WORK_DIR/after.json" "$WORK_DIR/document.json" <<'PY'
import json, sys
a, b, c, invalid_path, after_path, document_path = sys.argv[1:]
invalid = json.load(open(invalid_path, encoding="utf-8"))
after = json.load(open(after_path, encoding="utf-8"))
document = json.load(open(document_path, encoding="utf-8"))
assert invalid["invalidated"] is True and invalid["invalidatedAt"], invalid
assert [hit["document"]["documentId"] for hit in after["results"]] == [c], after
assert document["hitCount"] == 1 and document["invalidated"] is True, document
print("PASS: async tasks, duplicate ID, Chinese channel, empty query Top-K, hit count, invalidation")
print("Document IDs:", a, b, c)
PY
