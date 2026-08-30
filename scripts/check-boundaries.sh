#!/bin/sh
# ============================================================================
# 設計上の境界が守られているかを機械的に確認する。
#
# ★ 「そういう約束にしてある」は 3 か月後には守られていない。
#   守られているかを 1 コマンドで確かめられる形にしておく。
#   CI に入れて、破った変更がマージされないようにするのが本来の使い方。
#
# ★ 検査対象はソースと依存宣言だけ。コメント・ビルド生成物・仮想環境は除く。
#   これを外すと、ルールを説明したコメント自身が違反として報告される
#   （実際に最初はそうなった）。誤検知が出る検査は、いずれ無視される。
#
# 確認するのは 4 つ:
#   1. api（Spring Boot）が Twilio に依存していないこと
#   2. Twilio の発信 API を呼ぶ場所が voice/app/telephony/dialer.py だけであること
#   3. 発信の関門（DialingGate）を呼ぶ場所が CallService だけであること
#   4. voice 側がスキーマのマイグレーションを持っていないこと
# ============================================================================

set -e
cd "$(dirname "$0")/.."

fail=0

report() {
  if [ "$2" = "0" ]; then
    printf '  OK   %s\n' "$1"
  else
    printf '  NG   %s\n' "$1"
    fail=1
  fi
}

# ソースだけを対象にする（ビルド生成物・仮想環境・キャッシュを除く）
SRC_EXCLUDES='--exclude-dir=__pycache__ --exclude-dir=.venv --exclude-dir=build
              --exclude-dir=node_modules --exclude-dir=.gradle --exclude=*.pyc'

echo "境界の検証"
echo "----------------------------------------------------------------------"

# --------------------------------------------------------------- 1
# api に Twilio の依存が入っていないこと。
# 入った時点で「電話に触れるのは voice だけ」が壊れ、
# 発信の関門を迂回する経路が作れるようになる。
#
# ★ コメント行を落としてから見る。この規約を説明したコメント自体を
#   違反として報告してしまうため。
if sed 's://.*::' api/build.gradle.kts 2>/dev/null | grep -qi "twilio"; then
  report "api が Twilio に依存していない" 1
  echo "       → api/build.gradle.kts の依存に twilio が現れています"
else
  report "api が Twilio に依存していない" 0
fi

# --------------------------------------------------------------- 2
# Twilio の Calls API を呼ぶ場所は dialer.py だけ。
# shellcheck disable=SC2086
hits=$(grep -rln $SRC_EXCLUDES "calls\.create" voice/app 2>/dev/null \
        | grep -v "telephony/dialer.py" || true)
if [ -n "$hits" ]; then
  report "Twilio の発信呼び出しが dialer.py だけ" 1
  echo "$hits" | sed 's/^/       → /'
else
  report "Twilio の発信呼び出しが dialer.py だけ" 0
fi

# --------------------------------------------------------------- 3
# 関門を呼ぶのは CallService だけ。
# ここが増えるほど「関門を通らない発信」が生まれやすくなる。
# shellcheck disable=SC2086
hits=$(grep -rln $SRC_EXCLUDES "DialingGate" api/src/main/java 2>/dev/null \
        | grep -v "service/CallService.java" \
        | grep -v "service/DialingGate.java" || true)
if [ -n "$hits" ]; then
  report "関門を呼ぶのは CallService だけ" 1
  echo "$hits" | sed 's/^/       → /'
else
  report "関門を呼ぶのは CallService だけ" 0
fi

# --------------------------------------------------------------- 4
# スキーマの所有者は api（Flyway）に一本化する。
# voice 側にマイグレーションを置くと、片方だけ適用された状態が生まれる。
if [ -d voice/migrations ] || find voice -name "V*__*.sql" 2>/dev/null | grep -q .; then
  report "voice がマイグレーションを持たない" 1
else
  report "voice がマイグレーションを持たない" 0
fi

echo "----------------------------------------------------------------------"
if [ "$fail" = "0" ]; then
  echo "境界は守られています"
else
  echo "境界が破られています。上の項目を直してください"
fi
exit $fail
