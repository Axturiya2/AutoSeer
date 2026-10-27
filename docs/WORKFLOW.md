# AutoSeer 開發工作流（省額度版）

原則：**能用 0 token 的東西驗證，就別叫 AI 去驗證。** AI 只負責改碼與解讀精簡後的結果。

## 日常迴圈

```
改碼 ─► tools\dev.cmd test ─► tools\dev.cmd install ─► 在模擬器操作 ─► tools\dev.cmd log
          (12 秒，0 token)       (建置+裝機+授權)         (你做)          (只看 AutoSeer tag)
```

| 動作 | 誰跑 | 花費 |
|------|------|------|
| 單測 / 建置 / 裝機 / 看 log | 你在終端機（`tools\dev.cmd …`），或 AI 呼叫同一支腳本 | 自己跑 = 0 token；AI 跑 = 1～3 行輸出 |
| push 前把關 | `.githooks/pre-push` 自動跑單測 | 0 |
| 雲端建 APK | GitHub Actions（push 後自動） | 0 |
| 提交 commit | `/ship`（固定 Haiku） | 極低 |
| 實跑出問題 | `/diag 現象`（讀 log 文字，不看截圖） | 低 |

`tools\dev.cmd` 與 `tools/dev.sh` 是同一支腳本（前者給 PowerShell / cmd，後者給 Git Bash 與 Claude）。

## 選模型

| 工作 | 建議 |
|------|------|
| commit、改文案、小修、跑流程 | Haiku |
| 功能開發、除錯（預設） | Sonnet |
| 架構重整、查不出的難 bug、跨模組設計 | 才切最強模型，做完切回 |

## 對話衛生（最大的額度變數）

- **一個對話一件事**，做完 `/clear`。狀態靠 git、`回饋與待辦.md`、memory 承接，不靠長對話。
- 說需求用 `@檔案` 或 `檔案:行號`，不要貼 log；要看 log 就說「跑 `dev.sh log`」。
- 方向不確定時先要「只出計畫、不寫碼」，貴的是做錯方向的實作。
- 對話拉長時 `/compact`，或直接 `/clear` 重開。

## 額度黑洞與對策

| 黑洞 | 對策（已內建） |
|------|----------------|
| 整份讀 Gradle 輸出 / build 產物 | `dev.sh` 只印錯誤行；`settings.json` 擋 `**/build/**`、`.gradle/`、`*.7z`、pptx |
| 截圖、影片、連續操作模擬器 | 驗證優先序：單測 > logcat > 截圖；CLAUDE.md 規定預設不用 computer-use |
| 全專案探索、子代理 | CLAUDE.md 有模組地圖；大檔先 Grep 再 Read 帶範圍 |
| 每次對話重新推導環境（JDK、AF_UNIX、重裝撤銷無障礙…） | 全部固化在 `tools/dev.sh`，CLAUDE.md 只留一頁 |
| 載入大型 skill / 設定文件 | 設定變更集中一次做；`update-config` 一次約 2 萬 token |

log 不夠看時，**補一行 `Log.i("AutoSeer", …)`** 在狀態轉換處，而不是改用截圖。

## 為什麼邏輯要放在 `libautomata` / `scripts`

只有這兩個純 JVM 模組能在 12 秒內不靠模擬器驗證。`app`（Android 殼）一律要建置＋裝機＋實跑，成本高一個量級。新邏輯盡量寫成「輸入狀態 → 輸出決策」的純函式並補單測（例：`DeployDecision`、`SweepProgress`），`app` 只放平台膠水。

## 首次設定

```
tools\dev.cmd setup        # 自檢 JDK/adb/SDK，並啟用 pre-push hook
```

- 有多台 adb 裝置時，建立 `.dev/env`（已被 gitignore）：`AUTOSEER_SERIAL=127.0.0.1:7555`，或每次加 `-s <序號>`。
- 換電腦時，同一個檔可覆寫 `AUTOSEER_JAVA_HOME` / `ANDROID_HOME`。
- 新增的 `.claude/settings.json` 要**重開 Claude 對話**才會載入。

## 檔案地圖

| 檔案 | 作用 |
|------|------|
| `CLAUDE.md` | 每次對話自動載入的專案說明＋省額度守則（刻意保持很短） |
| `docs/STATUS.md` | 單測／建置／Lint 基準線與已知風險，大改後更新 |
| `.claude/settings.json` | 允許 `dev.sh` 免詢問、擋大檔讀取、`dev.sh` 與 `git push` 免沙箱 |
| `.claude/commands/ship.md`、`diag.md` | `/ship`、`/diag` |
| `tools/dev.sh`、`tools/dev.cmd` | 建置/測試/部署/log 的單一入口 |
| `.githooks/pre-push` | push 前跑單測（`AUTOSEER_SKIP_HOOK=1` 可略過） |
| `.gitattributes` | 鎖定 shell 腳本為 LF（本機 `core.autocrlf=true` 會讓腳本壞掉） |
