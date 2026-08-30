#!/bin/sh
# ============================================================================
# 起動中のスタックに対する疎通・機能確認。
#
# ★ このスクリプトが存在する理由。
#   この設計では「Spring のトランザクションの外で DB に触る」と、
#   app.tenant_id が設定されず RLS が黙って 0 行を返す。
#   例外も警告も出ない。画面には「データがありません」と出るだけ。
#
#   実際に 2 回踏んだ:
#     1. Spring Data の派生クエリメソッド（findByXxx）が
#        Spring のトランザクションに入らず、顧客一覧が常に空になった
#     2. KpiController が JdbcTemplate を @Transactional の外で使い、
#        KPI の内訳が常に空になった
#
#   どちらも「200 が返る」ので、疎通確認だけでは見つからない。
#   読み取り経路ごとに「データがあるはずのところにデータがあるか」を
#   確かめるのが唯一の防御になる。新しい読み取り API を足したら、
#   ここにも 1 行足すこと。
#
# 前提: docker compose up 済み、scripts/seed-dev.sql 投入済み
# ============================================================================

set -e

API="${API_BASE:-http://localhost:8080}"
VOICE="${VOICE_BASE:-http://localhost:8001}"
WEB="${WEB_BASE:-http://localhost:3000}"

fail=0
pass() { printf '  OK   %s\n' "$1"; }
ng()   { printf '  NG   %s\n' "$1"; fail=1; }

check_code() {
  actual=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$2" ${3:+-H "$3"})
  if [ "$actual" = "$1" ]; then pass "$4 ($actual)"; else ng "$4 (期待 $1 / 実際 $actual)"; fi
}

echo "疎通"
echo "----------------------------------------------------------------------"
check_code 200 "$API/actuator/health" "" "api  /actuator/health"
check_code 200 "$VOICE/healthz"       "" "voice /healthz"
check_code 200 "$WEB/"                "" "web  /"

echo ""
echo "認証"
echo "----------------------------------------------------------------------"
TOKEN=$(curl -s --max-time 10 -X POST "$API/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug":"demo","email":"operator@demo.example","password":"password"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

if [ -n "$TOKEN" ]; then pass "ログイン"; else ng "ログイン"; exit 1; fi

AUTH="Authorization: Bearer $TOKEN"

code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -X POST "$API/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug":"demo","email":"operator@demo.example","password":"wrong"}')
if [ "$code" = "401" ]; then pass "誤ったパスワードを拒否"; else ng "誤ったパスワードを拒否 ($code)"; fi

echo ""
echo "読み取り経路（★ 0 件なら RLS にテナントが届いていない）"
echo "----------------------------------------------------------------------"

nonempty() {
  body=$(curl -s --max-time 10 "$API$1" -H "$AUTH")
  # 空配列 / 空オブジェクトでないこと
  case "$body" in
    "[]"|"{}"|"") ng "$2 — 空。@Transactional が無い可能性" ;;
    *)            pass "$2" ;;
  esac
}

nonempty "/api/v1/customers"          "顧客一覧"
nonempty "/api/v1/calls/dispositions" "結果コード一覧"
nonempty "/api/v1/calls"              "通話履歴"
nonempty "/api/v1/dnc"                "DNC 一覧"
nonempty "/api/v1/kpi/blocked"        "KPI 止めた理由"
nonempty "/api/v1/kpi/hourly"         "KPI 時間帯別"

echo ""
echo "テナント分離"
echo "----------------------------------------------------------------------"
T2=$(curl -s --max-time 10 -X POST "$API/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug":"other","email":"operator@other.example","password":"password"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

A=$(curl -s --max-time 10 "$API/api/v1/customers" -H "$AUTH" | tr -cd '{' | wc -c)
B=$(curl -s --max-time 10 "$API/api/v1/customers" -H "Authorization: Bearer $T2" | tr -cd '{' | wc -c)

if [ "$A" -gt 0 ] && [ "$B" -gt 0 ] && [ "$A" != "$B" ]; then
  pass "テナントごとに違う件数が返る（demo=$A 件 / other=$B 件）"
else
  ng "テナント分離（demo=$A 件 / other=$B 件）"
fi

if curl -s --max-time 10 "$API/api/v1/customers" -H "Authorization: Bearer $T2" \
     | grep -q "アルファ"; then
  ng "★ 他テナントの顧客が見えている"
else
  pass "他テナントの顧客が見えない"
fi

echo ""
echo "認証なしのアクセス"
echo "----------------------------------------------------------------------"
code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$API/api/v1/customers")
if [ "$code" = "401" ] || [ "$code" = "403" ]; then
  pass "トークンなしは拒否 ($code)"
else
  ng "トークンなしは拒否 ($code)"
fi

for path in /twilio/voice /twilio/status /twilio/recording; do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -X POST "$VOICE$path" -d "CallSid=CA1")
  if [ "$code" = "403" ]; then
    pass "署名なしの $path を拒否 ($code)"
  else
    ng "署名なしの $path を拒否 ($code)"
  fi
done

echo "----------------------------------------------------------------------"
if [ "$fail" = "0" ]; then
  echo "すべて通りました"
else
  echo "失敗があります"
fi
exit $fail
