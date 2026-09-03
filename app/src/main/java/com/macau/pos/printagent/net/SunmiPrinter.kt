package com.macau.pos.printagent.net

import android.content.Context
import android.util.Log
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterException
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.InnerResultCallback
import com.sunmi.peripheral.printer.SunmiPrinterService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Sunmi 內置打印機輸出（docs/96 §8）。
 *
 * 用 Sunmi 官方 `com.sunmi:printerlibrary` 嘅 AIDL 接口，直接 `sendRAWData(bytes)` 落機。
 * **刻意唔用** 佢嘅 printText / setAlignment 呢啲 high-level API：我哋三個 repo 共用嘅
 * `EscPosRenderer` 已經產好完整 ESC/POS byte[]（含 58mm 紙寬、Kanji 倍大、QR 點陣、
 * 折扣反白），如果再經 Sunmi high-level API 包一層，出紙就一定同預覽唔一致。
 *
 * 驗證過嘅 AIDL 表面（1.0.24，javap 實測）：
 *  - `InnerPrinterManager.getInstance().bindService(Context, InnerPrinterCallback): Boolean`
 *  - `InnerPrinterCallback` abstract `onConnected(SunmiPrinterService)` / `onDisconnected()`
 *  - `SunmiPrinterService.sendRAWData(byte[], InnerResultCallback)`
 *  - callback 四個方法：`onRunResult(Boolean)` / `onReturnString(String)` /
 *    `onRaiseException(Int, String)` / `onPrintResult(Int, String)`
 *
 * ⚠️ targetSdk ≥ 30 一定要喺 AndroidManifest 加 `<queries>` 宣告
 * `woyou.aidlservice.jiuiv5.IWoyouService`，否則 bindService 靜默失敗（見 Manifest 註解）。
 */
object SunmiPrinter {

    private const val TAG = "SunmiPrinter"

    /** 送紙後等 ack 嘅上限。Sunmi 嘅 onRunResult 係「數據交到打印服務」就回，正常 < 1s。 */
    private const val SEND_ACK_TIMEOUT_MS = 25_000L

    /** 綁定等待上限。ROM 上電後打印服務可能要幾百 ms 先起來。 */
    private const val BIND_TIMEOUT_MS = 6_000L

    @Volatile
    private var service: SunmiPrinterService? = null

    @Volatile
    private var binding = false

    @Volatile
    var lastError: String? = null
        private set

    /** 內置打印機係咪就緒（已 bind 到 AIDL）。 */
    fun isReady(): Boolean = service != null

    /** 機型字串，淨供診斷／UI 顯示。未就緒 → null。 */
    fun printerModal(): String? = runCatching { service?.printerModal }.getOrNull()

    /** 打印機狀態碼，淨供診斷。各 ROM 嘅碼值定義唔統一，**唔好攞嚟 gate 打印**。 */
    fun statusCode(): Int? = runCatching { service?.updatePrinterState() }.getOrNull()

    /**
     * 主綫程請用呢個：fire-and-forget 綁定，返咗唔代表已綁好（異步）。
     * 建議喺 Service.onCreate 叫一次，等真正要印時已經就緒。
     */
    fun ensureBound(context: Context) {
        if (service != null || binding) return
        try {
            binding = true
            InnerPrinterManager.getInstance().bindService(context.applicationContext, callback())
        } catch (e: InnerPrinterException) {
            binding = false
            lastError = "bindService 失敗：${e.message}"
            Log.w(TAG, lastError ?: "", e)
        } catch (e: Exception) {
            binding = false
            lastError = "bindService 例外：${e.message}"
            Log.w(TAG, lastError ?: "", e)
        } catch (e: Throwable) {
            // 普通手機（非 Sunmi）上，Sunmi AIDL / native 可能掟 LinkageError /
            // UnsatisfiedLinkError（呢啲 extends Error，上面嘅 catch(Exception) 接唔到）。
            // 必須吞底，唔可以令 caller（例如 RelayService.onCreate）彈出成個 app。
            binding = false
            lastError = "bindService 非預期錯誤（非 Sunmi 機？）：${e.message}"
            Log.w(TAG, lastError ?: "", e)
        }
    }

