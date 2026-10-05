# AutoSeer

參考 [FGA (Fate-Grand-Automata)](https://github.com/Fate-Grand-Automata/FGA) 架構，替回合制手遊
**《賽爾號：巔峰之戰》**（Google Play 套件名 `tw.com.taomee.seerdf`）打造的**免 root Android 自動化 App**。

> ⚠️ 本工具僅供個人研究/學習用途。遊戲自動化可能違反遊戲服務條款，可能導致帳號被封，使用風險自負。
> 本專案只借鏡 FGA 的架構思路，程式碼與辨識圖樣皆自行撰寫，未複製 FGA 原始碼。

## 運作原理

MediaProjection 截圖 → 轉灰階、等比縮放到高 720 → OpenCV `matchTemplate`（門檻預設 0.8）辨識 →
AccessibilityService `dispatchGesture` 點擊/滑動（免 root）。所有辨識與座標運算都在 **1280×720 正規化座標系**進行，
輸出點擊時再換算回實際裝置解析度，因此同一套腳本可同時適用模擬器與各種手機。

## 模組結構

| 模組 | 類型 | 內容 |
|------|------|------|
| `libautomata` | 純 JVM Kotlin | 平台無關的自動化核心：`Location`/`Region`/`IPattern`、`AutomataApi`、`Script`、介面（可單元測試） |
| `scripts` | 純 JVM Kotlin | 賽爾號流程：`GameState`/`GameStateDetector`、`BattleScript`、`DailyFarmScript`、`TestPipelineScript` |
| `app` | Android App | OpenCV 實作、MediaProjection 擷取、無障礙手勢、懸浮控制列、前景服務、設定 UI、圖樣資產 |

> 註：為降低建置複雜度，`scripts` 做成純 JVM 庫，辨識圖樣資產放在 `app/src/main/assets/images/`。
> 目前用 View + 手動組裝（未用 Compose / Hilt），之後可再引入。

## 建置

需求：Android Studio（內建 JBR 17）、Android SDK（platform android-35、build-tools 35）。

```bash
./gradlew assembleDebug
```

產出：`app/build/outputs/apk/debug/app-debug.apk`

### ⚠️ Windows 建置注意（本機特有）

本機的 JBR 用 AF_UNIX 實作 NIO pipe，預設 socket 目錄含 8.3 短檔名（`C:\Users\ADMINI~1\...`），
會導致 Gradle 報 `Unable to establish loopback connection`。已在 `gradle.properties` 設定
`-Djdk.net.unixdomain.tmpdir=D:\BY\tmp` 修正；若從命令列跑，請額外設環境變數確保 launcher 也生效：

```bash
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=D:/BY/tmp"   # 該目錄需先存在
```

在 Android Studio 內建置通常不受此問題影響。

## 安裝與執行（MuMu 模擬器）

```bash
adb -s 127.0.0.1:7555 install -r -t app/build/outputs/apk/debug/app-debug.apk
```

開啟 App「AutoSeer 設定」，依序：
1. **開啟無障礙服務**（手勢點擊）
2. **授權懸浮視窗**（顯示在其他 App 上層）
3. **授權螢幕擷取並啟動** → 允許「立即開始」

之後畫面左上出現懸浮控制列，按「開始」即執行目前腳本（現為 `TestPipelineScript` 管線驗證）。

測試時可用 adb 快速授權：

```bash
adb shell settings put secure enabled_accessibility_services com.autoseer/com.autoseer.input.GestureAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell cmd appops set com.autoseer SYSTEM_ALERT_WINDOW allow
```

## 進度

- [x] 階段 0：Gradle 多模組骨架
- [x] 階段 1：截圖 → 辨識 → 點擊管線（已在 MuMu 實測：截圖 1280×720、座標換算、手勢點擊全通）
- [ ] 階段 2：擷取賽爾號各畫面樣板、`GameStateDetector` 狀態機（**需從遊戲畫面擷取樣板圖片**）
- [ ] 階段 3：核心自動戰鬥 `BattleScript`
- [ ] 階段 4：每日/掛機刷取 `DailyFarmScript`

### 下一步：製作辨識樣板

`BattleScript`/`DailyFarmScript` 已寫好骨架，但需要實際樣板圖片才能辨識畫面。

**方法 A（推薦，免電腦免重編）— App 內擷取：**
1. 授權並啟動後，在懸浮控制列按 **「存畫面」**：會把 matcher 實際看到的**正規化畫面**
   （已等比縮到高 720）存到 `Android/data/com.autoseer/files/captures/`。
2. 從該圖裁下按鈕/標誌等小區塊，存成 `Android/data/com.autoseer/files/templates/<id>.png`
   （id 見下表）。裝置端樣板會**覆蓋**內建 assets，改樣板不必重編 APK。
3. 回到遊戲對應畫面，按 **「測試偵測」**：會在狀態列/Logcat 列出每個樣板的 `score` 與是否
   達門檻（0.8）。分數夠高即代表辨識成立，可放心讓 `BattleScript` 執行。

**方法 B — 用 adb（電腦端）：**
```bash
adb exec-out screencap -p > screen.png     # 在賽爾號各畫面各截一張
```
依「縮到高 720、等比」裁下小區塊，放到 `app/src/main/assets/images/<id>.png` 一起打包。

樣板 id（`SeerTemplates`）：`state/battle_action`（你的回合）、`state/result_win`（勝利）、
`stage/enter_battle`（進入戰鬥）、`stage/wait_challenge`（等待挑戰節點）、
`state/pet_defeated`（已戰敗）、`state/out_of_stamina`（挑戰次數不足）。

> 現階段服務執行的是 `BattleScript`（讀「使用中」腳本）；`ProbeScript` 為偵測除錯用，
> 由懸浮列「測試偵測」觸發，不會點擊畫面。
