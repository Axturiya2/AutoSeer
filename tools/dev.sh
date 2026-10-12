#!/usr/bin/env bash
# AutoSeer 開發入口：把建置 / 測試 / 部署 / 看 log 包成「輸出精簡」的子命令。
# 目的：環境細節（JDK、AF_UNIX、ANDROID_HOME、重裝後無障礙被撤銷）只寫這一處，
#       成功只印一行、失敗只印關鍵行，AI 與人都不必重讀幾千行 Gradle 輸出。
# 用法：tools/dev.sh [-s <adb序號>] <命令> [參數]   （無參數看說明）
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
ROOT=$PWD
mkdir -p .dev

# 本機覆寫（不進版控）：AUTOSEER_JAVA_HOME / ANDROID_HOME / AUTOSEER_TMP / AUTOSEER_SERIAL
[ -f .dev/env ] && . .dev/env

# 刻意不吃環境裡的 JAVA_HOME：Android Studio 內建 JBR 已是 Java 25，Gradle 8.10 不支援。
# 沒覆寫就找 ~/.jdks 底下的 JDK 21（Android Studio「Download JDK」的安裝位置）
if [ -z "${AUTOSEER_JAVA_HOME:-}" ]; then
  for d in "$HOME"/.jdks/*-21.*; do [ -f "$d/bin/java.exe" ] && AUTOSEER_JAVA_HOME=$d; done
fi
export JAVA_HOME="${AUTOSEER_JAVA_HOME:-$HOME/.jdks/jbr-21.0.11}"
# JBR 的 AF_UNIX pipe 遇到 8.3 短檔名會 EINVAL，必須指到短 ASCII 路徑（專案路徑本來就要求純 ASCII）
AUTOSEER_TMP="${AUTOSEER_TMP:-$(cygpath -m "$ROOT")/.dev/tmp}"
mkdir -p "$AUTOSEER_TMP"
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$AUTOSEER_TMP"
export ANDROID_HOME="${ANDROID_HOME:-$(cygpath -m "${LOCALAPPDATA:-$USERPROFILE/AppData/Local}")/Android/Sdk}"
export MSYS_NO_PATHCONV=1   # 防止 Git Bash 改寫傳給 adb 的 /xxx 參數
ADB="$ANDROID_HOME/platform-tools/adb.exe"
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.autoseer
A11Y="$PKG/com.autoseer.input.GestureAccessibilityService"
SERIAL=""

# ---- Gradle：完整輸出進 .dev/last-gradle.log，終端只留摘要 ----
gw() {
  local log=.dev/last-gradle.log
  echo "▶ gradle $*"
  SECONDS=0
  ./gradlew --console=plain "$@" >"$log" 2>&1
  local rc=$?
  if [ $rc -ne 0 ]; then
    {
      if grep -q '^e: ' "$log"; then
        grep -E '^e: ' "$log"                             # 編譯錯誤（已足夠，不再附 Gradle 的重複說明）
      else
        grep -E -A3 '^[^>].* > .* FAILED$' "$log"         # 失敗的測試＋斷言訊息（排除 "> Task … FAILED"）
        grep -E -A2 '^\* What went wrong' "$log"          # 其他 Gradle 層級錯誤
      fi
    } | sed -E 's#file:///[^ ]*/AutoSeer/##' | awk '!seen[$0]++' | head -40
    echo "✗ 失敗 (${SECONDS}s)，完整 log：.dev/last-gradle.log（用 Grep 查，別整份讀）"
    return $rc
  fi
  echo "✓ 通過 (${SECONDS}s)"
}

