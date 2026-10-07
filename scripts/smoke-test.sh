#!/usr/bin/env bash
# Smoke test of mychart-danish-proxy against a live MinSundhedsplatform (MyChart) session.
#
#   ./scripts/smoke-test.sh [port]        (default port 8080)
#
# Cookie and __RequestVerificationToken are read from the macOS clipboard (pbpaste), so
# they never pass through the terminal line editor (which truncates long pastes and can
# insert invisible separators) and never land in shell history. Output is status codes and
# result counts only - no health data is printed.
set -u
PORT="${1:-8080}"
BASE="http://localhost:${PORT}/fhir"

clip() {
  if command -v pbpaste >/dev/null 2>&1; then pbpaste
  elif command -v xclip >/dev/null 2>&1; then xclip -o -selection clipboard
  else echo "No clipboard tool (pbpaste/xclip) found" >&2; exit 1; fi
}

# Strip line/paragraph separators, CR/LF, surrounding quotes and <>, and a pasted "Name:" prefix.
clean() {
  perl -0777 -pe 's/\xE2\x80[\xA8\xA9]//g; s/[\r\n\t]//g; s/^\s+|\s+$//g; s/^(cookie|__requestverificationtoken)\s*:\s*//i; s/^["\x27<]+|["\x27>]+$//g'
}

echo "1) Checking that mychart-danish-proxy is running on port ${PORT} ..."
code=$(curl -s -o /dev/null -w '%{http_code}' "${BASE}/metadata")
if [ "$code" != "200" ]; then
  echo "   /fhir/metadata returned '${code}'. Start the proxy in another terminal:"
  echo "   ./gradlew bootRun --args='--server.port=${PORT}'"
  exit 1
fi
echo "   OK"

echo
echo "2) In Chrome DevTools > Network, click a request to /MyChartPPR1/api/..."
echo "   Under Request Headers, copy the VALUE of 'Cookie' (right-click > Copy value)."
read -r -p "   Press Enter when the cookie is on the clipboard ... " _
# If a whole "export C='Cookie: ...'" line was copied, keep only the cookie value.
COOKIE=$(clip | clean | perl -0777 -pe "if (/(?:^|\s|')Cookie:\s*([^'\x{0}]*)/i) { \$_ = \$1 } s/\s+\$//")
echo "   cookie: ${#COOKIE} characters"
case "$COOKIE" in *MyChart*) ;; *) echo "   Warning: the cookie does not contain 'MyChart' - is it the right value?";; esac

echo
echo "3) Now copy the VALUE of the '__RequestVerificationToken' request header"
echo "   (same request, a separate line under Request Headers - not the cookie of that name)."
echo "   Without it only Patient works; MyChart rejects the /api calls with a login redirect."
TOKEN=""
while :; do
  read -r -p "   Press Enter when the token is on the clipboard (type s + Enter to skip) ... " answer
  [ "$answer" = "s" ] && break
  TOKEN=$(clip | clean | perl -pe 's/^.*__RequestVerificationToken:\s*//i; s/\x27.*$//')
  if [ -z "$TOKEN" ] || [ "$TOKEN" = "$COOKIE" ] || printf '%s' "$TOKEN" | grep -q '[; ]'; then
    echo "   The clipboard does not hold a token (it still has the cookie, or text with ';' or spaces). Try again."
    TOKEN=""
    continue
  fi
  break
done
echo "   token: ${#TOKEN} characters"

echo
echo "4) Calling /fhir (status and number of resources only):"
for q in Patient Condition "Observation?date=ge2025-01-01" "Observation?category=vital-signs" \
         DiagnosticReport Appointment Encounter DocumentReference ServiceRequest Organization; do
  body=$(mktemp)
  if [ -n "$TOKEN" ]; then
    code=$(curl -s -o "$body" -w '%{http_code}' -H "Cookie: ${COOKIE}" -H "__RequestVerificationToken: ${TOKEN}" "${BASE}/${q}")
  else
    code=$(curl -s -o "$body" -w '%{http_code}' -H "Cookie: ${COOKIE}" "${BASE}/${q}")
  fi
  if [ "$code" = "200" ]; then
    total=$(grep -o '"total" *: *[0-9]*' "$body" | head -1 | grep -o '[0-9]*$')
    printf '   %-36s %s  %s resources\n' "$q" "$code" "${total:-?}"
  else
    # Error responses are OperationOutcomes from the proxy; show their diagnostics only.
    diag=$(grep -o '"diagnostics" *: *"[^"]*"' "$body" | head -1 | cut -c1-160)
    [ "$code" = "401" ] && saw401=1
    printf '   %-36s %s  %s\n' "$q" "$code" "${diag:-$(head -c 120 "$body" | tr -d '\n')}"
  fi
  rm -f "$body"
done

echo
if [ "${saw401:-0}" = "1" ]; then
  echo "401 = MyChart did not accept the session for that call. Copy fresh Cookie and"
  echo "__RequestVerificationToken values (the session times out after a few idle minutes) and rerun."
fi
echo "Done. Send the lines above and any WARN lines from the proxy terminal."
echo "Remember to log out of minsundhedsplatform.dk afterwards."
