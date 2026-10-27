---
description: 單測通過後，依本專案慣例提交 commit（不推送）
argument-hint: [一句話說明，可省略]
allowed-tools: Bash(tools/dev.sh *), Bash(git status*), Bash(git diff*), Bash(git log*), Bash(git add *), Bash(git commit *)
model: haiku
---
照順序做，不要多做：

1. 執行 `tools/dev.sh test`。失敗就停下，貼出它印的精簡摘要，**不要自己修**。
2. `git status --short` 與 `git diff --stat`。除非判斷不出訊息，否則不看完整 diff。
3. 用明確檔名 `git add`（不用 `-A`；不要加入 `.dev/`、`*.7z`、`local.properties`）。
4. 提交：繁體中文一行訊息，風格比照 `git log --oneline -5`（`feat:` / `fix:` / `建置：`…）。使用者補充：$ARGUMENTS
5. 不推送。回報 commit hash，並說明「尚未推送，推送時 pre-push 會再跑一次單測」。
