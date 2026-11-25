package com.echora.app.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.echora.app.IEchoraCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

class ShellConnection(private val context: Context) {

    enum class ShellStatus {
        NO_PERMISSION, NOT_READY, CONNECTING, READY, ERROR, UNSUPPORTED
    }

    private val _status = MutableStateFlow<ShellStatus>(ShellStatus.NOT_READY)
    val status: StateFlow<ShellStatus> = _status

    private val binderDeath = IBinder.DeathRecipient { binderDied() }
    private var capture: IEchoraCapture? = null
    private var binder: IBinder? = null
    private var scope = CoroutineScope(Dispatchers.IO)
    private var retryCount = 0
    private var isStopped = false
    private var serviceConnection: ServiceConnection? = null
    private var currentArgs: Shizuku.UserServiceArgs? = null

    fun start() {
        if (isStopped) {
            isStopped = false
            scope = CoroutineScope(Dispatchers.IO)
        }
        scope.launch {
            if (!Shizuku.pingBinder()) {
                updateStatus(ShellStatus.NOT_READY)
                return@launch
            }
            if (Shizuku.isPreV11()) {
                updateStatus(ShellStatus.UNSUPPORTED)
                return@launch
            }
            if (Shizuku.checkSelfPermission() != 0) {
                updateStatus(ShellStatus.NO_PERMISSION)
                return@launch
            }
            bindService()
        }
    }

    fun stop(removeService: Boolean = true) {
        isStopped = true
        scope.cancel()
        unbindService(removeService)
        capture = null
        binder = null
        updateStatus(ShellStatus.NOT_READY)
        retryCount = 0
    }

    fun capture(): IEchoraCapture? = capture

    private fun bindService() {
        updateStatus(ShellStatus.CONNECTING)
        val componentName = ComponentName(context, EchoraShellService::class.java)
        
        val args = Shizuku.UserServiceArgs(componentName)
            .daemon(false)
            .debuggable(true)
            .version(6)
            .processNameSuffix("shizuku")
        currentArgs = args

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.d("EchoraShellConn", "Service connected: $name")
                service?.linkToDeath(binderDeath, 0)
                binder = service
                capture = IEchoraCapture.Stub.asInterface(service)
                retryCount = 0
                updateStatus(ShellStatus.READY)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d("EchoraShellConn", "Service disconnected: $name")
                capture = null
                binder = null
                if (isStopped == false) {
                    scheduleRebind()
                } else {
                    updateStatus(ShellStatus.NOT_READY)
                }
            }
        }

        Shizuku.bindUserService(args, serviceConnection!!)
    }

    private fun unbindService(removeService: Boolean = false) {
        serviceConnection?.let { conn ->
            currentArgs?.let { args ->
                if (Shizuku.pingBinder()) Shizuku.unbindUserService(args, conn, removeService)
            }
            binder?.unlinkToDeath(binderDeath, 0)
            binder = null
            serviceConnection = null
            currentArgs = null
        }
    }

    private fun binderDied() {
        Log.w("EchoraShellConn", "Binder died, scheduling rebind")
        capture = null
        binder = null
        if (isStopped == false) {
            scope.launch {
                scheduleRebind()
            }
        }
    }

    private fun scheduleRebind() {
        unbindService()
        val delay = when {
            retryCount < 3 -> 1000L * (1 shl retryCount)
            retryCount < 6 -> 8000L
            else -> 30000L
        }
        retryCount++
        scope.launch {
            withContext(Dispatchers.IO) {
                delay(delay)
                if (isStopped == false) {
                    start()
                }
            }
        }
    }

    private fun updateStatus(newStatus: ShellStatus) {
        _status.value = newStatus
    }
}
