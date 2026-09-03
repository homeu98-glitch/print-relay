# MEMORY.md — macau-print-relay 項目長期筆記

> 跨 session 持續生效嘅 codebase 約定、踩坑清單、設計原則。
> 由每日 `YYYY-MM-DD.md` 提煉而來，超過 30 日嘅日誌會被濃縮到此。

---

## 1. codebase 約定（macauPosSystem）

### 1.1 同步佇列合約（最重要）
**入隊一律寫 `"pending"`，等 server 回 200 之後由 `sync-flush.ts:318-322` 自動標 `"synced"`。**

- 寫法示例：`status: "pending"`（**唔好**用 `networkOnline ? "synced" : "pending"` — 舊 bug，會永久卡住）
- 設計依歸：`sync-flush.ts:64-67` 註解已寫明
- 反例（已修）：2026-09-03 嘅 H10 bug，pos-app.tsx 23 處同 print-center.tsx 1 處都係錯寫

### 1.2 pushEvents 必須 trigger flush worker
每個入隊 function 入面 saveQueue 之後**必須** call `notifyQueueChanged()`（import from `@/lib/pos/sync-flush`），
先會 dispatch `POS_SYNC_QUEUE_CHANGED_EVENT` 觸發 auto-flush worker。
- 反例（已修）：print-center.tsx 嘅 pushEvents 只 saveQueue，永不自觸發

### 1.3 「推送成功先標 synced」係合約
以下 5 處都係正確寫法（fetch 200 後才標 synced）：
- `pos-app.tsx:2078`（`syncNow()`）
- `device-settings.tsx:464`
- `shift-page.tsx:294, 415`
- `kiosk-order.ts:308`
- `print-jobs.ts:390`（DELETE 直連）

### 1.4 storeId 真源只有一個
`resolveStoreId()` 在 `sync-flush.ts:221-227`：
1. `loadAuthSession().merchantId`（login 落嘅真 UUID）
2. `loadKioskDeviceBinding().storeId`（kiosk 綁定）
3. 都冇 → undefined（server 會返 400）

**唔好用** `bootstrap?.storeId`（可能係 mock `macau-store-a`），會污染雲端。

### 1.5 localStorage key 係 per-store scoped
`storage.ts:101-105`：`macau-pos/stores/{merchantId}/{suffix}`，
suffix：`queue: "sync-queue"`、`printJobs: "print-jobs"`。
authSession 係 `macau-pos/auth-session`。
relay 配對係 `macau-pos-relay-{agent-id,token,store-id,store-name,paired}`（`relay-config.ts:8-12`）。

---

## 2. macau-print-hub 約定（Android）

### 2.1 健康判定靠 Hub UI 三時間戳
`ui/MainActivity.kt:268-295` 嘅狀態區顯示：
- 上次認領 → 反映 `JobRunner.drain()` 有冇跑
- 上次心跳 → agent token 驗證通
- 上次叫醒 → Realtime 收到 INSERT

**「上次認領 = —」係 Hub 本地問題**；「認領有跳但已印 0 張」係雲端/POS 問題。

### 2.2 即使 Realtime 斷都唔會「零單」
`HubService.kt:137, 298` 有 30 秒對賬 tick 兜底 claim。
H4（Realtime 失敗）只會令出紙慢 30 秒，**唔會完全收唔到**。

### 2.3 空 claim 完全無日誌
`JobRunner.kt:42` claim 返 0 單時 `error=null`，`HubService.drainNow()` 只在 `error != null` 或 `claimed > 0` 時寫 note。
**「沒有單」和「claim 根本冇跑」UI 睇唔出**——排查必須靠 SQL（`pos_queue_events`）。

### 2.4 ESC/POS 字型放大合約（三端必須同步，否則變形）
`net/EscPosRenderer.kt` 嘅 `Buf` 類係唯一發字型指令嘅地方。改動前必讀 `desktop-companion/companion-server.mjs:257-343`（已實機驗證嘅權威版）。

- **地雷**：Epson / Gprinter 系 `ESC ! n`（1B 21，净管 ASCII）同 `GS ! n`（1D 21，管所有字符）係**相乘唔係覆蓋**。
  同時發 `ESC!0x30 + GS!0x30` → ASCII 變 4×4、中文 2×2 → 版面撕裂（= docs/80 B2）。
- **size byte 值（三端一致）**：
  - `SIZE_BYTE`（ESC!）：`s=0x00 m=0x20 l=0x30`
  - `GS_SIZE_BYTE`（GS! nibble，`n=((h-1)<<4)|(w-1)`）：`s=0x00 m=0x01 l=0x11`
  - `FS_SIZE_BYTE`（FS! Kanji）：`s=0x00 m=0x04 l=0x0C`
- **每行決定放大指令**（唔好統一發）：`useGs = kanjiEnlarge != "FS!"`；
  GS! 路線 + CJK 行 → `ESC!` 發 `0x00`（唔帶放大）、放大只由 `GS! GS_SIZE_BYTE[size]` 負責；
  FS! 路線 → `ESC! SIZE_BYTE[size]`（ASCII）+ `FS! FS_SIZE_BYTE[size]`（中文）。
- **行距跟縱向倍數**：`ESC 3 n`，`l`（雙高）→ 60，否則 30（docs/74 B3，防大字重疊變扁）。
- 實機 A/B 證據：`desktop-companion/test-kanji-size.mjs`（F-2 PASS = 修正生效；F-3/F-4 FAIL = 相乘）。
- 2026-09-03 修過：舊 `Buf` 用 `KANJI_SIZE_BYTE`（誤用 ESC! 值）且 `style()` 永遠發放大位 ESC! → 除 `s` 外全變形。

---

## 3. 流程坑（必踩過嘅）

### 3.1 migration 唔會自動跑
**寫咗 migration ≠ 跑咗 migration。** `0020` migration 註解明寫已踩過 0018/0019。
本地無 DB（唔似 Vercel Postgres），要人手貼去 Supabase SQL Editor。

### 3.2 UI「已發送」係假陽性
`relay-transport.ts:31-38` 無條件回 `ok:true`，UI 見到就標 `sent`。
Network tab 嘅 `POST /api/pos/sync` 先係真相。亦即 queue 入面 synced 嘅事件可能從未推送。

### 3.3 路由配置只有 web 端
「邊部機負責印咩內容」唯一真源：`loadDeviceConfig()` 讀 `pos_device_configs.printers` jsonb。
- 雲端**有**數據但**冇任何下發 API** 畀 Hub
- `/api/pos/device-config` GET **無 store_id 過濾**（取全平台最新一條，已知坑）
- Hub 端 `PrintJobDto.fromRow()` 連 `printer_group` 都未解析

---

## 4. 排查方法論（下次遇到同類問題嘅 SOP）

1. **二分先靠 UI/CLI 觀察**：Hub UI 三時間戳、SQL `count(*)`、Network 請求 log
2. **逐級加埋點**：從「未入隊」→「入隊」→「推送」→「server 收」→「Hub claim」→「打印」逐段加 console.log
3. **正反對照**：DELETE 路徑 vs CREATE 路徑（兩條路徑差異就係 bug 嘅位置）
4. **pos_queue_events 係決定性證人**：每條事件入 server 都會寫，type/created_at 一目了然

---

## 5. 文檔編號約定
- `macauPosSystem/docs/NN-*.md`，NN 係三位數編號
- 用粵語書面體 + 繁體
- 含「一句講晒」「性質」標註
- 同系列文檔互相 link（用相對路徑）
