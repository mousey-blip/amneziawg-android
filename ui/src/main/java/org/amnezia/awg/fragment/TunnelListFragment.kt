/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.fragment

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.databinding.Observable
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.amnezia.awg.Application
import org.amnezia.awg.BR
import org.amnezia.awg.R
import org.amnezia.awg.activity.ErawanQrScannerActivity
import org.amnezia.awg.activity.TunnelCreatorActivity
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.databinding.FilteredKeyedArrayList
import org.amnezia.awg.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import org.amnezia.awg.databinding.TunnelListFragmentBinding
import org.amnezia.awg.databinding.TunnelListItemBinding
import org.amnezia.awg.erawan.ErawanApi
import org.amnezia.awg.erawan.ErawanApiException
import org.amnezia.awg.erawan.ErawanPrefs
import org.amnezia.awg.model.ObservableTunnel
import org.amnezia.awg.util.ErrorMessages
import org.amnezia.awg.util.QrCodeFromFileScanner
import org.amnezia.awg.util.TunnelImporter
import org.amnezia.awg.widget.MultiselectableRelativeLayout
import androidx.core.view.isVisible
import org.amnezia.awg.erawan.ErawanBillingManager
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Fragment containing a list of known AmneziaWG tunnels. It allows creating and deleting tunnels.
 */
class TunnelListFragment : BaseFragment() {
    private val actionModeListener = ActionModeListener()
    private var actionMode: ActionMode? = null
    private var backPressedCallback: OnBackPressedCallback? = null
    private var binding: TunnelListFragmentBinding? = null

