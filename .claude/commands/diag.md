---
description: 用最少 token 診斷模擬器上的一次實跑（讀 logcat 文字，不看截圖）
argument-hint: [現象，例如「換精靈後卡住」]
allowed-tools: Bash(tools/dev.sh *), Grep, Read
---
現象：$ARGUMENTS

1. `tools/dev.sh log 80` 取得最近 log；不夠再 `log 200`，不要更多。
2. 只用 Grep 在 `scripts/`、`app/` 對照 log 裡出現的關鍵字定位程式；Read 一律帶 offset/limit。
3. 輸出三段，每段簡短：
   - 時間序摘要（≤ 8 行）
   - 卡住／出錯點，附 `檔案:行號`
   - 最可能原因＋一個能驗證它的方法
4. log 證據不足就直說缺什麼（例如在哪個狀態轉換補一行 `Log.i("AutoSeer", …)`），不要猜。除非使用者同意，不截圖、不操作模擬器、不改程式。
