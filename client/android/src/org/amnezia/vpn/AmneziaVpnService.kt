package org.amnezia.vpn

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.ConcurrentHashMap
import kotlin.LazyThreadSafetyMode.NONE
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.amnezia.vpn.protocol.BadConfigException
import org.amnezia.vpn.protocol.LoadLibraryException
import org.amnezia.vpn.protocol.Protocol
import org.amnezia.vpn.protocol.ProtocolState.CONNECTED
import org.amnezia.vpn.protocol.ProtocolState.CONNECTING
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTED
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTING
import org.amnezia.vpn.protocol.ProtocolState.RECONNECTING
import org.amnezia.vpn.protocol.ProtocolState.UNKNOWN
import org.amnezia.vpn.protocol.VpnException
import org.amnezia.vpn.protocol.VpnStartException
import org.amnezia.vpn.protocol.awg.Awg
import org.amnezia.vpn.protocol.cloak.Cloak
import org.amnezia.vpn.protocol.openvpn.OpenVpn
import org.amnezia.vpn.protocol.putStatus
import org.amnezia.vpn.protocol.wireguard.Wireguard
import org.amnezia.vpn.util.Log
import org.amnezia.vpn.util.Prefs
import org.amnezia.vpn.util.net.NetworkState
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "AmneziaVpnService"

const val MSG_VPN_CONFIG = "VPN_CONFIG"
const val MSG_ERROR = "ERROR"
const val MSG_SAVE_LOGS = "SAVE_LOGS"
const val MSG_CLIENT_NAME = "CLIENT_NAME"

const val AFTER_PERMISSION_CHECK = "AFTER_PERMISSION_CHECK"
private const val PREFS_CONFIG_KEY = "LAST_CONF"
private const val PREFS_SERVER_NAME = "LAST_SERVER_NAME"
private const val PREFS_SERVER_INDEX = "LAST_SERVER_INDEX"
private const val PROCESS_NAME = "org.amnezia.vpn:amneziaVpnService"
private const val NOTIFICATION_ID = 1337
private const val STATISTICS_SENDING_TIMEOUT = 1000L
private const val DISCONNECT_TIMEOUT = 5000L
private const val STOP_SERVICE_TIMEOUT = 5000L

class AmneziaVpnService : VpnService() {

    private lateinit var mainScope: CoroutineScope
    private lateinit var connectionScope: CoroutineScope
    private var isServiceBound = false
    private var protocol: Protocol? = null
    private val protocolCache = mutableMapOf<String, Protocol>()
    private var protocolState = MutableStateFlow(UNKNOWN)
    private var serverName: String? = null
    private var serverIndex: Int = -1

    private val isConnected
        get() = protocolState.value == CONNECTED

    private val isDisconnected
        get() = protocolState.value == DISCONNECTED

    private val isUnknown
        get() = protocolState.value == UNKNOWN

    private var connectionJob: Job? = null
    private var disconnectionJob: Job? = null
    private var statisticsSendingJob: Job? = null
    private lateinit var networkState: NetworkState
    private val clientMessengers = ConcurrentHashMap<Messenger, IpcMessenger>()

    private val isActivityConnected
        get() = clientMessengers.any { it.value.name == ACTIVITY_MESSENGER_NAME }

    private val connectionExceptionHandler = CoroutineExceptionHandler { _, e ->
        protocolState.value = DISCONNECTED
        protocol = null
        when (e) {
            is IllegalArgumentException,
            is VpnStartException,
            is VpnException -> onError(e.message ?: e.toString())

            is JSONException,
            is BadConfigException -> onError("VPN config format error: ${e.message}")

            is LoadLibraryException -> onError("${e.message}. Caused: ${e.cause?.message}")

            else -> throw e
        }
    }

