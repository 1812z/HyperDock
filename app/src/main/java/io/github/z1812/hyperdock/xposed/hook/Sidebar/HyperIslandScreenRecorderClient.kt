package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger

/** Small stable-protocol client; deliberately avoids any HyperIsland implementation classes. */
internal object HyperIslandScreenRecorderClient {
    private const val ACTION = "io.github.hyperisland.action.SCREEN_RECORDER_CONTROL"
    private const val SERVICE = "io.github.hyperisland.screenrecorder.ScreenRecorderControlService"
    private const val PACKAGE = "io.github.hyperisland"
    private const val CONTROL = 24
    private const val REGISTER = 20
    private var remote: Messenger? = null
    private var connection: ServiceConnection? = null

    fun start(context: Context, motionPhoto: Boolean) {
        val send = { messenger: Messenger ->
            val msg = Message.obtain(null, CONTROL)
            msg.replyTo = Messenger(android.os.Handler(android.os.Looper.getMainLooper()))
            msg.data = Bundle().apply { putString("op", "start"); if (motionPhoto) putBoolean("motion_photo", true) }
            runCatching { messenger.send(msg) }
        }
        remote?.let { send(it); return }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val messenger = Messenger(binder)
                remote = messenger
                val register = Message.obtain(null, REGISTER).apply {
                    replyTo = Messenger(android.os.Handler(android.os.Looper.getMainLooper()))
                }
                runCatching { messenger.send(register); send(messenger) }
            }
            override fun onServiceDisconnected(name: ComponentName) { remote = null; connection = null }
        }
        connection = conn
        val intent = Intent(ACTION).setPackage(PACKAGE).setClassName(PACKAGE, SERVICE)
        runCatching { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
    }
}
