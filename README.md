# POS Printer Demo (Android)

對應網頁版 `pos-printer-demo.html` 的實機 Demo：**真實掃區網**，不用 mock。  
UI 是 WebView；掃描與 TCP 9100 列印由 Android 原生執行。

## 功能

- 自動／手動設定網段（例如 `10.1.2`）
- 掃描 `.1–.254` 的 **9100 / 631 / 80**
- 把設備綁到服務：前台／水吧／廚房
- SharedPreferences 持久化（重開 App 仍在）
- 對 **TCP 9100** 設備送 ESC/POS 測試頁
- 可選：掃描時對 9100 設備列印識別頁（IP / MAC）

## 專案結構

```
app/src/main/
  assets/index.html          UI
  java/com/posdemo/printer/
    MainActivity.kt          WebView + JS Bridge
    data/DeviceStore.kt      本地儲存
    model/PrinterDevice.kt   設備與服務
    net/LanScanner.kt        區網掃描
    net/EscPosPrinter.kt     TCP 9100 列印
```

## 建置

1. 複製 `local.properties.example` 為 `local.properties`，填入本機 Android SDK 路徑
2. 需要 JDK 17+（建議 JDK 21）

```bat
gradlew.bat assembleDebug
```

APK 輸出：

`app/build/outputs/apk/debug/app-debug.apk`

### 安裝（USB 偵錯）

手機開啟「開發人員選項 → USB 偵錯」，用**數據線**連接後：

```bat
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可把 APK 傳到手機直接安裝（需允許未知來源）。

## 測試注意

1. 手機與印表機必須同一 Wi‑Fi／LAN
2. 廚房熱感機（Epson / Star 等）通常有 **9100**，可直接測列印
3. Brother 辦公複合機多半只有 80 / IPP，可能掃得到但 9100 列印會失敗
4. Android 常讀不到區網設備 MAC（ARP 限制），此時以 IP 當鍵；建議印表機固定 IP
