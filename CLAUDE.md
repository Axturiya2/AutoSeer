# AutoSeer

《賽爾號：巔峰之戰》免 root 自動化 App（Kotlin，多模組）。截圖 → OpenCV matchTemplate → 無障礙點擊。背景見 README.md，待辦見 `回饋與待辦.md`。
工作流與省額度做法：`docs/WORKFLOW.md`；建置／測試／Lint 基準線：`docs/STATUS.md`。

## 模組
- `libautomata`：純 JVM 核心（Location/Region/AutomataApi/Script），可單測
- `scripts`：賽爾號流程（BattleScript、SeerFactorScript、BattleTurnRunner、FactorSearchSweep…），純 JVM，可單測
- `app`：Android 殼（OpenCV、MediaProjection、無障礙、懸浮窗、UI）；辨識樣板在 `app/src/main/assets/images/`

## 指令（一律用包裝腳本，不要直接跑 gradlew / adb）
```
tools/dev.sh test [libautomata|scripts] [--tests '*X*']   # 純 JVM 單測，~12s，先跑這個
tools/dev.sh check                                         # test + assembleDebug（= CI）
tools/dev.sh build [--rerun-tasks]                         # 只建 APK；加參數可強制重建
tools/dev.sh lint                                          # Android Lint，~35s，CI 沒跑；改 app 後跑
tools/dev.sh install                                       # 建置→裝機→自動重新授權無障礙
tools/dev.sh log [N]                                       # AutoSeer tag＋崩潰的最後 N 行
tools/dev.sh setup                                         # 新機器／換環境先跑：自檢 JDK、adb、SDK、路徑
tools/dev.sh stop                                          # 搬移／壓縮／刪專案檔前先停 Gradle daemon（會鎖檔）
tools/dev.sh shot / devices / status
```
- 換電腦：路徑預設會自動推（`~/.jdks/*-21.*`、`%LOCALAPPDATA%\Android\Sdk`），對不上就在 `.dev/env` 設 `AUTOSEER_JAVA_HOME` / `ANDROID_HOME` / `AUTOSEER_TMP`。JDK 要 21（不能用 Android Studio 內建的 25），SDK 要 platform android-35。
- 使用者習慣自己把 `app/build/outputs/apk/debug/app-debug.apk` 手動裝進 MuMu、手動開權限：改完 app 跑 `check` 後告知 APK 已更新即可，不要叫使用者跑 `install`；需要讀 logcat 時才請使用者連上 adb。
- 成功只印 1～3 行；失敗只印錯誤行，完整 log 在 `.dev/last-gradle.log`（用 Grep 查，別整份 Read）。
- Bash 工具要加 `dangerouslyDisableSandbox: true`（沙箱擋 AF_UNIX，Gradle 會失敗）。
- 多台 adb 裝置時用 `-s <序號>`；`adb install -r` 會撤銷無障礙授權，所以裝機請用 `install` 不要手動 adb。
- Gradle log 開頭的「sdk.dir … Directory does not exist」可忽略：`local.properties` 是舊機器路徑，會改用 dev.sh 設的 `ANDROID_HOME`。
- minSdk 24：用 API 26+ 的 Android API 要加版本判斷或改用 androidx `*Compat`，否則 `lint` 會報 NewApi。

## 開發規則
- 座標與辨識一律在 **1280×720 正規化座標系**，輸出點擊時才換算回實機。
- 邏輯放 `libautomata`/`scripts` 並補單測（只有這兩個模組能 12 秒內驗證）；`app` 只放平台膠水。
- 專案路徑必須純 ASCII（中文路徑會讓單測 ClassNotFoundException）。
- commit 訊息繁體中文一行，前綴沿用 `feat:` / `fix:` / `建置：`；新回饋與 BUG 依 `回饋與待辦.md` 的格式記。
- 遊戲自動化有封號風險，只供個人研究；不複製 FGA 原始碼。

## 省額度守則（優先於「做得完整」）
1. 驗證優先序：單測 > logcat 文字 > 截圖。截圖與影片最貴，使用者沒要求就不看；預設不用 computer-use 長時間操作模擬器，要的話先問。
2. 不讀 `**/build/**`、`.gradle/`、`*.7z`、`UI/` 下的 pptx（已在 settings 擋）。
3. 大檔（BattleScript、SeerFactorScript、AutoSeerService、ControlOverlay 皆 300+ 行）先 Grep 定位，再 Read 帶 offset/limit。
4. 不開子代理、不做全專案探索；上面的模組地圖就是起點。
5. 載入大型 skill 前先想是否必要（例如 update-config 一次約 2 萬 token）；設定類變更集中一次做完。
6. 回覆精簡：不複述 diff、不貼整份 log；一件事一個對話，做完建議使用者 `/clear`。