# 從 XML 報告加總測試數（Gradle 若 up-to-date，報告仍是上次那份）
test_counts() {
  local files t f e
  files=$(ls libautomata/build/test-results/test/*.xml scripts/build/test-results/test/*.xml 2>/dev/null) || true
  [ -z "$files" ] && return 0
  t=$(cat $files | grep -o '<testsuite [^>]*' | grep -o ' tests="[0-9]*"' | awk -F'"' '{s+=$2} END{print s+0}')
  f=$(cat $files | grep -o '<testsuite [^>]*' | grep -o ' failures="[0-9]*"' | awk -F'"' '{s+=$2} END{print s+0}')
  e=$(cat $files | grep -o '<testsuite [^>]*' | grep -o ' errors="[0-9]*"' | awk -F'"' '{s+=$2} END{print s+0}')
  echo "  測試 $t 個，失敗 $f，錯誤 $e"
}

cmd_test() {
  local tasks=(":libautomata:test" ":scripts:test")
  case "${1:-}" in libautomata|scripts) tasks=(":$1:test"); shift ;; esac
  gw "${tasks[@]}" "$@" && test_counts
}

cmd_build() {
  gw :app:assembleDebug "$@" || return $?
  [ -f "$APK" ] && echo "  $APK ($(du -k "$APK" | cut -f1) KB)"
}

cmd_check() { cmd_test && cmd_build; }   # 等同 CI

# Android Lint（CI 沒跑）：只印 Error 行＋依 issue id 的計數，完整報告看 app/build/reports/lint-results-debug.html
LINT_TXT=app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt
cmd_lint() {
  gw :app:lintDebug "$@"; local rc=$?
  [ -f "$LINT_TXT" ] || return $rc
  grep -E ': Error: ' "$LINT_TXT" | sed -E 's#^.*AutoSeer[\\/]##' | head -20
  echo "  依 issue 計數："
  grep -oE '(Error|Warning): .*\[[A-Za-z0-9_]+\]$' "$LINT_TXT" \
    | sed -E 's/^(Error|Warning): .*\[([A-Za-z0-9_]+)\]$/\1 \2/' | sort | uniq -c | sort -rn | head -30
  return $rc
}

# ---- 裝置 ----
adbq() { "$ADB" "$@" 2>&1 | tr -d '\r'; }

pick_device() {
  SERIAL=${AUTOSEER_SERIAL:-}
  [ -n "$SERIAL" ] && return 0
  local list n
  list=$(adbq devices | awk 'NR>1 && $2=="device"{print $1}')
  n=$(printf '%s\n' "$list" | grep -c .)
  if [ "$n" -eq 1 ]; then SERIAL=$list; return 0; fi
  echo "找到 $n 台裝置，請指定：tools/dev.sh -s <序號> <命令>（或在 .dev/env 設 AUTOSEER_SERIAL=...）"
  adbq devices -l | tail -n +2
  return 1
}

cmd_devices() { adbq devices -l | tail -n +2 | grep . || echo "(無裝置)"; }

cmd_grant() {
  pick_device || return 1
  # 每次 adb install -r 都會讓 Android 關掉無障礙服務，不重開的話「偵測到回合但不點擊」
  adbq -s "$SERIAL" shell settings put secure enabled_accessibility_services "$A11Y" >/dev/null
  adbq -s "$SERIAL" shell settings put secure accessibility_enabled 1 >/dev/null
  adbq -s "$SERIAL" shell cmd appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null
  echo "✓ 已授權無障礙＋懸浮窗 ($SERIAL)"
}

cmd_install() {
  pick_device || return 1
  cmd_build || return $?
  adbq -s "$SERIAL" install -r -t "$APK" | tail -1
  cmd_grant
}

cmd_log() {
  if [ "${1:-}" = "-c" ]; then adbq logcat -c; echo "✓ 已清除 logcat"; return; fi
  pick_device || return 1
  # 只看 AutoSeer tag 與崩潰；先限縮掃描範圍再取尾端 N 行
  adbq -s "$SERIAL" logcat -d -v time -t 4000 -s AutoSeer:V AndroidRuntime:E | tail -n "${1:-60}"
}

cmd_shot() {   # 截圖進 .dev/shots/；注意：把圖片餵給 AI 是最貴的驗證方式，能用 log 就別用
  pick_device || return 1
  mkdir -p .dev/shots
  local f=".dev/shots/${1:-$(date +%H%M%S)}.png"
  "$ADB" -s "$SERIAL" exec-out screencap -p >"$f"
  echo "$f ($(du -k "$f" | cut -f1) KB)"
}

cmd_status() {
  git status -sb | head -12
  git log -1 --format='最近 commit：%h %s'
  if [ -f "$APK" ]; then echo "APK：$(date -r "$APK" '+%m-%d %H:%M')（$(du -k "$APK" | cut -f1) KB）"; else echo "APK：尚未建置"; fi
  echo "裝置："; cmd_devices
}

cmd_setup() {   # 新機器或換環境時跑一次，順便當環境自檢
  git config core.hooksPath .githooks && echo "✓ git hooks → .githooks（pre-push 會跑單測）"
  [ -f "$JAVA_HOME/bin/java.exe" ] && echo "✓ JDK  $JAVA_HOME" || echo "✗ 找不到 JDK：$JAVA_HOME（在 .dev/env 設 AUTOSEER_JAVA_HOME）"
  [ -f "$ADB" ] && echo "✓ adb  $ADB" || echo "✗ 找不到 adb：$ADB（在 .dev/env 設 ANDROID_HOME）"
  [ -d "$ANDROID_HOME/platforms/android-35" ] && echo "✓ SDK  platform android-35" || echo "✗ 缺 platforms/android-35"
  case "$ROOT" in *[!\ -~]*) echo "✗ 專案路徑含非 ASCII 字元，單元測試會 ClassNotFoundException" ;; *) echo "✓ 專案路徑為純 ASCII" ;; esac
}

cmd_stop() {   # 搬移、壓縮或刪除專案檔前先跑：Gradle／Kotlin daemon 會鎖住 build 與 .gradle 底下的檔案
  ./gradlew --stop -q && echo "✓ Gradle daemon 已停止"
}

usage() {
  cat <<'EOF'
tools/dev.sh [-s <adb序號>] <命令>
  test [libautomata|scripts] [--tests '*X*']   純 JVM 單測（最快的驗證，優先用）
  build [gradle 參數]                          assembleDebug（例：build --rerun-tasks）
  check                                        test + build（等同 CI）
  lint [gradle 參數]                           Android Lint：Error 行＋依 issue 計數（CI 未跑）
  install                                      build → adb install -r → 自動重新授權
  grant                                        只重新授權無障礙＋懸浮窗
  log [N|-c]                                   AutoSeer tag＋崩潰的最後 N 行（預設 60）／清除
  shot [名稱]                                  截圖存 .dev/shots/
  setup                                        新機器自檢（JDK／adb／SDK／路徑）並設定 git hooks
  stop                                         停掉 Gradle daemon（搬移／壓縮專案前）
  devices | status
EOF
}

if [ "${1:-}" = "-s" ]; then AUTOSEER_SERIAL=${2:-}; shift 2; fi
cmd=${1:-help}; [ $# -gt 0 ] && shift
case "$cmd" in
  test|build|check|lint|devices|grant|install|log|shot|status|setup|stop) "cmd_$cmd" "$@" ;;
  *) usage ;;
esac