    // Mirrors the manager's tunnel list with the auto-created "Erawan" tunnel excluded,
    // so it (and its row positions) never show up alongside user-imported tunnels.
    private var visibleTunnels: FilteredKeyedArrayList<String, ObservableTunnel>? = null
    private val tunnelFileImportResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
        if (data == null) return@registerForActivityResult
        val activity = activity ?: return@registerForActivityResult
        val contentResolver = activity.contentResolver ?: return@registerForActivityResult
        activity.lifecycleScope.launch {
            if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                try {
                    val qrCodeFromFileScanner = QrCodeFromFileScanner(contentResolver, QRCodeReader())
                    val result = qrCodeFromFileScanner.scan(data)
                    TunnelImporter.importTunnel(parentFragmentManager, result.text) { showSnackbar(it) }
                } catch (e: Exception) {
                    val error = ErrorMessages[e]
                    val message = Application.get().resources.getString(R.string.import_error, error)
                    Log.e(TAG, message, e)
                    showSnackbar(message)
                }
            } else {
                TunnelImporter.importTunnel(contentResolver, data) { showSnackbar(it) }
            }
        }
    }

    private val qrImportResultLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents
        val activity = activity
        if (qrCode != null && activity != null) {
            activity.lifecycleScope.launch { TunnelImporter.importTunnel(parentFragmentManager, qrCode) { showSnackbar(it) } }
        }
    }

    // Bridges the system VPN-permission dialog into the same suspend chain as the
    // Connect tap, so there is no separate "second" coroutine completing the connect later.
    private var pendingPermissionContinuation: CancellableContinuation<Unit>? = null
    private val erawanPermissionResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingPermissionContinuation?.resumeWith(Result.success(Unit))
        pendingPermissionContinuation = null
    }

    // The Erawan tunnel this fragment is currently tracking; null until either a
    // pre-existing one is found at startup or the first Connect tap creates one.
    private var erawanTunnel: ObservableTunnel? = null

    // Non-null target state while a Connect/Disconnect tap is in flight but the
    // tunnel's real state hasn't settled yet — drives the spinner/disabled look.
    private var erawanPendingTarget: Tunnel.State? = null

    private var speedPollingJob: Job? = null

    // GoBackend/AwgQuickBackend.setState() calls tunnel.onStateChange() synchronously from
    // whatever thread is running the backend call (Dispatchers.IO, in TunnelManager), so this
    // callback can fire on a background thread. Every UI touch here must marshal to Main.
    private val erawanStateCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable, propertyId: Int) {
            if (propertyId == BR.state) {
                runOnMain {
                    erawanPendingTarget = null
                    updateConnectButton()
                }
            }
        }
    }

    // Marshals to the main thread if called off it; runs inline if already on Main.
    // Used everywhere a tunnel-state callback or backend coroutine might touch views.
    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
    }

    private fun attachErawanTunnel(tunnel: ObservableTunnel) {
        if (erawanTunnel !== tunnel) {
            erawanTunnel?.removeOnPropertyChangedCallback(erawanStateCallback)
            erawanTunnel = tunnel
            tunnel.addOnPropertyChangedCallback(erawanStateCallback)
        }
        updateConnectButton()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) {
            val checkedItems = savedInstanceState.getIntegerArrayList(CHECKED_ITEMS)
            if (checkedItems != null) {
                for (i in checkedItems) actionModeListener.setItemChecked(i, true)
            }
        }
        childFragmentManager.setFragmentResultListener(ErawanServerPickerSheet.REQUEST_KEY_SERVER_SELECTED, viewLifecycleOwner) { _, _ ->
            updateServerLabel()
        }
        childFragmentManager.setFragmentResultListener(ErawanServerPickerSheet.REQUEST_KEY_SHOW_UPGRADE, viewLifecycleOwner) { _, _ ->
            launchUpgradeFlow()
        }
        updateServerLabel()
        billingManager = ErawanBillingManager(
            activity = requireActivity() as androidx.fragment.app.FragmentActivity,
            prefs = erawanPrefs,
            onTierUpdated = { tier ->
                runOnMain {
                    erawanPrefs.tier = tier
                    updateUpgradeBanner()
                    showSnackbar(getString(R.string.erawan_billing_success))
                }
            },
            onError = { msgRes ->
                runOnMain { showSnackbar(getString(msgRes)) }
            }
        )
        updateUpgradeBanner()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelListFragmentBinding.inflate(inflater, container, false)
        val bottomSheet = AddTunnelsSheet()
        binding?.apply {
            createFab.setOnClickListener {
                if (childFragmentManager.findFragmentByTag("BOTTOM_SHEET") != null)
                    return@setOnClickListener
                childFragmentManager.setFragmentResultListener(AddTunnelsSheet.REQUEST_KEY_NEW_TUNNEL, viewLifecycleOwner) { _, bundle ->
                    when (bundle.getString(AddTunnelsSheet.REQUEST_METHOD)) {
                        AddTunnelsSheet.REQUEST_CREATE -> {
                            startActivity(Intent(requireActivity(), TunnelCreatorActivity::class.java))
                        }

                        AddTunnelsSheet.REQUEST_IMPORT -> {
                            tunnelFileImportResultLauncher.launch("*/*")
                        }

                        AddTunnelsSheet.REQUEST_SCAN -> {
                            qrImportResultLauncher.launch(
                                ScanOptions()
                                    .setOrientationLocked(false)
                                    .setBeepEnabled(false)
                                    .setPrompt(getString(R.string.qr_code_hint))
                                    .setCaptureActivity(ErawanQrScannerActivity::class.java)
                            )
                        }
                    }
                }
                bottomSheet.showNow(childFragmentManager, "BOTTOM_SHEET")
            }
            executePendingBindings()
        }
        backPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(this) { actionMode?.finish() }
        backPressedCallback?.isEnabled = false

        return binding?.root
    }

    override fun onDestroyView() {
        billingManager?.disconnect()
        billingManager = null
        erawanTunnel?.removeOnPropertyChangedCallback(erawanStateCallback)
        erawanTunnel = null
        speedPollingJob?.cancel()
        speedPollingJob = null
        visibleTunnels?.detach()
        visibleTunnels = null
        binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntegerArrayList(CHECKED_ITEMS, actionModeListener.getCheckedItems())
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        binding ?: return
        lifecycleScope.launch {
            val tunnels = visibleTunnels ?: return@launch
            if (newTunnel != null) viewForTunnel(newTunnel, tunnels)?.setSingleSelected(true)
            if (oldTunnel != null) viewForTunnel(oldTunnel, tunnels)?.setSingleSelected(false)
        }
    }

    private fun onTunnelDeletionFinished(count: Int, throwable: Throwable?) {
        val message: String
        val ctx = activity ?: Application.get()
        if (throwable == null) {
            message = ctx.resources.getQuantityString(R.plurals.delete_success, count, count)
        } else {
            val error = ErrorMessages[throwable]
            message = ctx.resources.getQuantityString(R.plurals.delete_error, count, count, error)
            Log.e(TAG, message, throwable)
        }
        showSnackbar(message)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding ?: return
        binding!!.fragment = this
        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            visibleTunnels?.detach()
            visibleTunnels = FilteredKeyedArrayList(tunnels) { it.name != ERAWAN_TUNNEL_NAME }.also {
                binding!!.tunnels = it
            }
            tunnels[ERAWAN_TUNNEL_NAME]?.let { attachErawanTunnel(it) } ?: updateConnectButton()
        }
        binding!!.rowConfigurationHandler = object : RowConfigurationHandler<TunnelListItemBinding, ObservableTunnel> {
            override fun onConfigureRow(binding: TunnelListItemBinding, item: ObservableTunnel, position: Int) {
                binding.fragment = this@TunnelListFragment
                binding.root.setOnClickListener {
                    if (actionMode == null) {
                        selectedTunnel = item
                    } else {
                        actionModeListener.toggleItemChecked(position)
                    }
                }
                binding.root.setOnLongClickListener {
                    actionModeListener.toggleItemChecked(position)
                    true
                }
                if (actionMode != null)
                    (binding.root as MultiselectableRelativeLayout).setMultiSelected(actionModeListener.checkedItems.contains(position))
                else
                    (binding.root as MultiselectableRelativeLayout).setSingleSelected(selectedTunnel == item)
            }
        }
    }

    private fun showSnackbar(message: CharSequence) {
        val binding = binding
        if (binding != null)
            Snackbar.make(binding.mainContainer, message, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.createFab)
                .show()
        else
            Toast.makeText(activity ?: Application.get(), message, Toast.LENGTH_SHORT).show()
    }

    private val erawanPrefs by lazy { ErawanPrefs(requireContext()) }

    private var billingManager: ErawanBillingManager? = null

    private fun updateConnectButton() {
        runOnMain {
            if (!isAdded) return@runOnMain
            val b = binding ?: return@runOnMain
            val context = context ?: return@runOnMain
            val tunnel = erawanTunnel

            val buttonTextRes: Int
            val statusTextRes: Int
            val colorRes: Int
            val busy: Boolean
            when (erawanPendingTarget) {
                Tunnel.State.UP -> {
                    buttonTextRes = R.string.connecting; statusTextRes = R.string.connecting
                    colorRes = R.color.connect_state_connecting; busy = true
                }
                Tunnel.State.DOWN -> {
                    buttonTextRes = R.string.disconnecting; statusTextRes = R.string.disconnecting
                    colorRes = R.color.connect_state_connecting; busy = true
                }
                else -> if (tunnel != null && tunnel.state == Tunnel.State.UP) {
                    buttonTextRes = R.string.disconnect_button; statusTextRes = R.string.connected
                    colorRes = R.color.connect_state_connected; busy = false
                } else {
                    buttonTextRes = R.string.connect_button; statusTextRes = R.string.disconnected
                    colorRes = R.color.connect_state_disconnected; busy = false
                }
            }

            val color = ContextCompat.getColor(context, colorRes)
            b.connectButton.isEnabled = !busy
            b.connectButton.setText(buttonTextRes)
            b.connectButton.backgroundTintList = ColorStateList.valueOf(color)
            b.connectProgress.visibility = if (busy) View.VISIBLE else View.GONE
            b.connectionStatusText.setText(statusTextRes)
            b.connectionStatusText.setTextColor(color)
            b.connectionStatusDot.imageTintList = ColorStateList.valueOf(color)

            val isUp = tunnel != null && tunnel.state == Tunnel.State.UP && erawanPendingTarget == null
            if (isUp) startSpeedPolling(tunnel!!) else stopSpeedPolling()
        }
    }

    private fun startSpeedPolling(tunnel: ObservableTunnel) {
        if (speedPollingJob?.isActive == true) return
        speedPollingJob = viewLifecycleOwner.lifecycleScope.launch {
            var prevRx = 0L
            var prevTx = 0L
            var prevTime = System.currentTimeMillis()
            while (tunnel.state == Tunnel.State.UP) {
                delay(1000L)
                val stats = try { tunnel.getStatisticsAsync() } catch (e: Exception) { break }
                val now = System.currentTimeMillis()
                val dt = ((now - prevTime) / 1000.0).coerceAtLeast(0.1)
                val downBps = (stats.totalRx() - prevRx) / dt
                val upBps   = (stats.totalTx() - prevTx) / dt
                prevRx = stats.totalRx()
                prevTx = stats.totalTx()
                prevTime = now
                val text = "↓ ${formatSpeed(downBps)}  ↑ ${formatSpeed(upBps)}"
                binding?.speedText?.text = text
                binding?.speedText?.visibility = View.VISIBLE
            }
            binding?.speedText?.visibility = View.GONE
        }
    }

    private fun stopSpeedPolling() {
        speedPollingJob?.cancel()
        speedPollingJob = null
        binding?.speedText?.visibility = View.GONE
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1_000_000.0 -> "%.1f MB/s".format(bytesPerSec / 1_000_000.0)
            bytesPerSec >= 1_000.0     -> "%.0f KB/s".format(bytesPerSec / 1_000.0)
            else                        -> "%.0f B/s".format(bytesPerSec)
        }
    }

    fun onServerPickerClicked() {
        if (childFragmentManager.findFragmentByTag("ERAWAN_SERVER_PICKER") != null) return
        ErawanServerPickerSheet().showNow(childFragmentManager, "ERAWAN_SERVER_PICKER")
    }

    private fun updateServerLabel() {
        runOnMain {
            if (!isAdded) return@runOnMain
            val name = erawanPrefs.selectedServerName
            val label = if (name != null) name else getString(R.string.erawan_server_auto_title)
            binding?.currentServerLabel?.text = getString(R.string.erawan_current_server_prefix, label)
        }
    }

    fun onUpgradeClicked() {
        launchUpgradeFlow()
    }

    private fun launchUpgradeFlow() {
        billingManager?.queryAndLaunch() ?: showSnackbar(getString(R.string.erawan_billing_unavailable))
    }

    private fun updateUpgradeBanner() {
        runOnMain {
            if (!isAdded) return@runOnMain
            val premium = erawanPrefs.isPremium()
            binding?.upgradeBanner?.isVisible = !premium
            binding?.premiumBadge?.isVisible = premium
        }
    }

    fun onConnectClicked() {
        val tunnel = erawanTunnel
        if (tunnel != null && tunnel.state == Tunnel.State.UP)
            disconnectErawanTunnel(tunnel)
        else
            startErawanConnect()
    }

    // Single tap, single coroutine: resolve config (only if needed) -> create/update the
    // tunnel -> request VPN permission if needed (awaited in-line, no separate callback
    // coroutine) -> setState(UP). Every step is awaited in order; nothing is deferred to
    // a "next tap".
    private fun startErawanConnect() {
        val activity = activity ?: return
        erawanPendingTarget = Tunnel.State.UP
        updateConnectButton()
        activity.lifecycleScope.launch {
            try {
                Log.d(TAG, "Erawan connect: resolving tunnel/config")
                val tunnels = Application.getTunnelManager().getTunnels()
                val existing = erawanTunnel ?: tunnels[ERAWAN_TUNNEL_NAME]
                val tunnel = if (existing != null && erawanPrefs.matchesCachedConfig(erawanPrefs.selectedServerId)) {
                    // Force the config into memory now (instead of relying on the lazy
                    // getter's fire-and-forget fetch) so setState below never races a load.
                    existing.getConfigAsync()
                    existing
                } else {
                    if (!erawanPrefs.isRegistered()) {
                        val (token, _) = ErawanApi.register(erawanPrefs.deviceId)
                        erawanPrefs.appToken = token
                    }
                    val result = ErawanApi.connect(erawanPrefs.appToken!!, erawanPrefs.selectedServerId)
                    val config = Config.parse(ByteArrayInputStream(result.config.toByteArray(StandardCharsets.UTF_8)))
                    erawanPrefs.rememberConfigServerId(erawanPrefs.selectedServerId)
                    if (existing != null) {
                        existing.setConfigAsync(config)
                        existing
                    } else {
                        Application.getTunnelManager().create(ERAWAN_TUNNEL_NAME, config)
                    }
                }
                attachErawanTunnel(tunnel)

                Log.d(TAG, "Erawan connect: requesting VPN permission if needed")
                requestVpnPermissionIfNeeded(activity)

                Log.d(TAG, "Erawan connect: setting state UP")
                tunnel.setStateAsync(Tunnel.State.UP)
                Log.d(TAG, "Erawan connect: state is now ${tunnel.state}")
                showSnackbar(getString(R.string.connected))
            } catch (e: Throwable) {
                Log.e(TAG, "Erawan connect failed", e)
                showSnackbar(erawanErrorMessage(e))
            } finally {
                erawanPendingTarget = null
                updateConnectButton()
            }
        }
    }

    private fun disconnectErawanTunnel(tunnel: ObservableTunnel) {
        val activity = activity ?: return
        erawanPendingTarget = Tunnel.State.DOWN
        updateConnectButton()
        activity.lifecycleScope.launch {
            try {
                Log.d(TAG, "Erawan disconnect: setting state DOWN")
                tunnel.setStateAsync(Tunnel.State.DOWN)
                Log.d(TAG, "Erawan disconnect: state is now ${tunnel.state}")
                showSnackbar(getString(R.string.disconnected))
            } catch (e: Throwable) {
                Log.e(TAG, "Erawan disconnect failed", e)
                showSnackbar(erawanErrorMessage(e))
            } finally {
                erawanPendingTarget = null
                updateConnectButton()
            }
        }
    }

    // Suspends in-line until the system VPN-permission dialog result comes back (no-op if
    // permission is already granted). Part of the same coroutine as the Connect tap that
    // called it, so the subsequent setState(UP) always runs in that same flow.
    private suspend fun requestVpnPermissionIfNeeded(activity: Activity) {
        if (Application.getBackend() !is GoBackend) return
        val intent = GoBackend.VpnService.prepare(activity) ?: return
        suspendCancellableCoroutine<Unit> { cont ->
            pendingPermissionContinuation = cont
            erawanPermissionResultLauncher.launch(intent)
        }
    }

    private fun erawanErrorMessage(e: Throwable): String {
        val resources = Application.get().resources
        if (e is ErawanApiException) {
            return when (e.reasonCode) {
                "missing_token", "invalid_token" -> resources.getString(R.string.error_invalid_token)
                "server_not_found" -> resources.getString(R.string.error_server_not_found)
                "servers_busy_try_again", "provision_failed", "server_not_available_for_tier" ->
                    resources.getString(R.string.error_servers_busy)
                else -> resources.getString(R.string.generic_error, e.reasonCode)
            }
        }
        if (e is IOException) return resources.getString(R.string.error_network)
        return ErrorMessages[e]
    }

    private fun viewForTunnel(tunnel: ObservableTunnel, tunnels: List<*>): MultiselectableRelativeLayout? {
        return binding?.tunnelList?.findViewHolderForAdapterPosition(tunnels.indexOf(tunnel))?.itemView as? MultiselectableRelativeLayout
    }

    private inner class ActionModeListener : ActionMode.Callback {
        val checkedItems: MutableCollection<Int> = HashSet()
        private var resources: Resources? = null

        fun getCheckedItems(): ArrayList<Int> {
            return ArrayList(checkedItems)
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.menu_action_delete -> {
                    val activity = activity ?: return true
                    val copyCheckedItems = HashSet(checkedItems)
                    binding?.createFab?.apply {
                        visibility = View.VISIBLE
                        scaleX = 1f
                        scaleY = 1f
                    }
                    activity.lifecycleScope.launch {
                        try {
                            val tunnels = visibleTunnels ?: return@launch
                            val tunnelsToDelete = ArrayList<ObservableTunnel>()
                            for (position in copyCheckedItems) tunnelsToDelete.add(tunnels[position])
                            val futures = tunnelsToDelete.map { async(SupervisorJob()) { it.deleteAsync() } }
                            onTunnelDeletionFinished(futures.awaitAll().size, null)
                        } catch (e: Throwable) {
                            onTunnelDeletionFinished(0, e)
                        }
                    }
                    checkedItems.clear()
                    mode.finish()
                    true
                }

                R.id.menu_action_select_all -> {
                    lifecycleScope.launch {
                        val tunnels = visibleTunnels ?: return@launch
                        for (i in 0 until tunnels.size) {
                            setItemChecked(i, true)
                        }
                    }
                    true
                }

                else -> false
            }
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            backPressedCallback?.isEnabled = true
            if (activity != null) {
                resources = activity!!.resources
            }
            animateFab(binding?.createFab, false)
            mode.menuInflater.inflate(R.menu.tunnel_list_action_mode, menu)
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            backPressedCallback?.isEnabled = false
            resources = null
            animateFab(binding?.createFab, true)
            checkedItems.clear()
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updateTitle(mode)
            return false
        }

        fun setItemChecked(position: Int, checked: Boolean) {
            if (checked) {
                checkedItems.add(position)
            } else {
                checkedItems.remove(position)
            }
            val adapter = if (binding == null) null else binding!!.tunnelList.adapter
            if (actionMode == null && !checkedItems.isEmpty() && activity != null) {
                (activity as AppCompatActivity).startSupportActionMode(this)
            } else if (actionMode != null && checkedItems.isEmpty()) {
                actionMode!!.finish()
            }
            adapter?.notifyItemChanged(position)
            updateTitle(actionMode)
        }

        fun toggleItemChecked(position: Int) {
            setItemChecked(position, !checkedItems.contains(position))
        }

        private fun updateTitle(mode: ActionMode?) {
            if (mode == null) {
                return
            }
            val count = checkedItems.size
            if (count == 0) {
                mode.title = ""
            } else {
                mode.title = resources!!.getQuantityString(R.plurals.delete_title, count, count)
            }
        }

        private fun animateFab(view: View?, show: Boolean) {
            view ?: return
            val animation = AnimationUtils.loadAnimation(
                context, if (show) R.anim.scale_up else R.anim.scale_down
            )
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {
                }

                override fun onAnimationEnd(animation: Animation?) {
                    if (!show) view.visibility = View.GONE
                }

                override fun onAnimationStart(animation: Animation?) {
                    if (show) view.visibility = View.VISIBLE
                }
            })
            view.startAnimation(animation)
        }
    }

    companion object {
        private const val CHECKED_ITEMS = "CHECKED_ITEMS"
        private const val TAG = "AmneziaWG/TunnelListFragment"
        private const val ERAWAN_TUNNEL_NAME = "Erawan"
    }
}
