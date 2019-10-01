package net.mullvad.mullvadvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.support.v4.app.NotificationCompat
import net.mullvad.mullvadvpn.dataproxy.ConnectionProxy
import net.mullvad.mullvadvpn.model.ActionAfterDisconnect
import net.mullvad.mullvadvpn.model.TunnelState

val CHANNEL_ID = "vpn_tunnel_status"
val FOREGROUND_NOTIFICATION_ID: Int = 1
val KEY_CONNECT_ACTION = "connect_action"
val KEY_DISCONNECT_ACTION = "disconnect_action"
val PERMISSION_TUNNEL_ACTION = "net.mullvad.mullvadvpn.permission.TUNNEL_ACTION"

class ForegroundNotificationManager(val service: Service, val connectionProxy: ConnectionProxy) {
    private val notificationManager =
        service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val listenerId = connectionProxy.onStateChange.subscribe { state ->
        tunnelState = state
    }

    private var reconnecting = false
    private var showingReconnecting = false

    private var tunnelState: TunnelState = TunnelState.Disconnected()
        set(value) {
            field = value

            reconnecting =
                (value is TunnelState.Disconnecting &&
                    value.actionAfterDisconnect is ActionAfterDisconnect.Reconnect) ||
                (value is TunnelState.Connecting && reconnecting)

            updateNotification()
        }

    private val notificationText: Int
        get() {
            val state = tunnelState

            return when (state) {
                is TunnelState.Disconnected -> R.string.unsecured
                is TunnelState.Connecting -> {
                    if (reconnecting) {
                        R.string.reconnecting
                    } else {
                        R.string.connecting
                    }
                }
                is TunnelState.Connected -> R.string.secured
                is TunnelState.Disconnecting -> {
                    when (state.actionAfterDisconnect) {
                        is ActionAfterDisconnect.Reconnect -> R.string.reconnecting
                        else -> R.string.disconnecting
                    }
                }
                is TunnelState.Blocked -> R.string.blocking_all_connections
            }
        }

    private val tunnelActionText: Int
        get() {
            val state = tunnelState

            return when (state) {
                is TunnelState.Disconnected -> R.string.connect
                is TunnelState.Connecting -> R.string.cancel
                is TunnelState.Connected -> R.string.disconnect
                is TunnelState.Disconnecting -> {
                    when (state.actionAfterDisconnect) {
                        is ActionAfterDisconnect.Reconnect -> R.string.cancel
                        else -> R.string.connect
                    }
                }
                is TunnelState.Blocked -> R.string.disconnect
            }
        }

    private val tunnelActionKey: String
        get() {
            val state = tunnelState

            return when (state) {
                is TunnelState.Disconnected -> KEY_CONNECT_ACTION
                is TunnelState.Connecting -> KEY_DISCONNECT_ACTION
                is TunnelState.Connected -> KEY_DISCONNECT_ACTION
                is TunnelState.Disconnecting -> {
                    when (state.actionAfterDisconnect) {
                        is ActionAfterDisconnect.Reconnect -> KEY_DISCONNECT_ACTION
                        else -> KEY_CONNECT_ACTION
                    }
                }
                is TunnelState.Blocked -> KEY_DISCONNECT_ACTION
            }
        }

    private val tunnelActionIcon: Int
        get() {
            if (tunnelActionKey == KEY_CONNECT_ACTION) {
                return R.drawable.icon_notification_connect
            } else {
                return R.drawable.icon_notification_disconnect
            }
        }

    private val connectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onConnect?.invoke()
        }
    }

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onDisconnect?.invoke()
        }
    }

    var onConnect: (() -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null
    var loggedIn = false
        set(value) {
            field = value
            updateNotification()
        }

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            initChannel()
        }

        service.apply {
            val connectFilter = IntentFilter(KEY_CONNECT_ACTION)
            val disconnectFilter = IntentFilter(KEY_DISCONNECT_ACTION)

            registerReceiver(connectReceiver, connectFilter, PERMISSION_TUNNEL_ACTION, null)
            registerReceiver(disconnectReceiver, disconnectFilter, PERMISSION_TUNNEL_ACTION, null)

            startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification())
        }
    }

    fun onDestroy() {
        listenerId?.let { listener ->
            connectionProxy.onStateChange.unsubscribe(listener)
        }

        service.apply {
            unregisterReceiver(connectReceiver)
            unregisterReceiver(disconnectReceiver)

            stopForeground(FOREGROUND_NOTIFICATION_ID)
        }
    }

    private fun initChannel() {
        val channelName = service.getString(R.string.foreground_notification_channel_name)
        val importance = NotificationManager.IMPORTANCE_MIN
        val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
            description = service.getString(R.string.foreground_notification_channel_description)
            setShowBadge(true)
        }

        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        if (!reconnecting || !showingReconnecting) {
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(service, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setAction(Intent.ACTION_MAIN)

        val pendingIntent =
            PendingIntent.getActivity(service, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification)
            .setColor(service.getColor(R.color.colorPrimary))
            .setContentTitle(service.getString(notificationText))
            .setContentIntent(pendingIntent)

        if (loggedIn) {
            builder.addAction(buildTunnelAction())
        }

        return builder.build()
    }

    private fun buildTunnelAction(): NotificationCompat.Action {
        val intent = Intent(tunnelActionKey).setPackage("net.mullvad.mullvadvpn")
        val pendingIntent =
            PendingIntent.getBroadcast(service, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT)

        val icon = tunnelActionIcon
        val label = service.getString(tunnelActionText)

        return NotificationCompat.Action(icon, label, pendingIntent)
    }
}
