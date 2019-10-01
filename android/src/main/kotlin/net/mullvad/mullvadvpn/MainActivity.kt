package net.mullvad.mullvadvpn

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.support.v4.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import net.mullvad.mullvadvpn.dataproxy.AccountCache
import net.mullvad.mullvadvpn.dataproxy.AppVersionInfoCache
import net.mullvad.mullvadvpn.dataproxy.KeyStatusListener
import net.mullvad.mullvadvpn.dataproxy.LocationInfoCache
import net.mullvad.mullvadvpn.dataproxy.MullvadProblemReport
import net.mullvad.mullvadvpn.dataproxy.RelayListListener
import net.mullvad.mullvadvpn.dataproxy.SettingsListener
import net.mullvad.mullvadvpn.util.SmartDeferred

class MainActivity : FragmentActivity() {
    companion object {
        val KEY_SHOULD_CONNECT = "should_connect"
    }

    var daemon = CompletableDeferred<MullvadDaemon>()
        private set
    var service = CompletableDeferred<MullvadVpnService.LocalBinder>()
        private set

    var appVersionInfoCache = AppVersionInfoCache(this)
    val connectionProxy = SmartDeferred(configureConnectionProxy())
    val keyStatusListener = KeyStatusListener(daemon)
    val problemReport = MullvadProblemReport()
    var settingsListener = SettingsListener(this)
    var relayListListener = RelayListListener(this)
    val locationInfoCache = LocationInfoCache(daemon, relayListListener)
    val accountCache = AccountCache(settingsListener, daemon)

    private var quitJob: Job? = null
    private var serviceToStop: MullvadVpnService.LocalBinder? = null
    private var waitForDaemonJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val localBinder = binder as MullvadVpnService.LocalBinder

            waitForDaemonJob = GlobalScope.launch(Dispatchers.Default) {
                localBinder.resetComplete?.await()
                service.complete(localBinder)
                daemon.complete(localBinder.daemon.await())
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            waitForDaemonJob?.cancel()
            waitForDaemonJob = null

            service.cancel()
            daemon.cancel()

            service = CompletableDeferred<MullvadVpnService.LocalBinder>()
            daemon = CompletableDeferred<MullvadDaemon>()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        if (savedInstanceState == null) {
            addInitialFragment()
        }

        appVersionInfoCache.onCreate()

        if (intent.getBooleanExtra(KEY_SHOULD_CONNECT, false) ?: false) {
            connectionProxy.awaitThen { connect() }
        }
    }

    override fun onStart() {
        super.onStart()

        val intent = Intent(this, MullvadVpnService::class.java)

        startService(intent)
        bindService(intent, serviceConnection, 0)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        setVpnPermission(resultCode == Activity.RESULT_OK)
    }

    override fun onStop() {
        quitJob?.cancel()

        serviceToStop?.apply { stop() }
        unbindService(serviceConnection)

        super.onStop()
    }

    override fun onDestroy() {
        connectionProxy.cancel()

        accountCache.onDestroy()
        appVersionInfoCache.onDestroy()
        keyStatusListener.onDestroy()
        relayListListener.onDestroy()
        settingsListener.onDestroy()

        waitForDaemonJob?.cancel()
        daemon.cancel()

        super.onDestroy()
    }

    fun openSettings() {
        supportFragmentManager?.beginTransaction()?.apply {
            setCustomAnimations(
                R.anim.fragment_enter_from_bottom,
                R.anim.do_nothing,
                R.anim.do_nothing,
                R.anim.fragment_exit_to_bottom
            )
            replace(R.id.main_fragment, SettingsFragment())
            addToBackStack(null)
            commit()
        }
    }

    fun requestVpnPermission(intent: Intent) {
        startActivityForResult(intent, 0)
    }

    fun quit() {
        quitJob?.cancel()
        quitJob = GlobalScope.launch(Dispatchers.Main) {
            serviceToStop = service.await()
            finishAndRemoveTask()
        }
    }

    private fun addInitialFragment() {
        supportFragmentManager?.beginTransaction()?.apply {
            add(R.id.main_fragment, LaunchFragment())
            commit()
        }
    }

    private fun configureConnectionProxy() = GlobalScope.async(Dispatchers.Default) {
        service.await().connectionProxy.apply {
            mainActivity = this@MainActivity
        }
    }

    private fun setVpnPermission(allow: Boolean) = GlobalScope.launch(Dispatchers.Default) {
        connectionProxy.awaitThen {
            vpnPermission.complete(allow)
        }
    }

    private fun fetchSettings() = GlobalScope.async(Dispatchers.Default) {
        daemon.await().getSettings()
    }
}
