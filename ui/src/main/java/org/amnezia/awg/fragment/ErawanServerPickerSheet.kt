/*
 * Erawan server picker bottom sheet: "Auto (Fastest)" plus the tier-filtered
 * list returned by /app/servers. Selection is persisted to ErawanPrefs and
 * picked up by /app/connect on the next Connect tap.
 */
package org.amnezia.awg.fragment

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import org.amnezia.awg.R
import org.amnezia.awg.erawan.ErawanApi
import org.amnezia.awg.erawan.ErawanPrefs
import org.amnezia.awg.erawan.ErawanServer
import org.amnezia.awg.util.resolveAttribute

class ErawanServerPickerSheet : BottomSheetDialogFragment() {

    private var behavior: BottomSheetBehavior<*>? = null
    private val bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        override fun onStateChanged(bottomSheet: View, newState: Int) {
            if (newState == BottomSheetBehavior.STATE_COLLAPSED) dismiss()
        }
    }

    private val erawanPrefs by lazy { ErawanPrefs(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        if (savedInstanceState != null) dismiss()
        return inflater.inflate(R.layout.erawan_server_picker_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val dialog = dialog as? BottomSheetDialog ?: return
                behavior = dialog.behavior
                behavior?.apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    peekHeight = 0
                    addBottomSheetCallback(bottomSheetCallback)
                }
            }
        })
        view.background = GradientDrawable().apply {
            setColor(requireContext().resolveAttribute(com.google.android.material.R.attr.colorSurface))
        }

        view.findViewById<View>(R.id.erawan_auto_option).setOnClickListener { selectAuto() }

        loadServers(view)
    }

    override fun dismiss() {
        super.dismiss()
        behavior?.removeBottomSheetCallback(bottomSheetCallback)
    }

    private fun loadServers(view: View) {
        val loading = view.findViewById<ProgressBar>(R.id.servers_loading)
        val scroll = view.findViewById<View>(R.id.servers_scroll)
        val errorText = view.findViewById<TextView>(R.id.servers_error)
        val container = view.findViewById<LinearLayout>(R.id.servers_container)

        loading.isVisible = true
        scroll.isVisible = false
        errorText.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!erawanPrefs.isRegistered()) {
                    val (token, _) = ErawanApi.register(erawanPrefs.deviceId)
                    erawanPrefs.appToken = token
                }
                val servers = ErawanApi.servers(erawanPrefs.appToken!!)
                loading.isVisible = false
                if (servers.isEmpty()) {
                    errorText.isVisible = true
                    errorText.setText(R.string.erawan_servers_empty)
                    Toast.makeText(requireContext(), R.string.erawan_servers_empty, Toast.LENGTH_SHORT).show()
                } else {
                    scroll.isVisible = true
                    populateServers(container, servers)
                }
            } catch (e: Throwable) {
                loading.isVisible = false
                errorText.isVisible = true
                errorText.setText(R.string.erawan_servers_error)
                Toast.makeText(requireContext(), R.string.erawan_servers_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun populateServers(container: LinearLayout, servers: List<ErawanServer>) {
        val inflater = LayoutInflater.from(requireContext())
        container.removeAllViews()
        for (server in servers) {
            val row = inflater.inflate(R.layout.erawan_server_row, container, false)
            row.findViewById<TextView>(R.id.server_name).text = server.name
            row.findViewById<TextView>(R.id.server_country).text = server.country
            row.findViewById<TextView>(R.id.server_load).apply {
                text = getString(R.string.erawan_server_load_format, server.currentLoad)
                setTextColor(loadColor(server.currentLoad))
            }
            row.setOnClickListener { selectServer(server) }
            container.addView(row)
        }
    }

    private fun loadColor(load: Int): Int {
        val colorRes = when {
            load < 40 -> R.color.erawan_load_low
            load < 75 -> R.color.erawan_load_medium
            else -> R.color.erawan_load_high
        }
        return ContextCompat.getColor(requireContext(), colorRes)
    }

    private fun selectAuto() {
        erawanPrefs.setSelectedServer(null, null)
        setFragmentResult(REQUEST_KEY_SERVER_SELECTED, Bundle.EMPTY)
        dismiss()
    }

    private fun selectServer(server: ErawanServer) {
        erawanPrefs.setSelectedServer(server.id, server.name)
        setFragmentResult(REQUEST_KEY_SERVER_SELECTED, Bundle.EMPTY)
        dismiss()
    }

    companion object {
        const val REQUEST_KEY_SERVER_SELECTED = "request_server_selected"
    }
}
