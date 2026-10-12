# AutoSeer 狀態基準線

用來比對「有沒有退步」的快照。大改（升級依賴、動建置設定、修 Lint）後跑一次
`tools/dev.sh check` 與 `tools/dev.sh lint`，有變就更新本表。待辦與 BUG 細節在 `回饋與待辦.md`。

最後更新：2026-10-12（換電腦前：dev.sh 路徑改成自動推、AF_UNIX 暫存改放 `.dev/tmp`）

## 驗證結果

| 項目 | 結果 | 指令 |
|------|------|------|
| 單測 | ✅ 60 個，失敗 0、錯誤 0（強制重跑約 12s） | `tools/dev.sh test` |
| 建置 | ✅ `app-debug.apk` 46.6 MB，無 Kotlin 編譯警告 | `tools/dev.sh build` |
| Lint | ✅ 0 error／123 warning（見下） | `tools/dev.sh lint` |
| 實機啟動 | ✅ 2026-10-06 使用者實機：擷取卡存檔＋精靈編號搜尋導航正常 | `tools/dev.sh install` → `log` |

Lint 警告分布：UnusedResources 30、SetTextI18n 28、GradleDependency 15、Autofill 11、
Overdraw 9、HardcodedText 9、LabelFor 9、AndroidGradlePluginVersion 6、DrawAllocation 3、
ObsoleteSdkInt／MonochromeLauncherIcon／ChromeOsAbiSupport 各 1。全是警告，暫不處理。

## 本機環境（檢查時）

- Gradle 8.10.2 跑在 JBR 21.0.11；`libautomata`／`scripts` 以 `jvmToolchain(17)` 編譯，`app` 為 Java/Kotlin 17
- AGP 8.7.3、Kotlin 2.0.21；SDK platform android-35、build-tools 35.0.0
- 路徑與 JDK 由 `tools/dev.sh` 設定，不吃系統 `JAVA_HOME`／`ANDROID_HOME`

## 已知風險（未處理）

- **ABI 只打包 ARM**（arm64-v8a、armeabi-v7a）：MuMu 是 x86_64，要靠模擬器的 ARM 轉譯才能裝與載入 OpenCV `.so`。尚未在實機確認。
- **`app` 模組沒有任何測試**：平台膠水只能靠實機與 logcat 驗證。
- **Lint 不在 CI**：CI 只跑單測和 `assembleDebug`，新的 Lint error 不會擋 PR。
- **`local.properties` 的 `sdk.dir` 是失效路徑**（`C:/Users/ES_915/...`）：AGP 印 WARNING 後改用 `ANDROID_HOME`，不影響建置。這個檔案不進版控，可自行改掉或刪除。
