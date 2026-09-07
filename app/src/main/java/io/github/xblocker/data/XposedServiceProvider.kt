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

/**
 * Receives the libxposed service binder that LSPosed pushes into the module app through the
 * `<package>.XposedService` provider (`SendBinder`). Whether a framework delivers the binder
 * to legacy-API modules depends on the fork; when nothing arrives, the UI falls back to
 * marker-based detection and manual guidance.
 *
 * The service is called through hand-written binder parcels instead of generated AIDL
 * stubs: the Chinese project path breaks AGP's AIDL dependency-file parsing on Windows,
 * and libxposed pins absolute transaction codes, so the wire format is small and stable.
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
    /** Absolute transaction codes from libxposed IXposedService/IXposedScopeCallback AIDL. */
    private const val DESCRIPTOR = "io.github.libxposed.service.IXposedService"
    private const val CALLBACK_DESCRIPTOR = "io.github.libxposed.service.IXposedScopeCallback"
    private const val TRANSACTION_GET_SCOPE = 10
    private const val TRANSACTION_REQUEST_SCOPE = 11
    private const val CALLBACK_APPROVED = 1
    private const val CALLBACK_FAILED = 2
    const val SEND_BINDER = "SendBinder"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var binder: IBinder? = null
    private val waiting = CopyOnWriteArrayList<(IBinder) -> Unit>()

    fun onBinder(raw: IBinder) {
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
        val target = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!target.transact(TRANSACTION_GET_SCOPE, data, reply, 0)) return null
            reply.readException()
            reply.createStringArrayList().orEmpty()
        } catch (_: Exception) {
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
        if (target == null || !target.isBinderAlive) {
            onResult(null, "LSPosed 服务未连接")
            return
        }
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
                CALLBACK_APPROVED -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    val approved = data.createStringArrayList().orEmpty()
                    main.post { onResult(approved, null) }
                    true
                }
                CALLBACK_FAILED -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    val message = data.readString() ?: "授权未完成"
                    main.post { onResult(null, message) }
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
        val data = Parcel.obtain()
        val sent = try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeStringList(packages)
            target.transact(TRANSACTION_REQUEST_SCOPE, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
            false
        } finally {
            data.recycle()
        }
        if (!sent) main.post { onResult(null, "请求发送失败") }
    }
}