    private val actionMessageHandler: Handler by lazy(NONE) {
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                val action = msg.extractIpcMessage<Action>()
                Log.d(TAG, "Handle action: $action")
                when (action) {
                    Action.REGISTER_CLIENT -> {
                        val clientName = msg.data.getString(MSG_CLIENT_NAME)
                        val messenger = IpcMessenger(msg.replyTo, clientName)
                        clientMessengers[msg.replyTo] = messenger
                        Log.d(TAG, "Messenger client '$clientName' was registered")
                        if (clientName == ACTIVITY_MESSENGER_NAME && isConnected) launchSendingStatistics()
                    }

                    Action.UNREGISTER_CLIENT -> {
                        clientMessengers.remove(msg.replyTo)?.let {
                            Log.d(TAG, "Messenger client '${it.name}' was unregistered")
                            if (it.name == ACTIVITY_MESSENGER_NAME) stopSendingStatistics()
                        }
                    }

                    Action.CONNECT -> {
                        connect(msg.data.getString(MSG_VPN_CONFIG))
                    }

                    Action.DISCONNECT -> {
                        disconnect()
                    }

                    Action.REQUEST_STATUS -> {
                        clientMessengers[msg.replyTo]?.let { clientMessenger ->
                            clientMessenger.send {
                                ServiceEvent.STATUS.packToMessage {
                                    putStatus(this@AmneziaVpnService.protocolState.value)
                                }
                            }
                        }
                    }

                    Action.SET_SAVE_LOGS -> {
                        Log.saveLogs = msg.data.getBoolean(MSG_SAVE_LOGS)
                    }
                }
            }
        }
    }

    private val vpnServiceMessenger: Messenger by lazy(NONE) {
        Messenger(actionMessageHandler)
    }

    /**
     * Notification setup
     */
    private val foregroundServiceTypeCompat
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> FOREGROUND_SERVICE_TYPE_MANIFEST
            else -> 0
        }

    private val notification: Notification by lazy(NONE) {
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_amnezia_round)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, AmneziaActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Service overloaded methods
     */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Create Amnezia VPN service")
        mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + connectionExceptionHandler)
        loadServerData()
        launchProtocolStateHandler()
        networkState = NetworkState(this, ::reconnect)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isAlwaysOnCompat =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isAlwaysOn
            else intent?.component?.packageName != packageName

        if (isAlwaysOnCompat) {
            Log.d(TAG, "Start service via Always-on")
            connect()
        } else if (intent?.getBooleanExtra(AFTER_PERMISSION_CHECK, false) == true) {
            Log.d(TAG, "Start service after permission check")
            connect()
        } else {
            Log.d(TAG, "Start service")
            connect(intent?.getStringExtra(MSG_VPN_CONFIG))
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceTypeCompat)
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind by $intent")
        if (intent?.action == SERVICE_INTERFACE) return super.onBind(intent)
        isServiceBound = true
        return vpnServiceMessenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind by $intent")
        if (intent?.action != SERVICE_INTERFACE) {
            if (clientMessengers.isEmpty()) {
                isServiceBound = false
                if (isUnknown || isDisconnected) stopService()
            }
        }
        return true
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "onRebind by $intent")
        if (intent?.action != SERVICE_INTERFACE) {
            isServiceBound = true
        }
        super.onRebind(intent)
    }

    override fun onRevoke() {
        Log.d(TAG, "onRevoke")
        // Calls to onRevoke() method may not happen on the main thread of the process
        mainScope.launch {
            disconnect()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Destroy service")
        runBlocking {
            disconnect()
            disconnectionJob?.join()
        }
        connectionScope.cancel()
        mainScope.cancel()
        super.onDestroy()
    }

    private fun stopService() {
        Log.d(TAG, "Stop service")
        // the coroutine below will be canceled during the onDestroy call
        mainScope.launch {
            delay(STOP_SERVICE_TIMEOUT)
            Log.w(TAG, "Stop service timeout, kill process")
            Process.killProcess(Process.myPid())
        }
        stopSelf()
    }

    /**
     * Methods responsible for processing VPN connection
     */
    private fun launchProtocolStateHandler() {
        mainScope.launch {
            // drop first default UNKNOWN state
            protocolState.drop(1).collect { protocolState ->
                Log.d(TAG, "Protocol state changed: $protocolState")
                when (protocolState) {
                    CONNECTED -> {
                        networkState.bindNetworkListener()
                        if (isActivityConnected) launchSendingStatistics()
                    }

                    DISCONNECTED -> {
                        networkState.unbindNetworkListener()
                        stopSendingStatistics()
                        if (!isServiceBound) stopService()
                    }

                    DISCONNECTING -> {
                        networkState.unbindNetworkListener()
                        stopSendingStatistics()
                    }

                    RECONNECTING -> {
                        stopSendingStatistics()
                    }

                    CONNECTING, UNKNOWN -> {}
                }

                clientMessengers.send {
                    ServiceEvent.STATUS_CHANGED.packToMessage {
                        putStatus(protocolState)
                    }
                }

                VpnStateStore.store { VpnState(protocolState, serverName, serverIndex) }
            }
        }
    }

    @MainThread
    private fun launchSendingStatistics() {
        /* if (isServiceBound && isConnected) {
            statisticsSendingJob = mainScope.launch {
                while (true) {
                    clientMessenger.send {
                        ServiceEvent.STATISTICS_UPDATE.packToMessage {
                            putStatistics(protocol?.statistics ?: Statistics.EMPTY_STATISTICS)
                        }
                    }
                    delay(STATISTICS_SENDING_TIMEOUT)
                }
            }
        } */
    }

    @MainThread
    private fun stopSendingStatistics() {
        statisticsSendingJob?.cancel()
    }

    @MainThread
    private fun connect(vpnConfig: String? = null) {
        if (vpnConfig == null) {
            connectToVpn(Prefs.load(PREFS_CONFIG_KEY))
        } else {
            Prefs.save(PREFS_CONFIG_KEY, vpnConfig)
            connectToVpn(vpnConfig)
        }
    }

    @MainThread
    private fun connectToVpn(vpnConfig: String) {
        if (isConnected || protocolState.value == CONNECTING) return

        Log.d(TAG, "Start VPN connection")

        protocolState.value = CONNECTING

        val config = parseConfigToJson(vpnConfig)
        saveServerData(config)
        if (config == null) {
            onError("Invalid VPN config")
            protocolState.value = DISCONNECTED
            return
        }

        if (!checkPermission()) {
            protocolState.value = DISCONNECTED
            return
        }

        connectionJob = connectionScope.launch {
            disconnectionJob?.join()
            disconnectionJob = null

            protocol = getProtocol(config.getString("protocol"))
            protocol?.startVpn(config, Builder(), ::protect)
        }
    }

    @MainThread
    private fun disconnect() {
        if (isUnknown || isDisconnected || protocolState.value == DISCONNECTING) return

        Log.d(TAG, "Stop VPN connection")

        protocolState.value = DISCONNECTING

        disconnectionJob = connectionScope.launch {
            connectionJob?.join()
            connectionJob = null

            protocol?.stopVpn()
            protocol = null
            try {
                withTimeout(DISCONNECT_TIMEOUT) {
                    // waiting for disconnect state
                    protocolState.first { it == DISCONNECTED }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Disconnect timeout")
                stopService()
            }
        }
    }

    @MainThread
    private fun reconnect() {
        if (!isConnected) return

        Log.d(TAG, "Reconnect VPN")

        protocolState.value = RECONNECTING

        connectionJob = connectionScope.launch {
            protocol?.reconnectVpn(Builder())
        }
    }

    @MainThread
    private fun getProtocol(protocolName: String): Protocol =
        protocolCache[protocolName]
            ?: when (protocolName) {
                "wireguard" -> Wireguard()
                "awg" -> Awg()
                "openvpn" -> OpenVpn()
                "cloak" -> Cloak()
                else -> throw IllegalArgumentException("Protocol '$protocolName' not found")
            }.apply { initialize(applicationContext, protocolState, ::onError) }
                .also { protocolCache[protocolName] = it }

    /**
     * Utils methods
     */
    private fun onError(msg: String) {
        Log.e(TAG, msg)
        mainScope.launch {
            clientMessengers.send {
                ServiceEvent.ERROR.packToMessage {
                    putString(MSG_ERROR, msg)
                }
            }
        }
    }

    private fun parseConfigToJson(vpnConfig: String): JSONObject? =
        if (vpnConfig.isBlank()) {
            null
        } else {
            try {
                JSONObject(vpnConfig)
            } catch (e: JSONException) {
                onError("Invalid VPN config json format: ${e.message}")
                null
            }
        }

    private fun saveServerData(config: JSONObject?) {
        serverName = config?.opt("description") as String?
        serverIndex = config?.opt("serverIndex") as Int? ?: -1
        Prefs.save(PREFS_SERVER_NAME, serverName)
        Prefs.save(PREFS_SERVER_INDEX, serverIndex)
    }

    private fun loadServerData() {
        serverName = Prefs.load<String>(PREFS_SERVER_NAME).ifBlank { null }
        if (serverName != null) serverIndex = Prefs.load(PREFS_SERVER_INDEX)
    }

    private fun checkPermission(): Boolean =
        if (prepare(applicationContext) != null) {
            Intent(this, VpnRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.also {
                startActivity(it)
            }
            false
        } else {
            true
        }

    companion object {
        fun isRunning(context: Context): Boolean =
            (context.getSystemService(ACTIVITY_SERVICE) as ActivityManager)
                .runningAppProcesses.any {
                    it.processName == PROCESS_NAME && it.importance <= IMPORTANCE_FOREGROUND_SERVICE
                }
    }
}