    /**
     * 打印路徑用：確保已綁定，未綁就同步等。可以由背景綫程 call
     * （AIDL callback 會派去主綫程，所以**唔可以**喺主綫程 call 呢個，會 deadlock）。
     */
    fun ensureReady(context: Context, timeoutMs: Long = BIND_TIMEOUT_MS): Boolean {
        if (service != null) return true
        if (LooperHolder.isMainThread()) {
            // 主綫程 call 只能 fire-and-forget，等唔到結果
            ensureBound(context)
            return service != null
        }
        val latch = CountDownLatch(1)
        val cb = object : InnerPrinterCallback() {
            override fun onConnected(svc: SunmiPrinterService) {
                service = svc
                binding = false
                lastError = null
                latch.countDown()
            }

            override fun onDisconnected() {
                service = null
                binding = false
                latch.countDown()
            }
        }
        val ok = try {
            InnerPrinterManager.getInstance().bindService(context.applicationContext, cb)
        } catch (e: Exception) {
            binding = false
            lastError = "bindService 失敗：${e.message}"
            Log.w(TAG, lastError ?: "", e)
            false
        } catch (e: Throwable) {
            // 同上：非 Sunmi 機可能掟 LinkageError / UnsatisfiedLinkError（extends Error）。
            binding = false
            lastError = "bindService 非預期錯誤（非 Sunmi 機？）：${e.message}"
            Log.w(TAG, lastError ?: "", e)
            false
        }
        if (!ok) return false
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (service == null && lastError == null) lastError = "綁定打印服務超時"
        return service != null
    }

    private fun callback(): InnerPrinterCallback = object : InnerPrinterCallback() {
        override fun onConnected(svc: SunmiPrinterService) {
            service = svc
            binding = false
            lastError = null
            Log.i(TAG, "Sunmi 打印服務已連線（${runCatching { svc.printerModal }.getOrNull() ?: "未知機型"}）")
        }

        override fun onDisconnected() {
            service = null
            binding = false
            Log.w(TAG, "Sunmi 打印服務斷線")
        }
    }

    /**
     * 送 raw ESC/POS bytes 落內置打印機。
     *
     * ✅ 成功 = 收到 `onRunResult(true)` 且冇 exception。
     * ❌ 失敗 = RemoteException / `onRunResult(false)` / `onRaiseException` / 超時。
     *
     * 超時當失敗而**唔係**當成功：25s 連「數據已交到打印服務」嘅 ack 都冇，
     * 幾乎肯定係打印服務死咗／機未著，紙一定冇出。呢個時候報 failed 交畀 server 重試
     * 先至安全；反過嚟「超時當成功」會令張單永遠印唔出又冇人知。
     */
    fun sendRaw(bytes: ByteArray): Result<Unit> {
        val svc = service
            ?: return Result.failure(IllegalStateException("Sunmi 內置打印機未連接（${lastError ?: "未綁定"}）"))
        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val cb = object : InnerResultCallback() {
            override fun onRunResult(isSuccess: Boolean) {
                if (!isSuccess) failure.compareAndSet(null, "打印失敗（onRunResult=false）")
                latch.countDown()
            }

            override fun onRaiseException(code: Int, msg: String?) {
                failure.compareAndSet(null, "打印異常 code=$code ${msg.orEmpty()}")
                latch.countDown()
            }

            override fun onReturnString(result: String?) {
                // 淨診斷，唔影響結果
                Log.d(TAG, "onReturnString: $result")
            }

            override fun onPrintResult(code: Int, msg: String?) {
                // 部分 ROM 會額外回報一次；非 0 記低但唔 latch（避免已經返回之後再改寫狀態）
                if (code != 0) Log.w(TAG, "onPrintResult code=$code ${msg.orEmpty()}")
            }
        }
        return try {
            svc.sendRAWData(bytes, cb)
            if (!latch.await(SEND_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Result.failure(IllegalStateException("打印超時（${SEND_ACK_TIMEOUT_MS}ms 冇 ack）"))
            } else {
                val f = failure.get()
                if (f == null) Result.success(Unit) else Result.failure(IllegalStateException(f))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 走紙（cut 前留白）。Sunmi 部分機型需要顯式行距先切得靚。 */
    fun lineWrap(n: Int) {
        runCatching { service?.lineWrap(n, null) }
    }

    private object LooperHolder {
        fun isMainThread(): Boolean =
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
    }
}
