package io.github.xblocker.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives the libxposed service binder that LSPosed pushes into the module app through the
 * `<package>.XposedService` provider (`SendBinder`). Whether a framework delivers the binder
 * to legacy-API modules depends on the fork; when nothing arrives, the UI falls back to
 * marker-based detection and manual guidance.
 *
 * The service is called through hand-written binder parcels instead of generated AIDL
 * stubs: the Chinese project path breaks AGP's AIDL dependency-file parsing on Windows,
 * and libxposed pins AIDL method IDs, so the wire format is small and stable.
 */
class XposedServiceProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // Only the framework's daemon (root/system) may inject the service binder.
        if (method == XposedServiceClient.SEND_BINDER && Binder.getCallingUid() in SYSTEM_UIDS) {
            @Suppress("DEPRECATION") val binder = extras?.getBinder("binder")
            if (binder != null) XposedServiceClient.onBinder(binder)
            return Bundle.EMPTY
        }
        return null
    }

    private companion object {
        val SYSTEM_UIDS = setOf(0, 1000)
    }
}

object XposedServiceClient {
    private const val TAG = "XBlockerScope"
    /** AIDL method IDs are offsets from FIRST_CALL_TRANSACTION, not absolute wire codes.
     * https://github.com/libxposed/service/tree/master/interface/src/main/aidl/io/github/libxposed/service
     */
    private const val DESCRIPTOR = "io.github.libxposed.service.IXposedService"
    private const val CALLBACK_DESCRIPTOR = "io.github.libxposed.service.IXposedScopeCallback"
    private const val TRANSACTION_GET_SCOPE = IBinder.FIRST_CALL_TRANSACTION + 10
    private const val TRANSACTION_REQUEST_SCOPE = IBinder.FIRST_CALL_TRANSACTION + 11
    private const val CALLBACK_APPROVED = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val CALLBACK_FAILED = IBinder.FIRST_CALL_TRANSACTION + 2
    const val SEND_BINDER = "SendBinder"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var binder: IBinder? = null
    private val waiting = CopyOnWriteArrayList<(IBinder) -> Unit>()
    // Match libxposed's callback lifetime: retain until completion, release on send failure.
    private val scopeCallbacks = ConcurrentHashMap.newKeySet<IBinder>()

    fun onBinder(raw: IBinder) {
        android.util.Log.d(TAG, "binder offered: $raw alive=${raw.isBinderAlive}")
        if (!raw.pingBinder()) return
        binder = raw
        val callbacks = waiting.toList()
        waiting.clear()
        for (callback in callbacks) runCatching { main.post { callback(raw) } }
    }

    fun connected(): Boolean = binder?.isBinderAlive == true

    /** Runs on the main thread as soon as the service is available. */
    fun onConnected(callback: (IBinder) -> Unit) {
        val current = binder
        if (current != null && current.isBinderAlive) main.post { callback(current) }
        else waiting.add(callback)
    }

    /** Currently granted scope packages, or null when the call fails or is unsupported. */
    fun scope(): List<String>? {
        val target = binder ?: run { android.util.Log.d(TAG, "scope: no binder"); return null }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!target.transact(TRANSACTION_GET_SCOPE, data, reply, 0)) {
                android.util.Log.d(TAG, "scope: transact returned false")
                return null
            }
            reply.readException()
            reply.createStringArrayList().orEmpty().also { android.util.Log.d(TAG, "scope: $it") }
        } catch (error: Exception) {
            android.util.Log.d(TAG, "scope: ${error.javaClass.simpleName}: ${error.message?.take(120)}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Asks the framework to prompt the user for the given packages; the oneway result
     * arrives on [onResult] from the binder thread (posted to the main thread).
     */
    fun requestScope(packages: List<String>, onResult: (approved: List<String>?, error: String?) -> Unit) {
        val target = binder
        android.util.Log.d(TAG, "requestScope: $packages binder=${target != null}")
        if (target == null || !target.isBinderAlive) {
            main.post { onResult(null, "LSPosed 服务未连接") }
            return
        }
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(CALLBACK_DESCRIPTOR)
                    true
                }
                CALLBACK_APPROVED -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    val approved = data.createStringArrayList().orEmpty()
                    android.util.Log.d(TAG, "scope approved: $approved")
                    if (scopeCallbacks.remove(this)) main.post { onResult(approved, null) }
                    true
                }
                CALLBACK_FAILED -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    val message = data.readString() ?: "授权未完成"
                    android.util.Log.d(TAG, "scope failed: ${message.take(120)}")
                    if (scopeCallbacks.remove(this)) main.post { onResult(null, message) }
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
        scopeCallbacks.add(callback)
        val data = Parcel.obtain()
        val sent = try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeStringList(packages)
            data.writeStrongBinder(callback)
            target.transact(TRANSACTION_REQUEST_SCOPE, data, null, IBinder.FLAG_ONEWAY)
        } catch (error: Exception) {
            android.util.Log.d(TAG, "requestScope send: ${error.javaClass.simpleName}: ${error.message?.take(120)}")
            false
        } finally {
            data.recycle()
        }
        android.util.Log.d(TAG, "requestScope sent=$sent")
        if (!sent && scopeCallbacks.remove(callback)) main.post { onResult(null, "请求发送失败") }
    }
}
