package org.amnezia.awg.fragment

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.amnezia.awg.R
import org.amnezia.awg.erawan.ErawanApi
import org.amnezia.awg.erawan.ErawanApiException
import org.amnezia.awg.erawan.ErawanPrefs
import org.amnezia.awg.databinding.DialogRedeemKeyBinding
import java.text.SimpleDateFormat
import java.util.Locale

class RedeemKeyDialogFragment : DialogFragment() {

    private var _binding: DialogRedeemKeyBinding? = null
    private val binding get() = _binding!!
    private lateinit var erawanPrefs: ErawanPrefs

    companion object {
        private const val ARG_FROM_PREMIUM = "from_premium"
        const val RESULT_KEY = "key_redeemed"

        fun newInstance(fromPremiumPage: Boolean = false) = RedeemKeyDialogFragment().apply {
            arguments = bundleOf(ARG_FROM_PREMIUM to fromPremiumPage)
        }
    }

    private val fromPremiumPage: Boolean
        get() = arguments?.getBoolean(ARG_FROM_PREMIUM) ?: false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogRedeemKeyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        erawanPrefs = ErawanPrefs(requireContext())

        // Auto-uppercase + ERWN-XXXX-XXXX-XXXX formatting
        binding.etPremiumKey.filters = arrayOf(InputFilter.LengthFilter(19))
        binding.etPremiumKey.addTextChangedListener(KeyFormatter(binding.etPremiumKey))
        binding.etPremiumKey.requestFocus()

        binding.btnCancelRedeem.setOnClickListener { dismiss() }
        binding.btnRedeem.setOnClickListener {
            val raw = binding.etPremiumKey.text.toString().trim()
            attemptRedeem(raw)
        }
    }

    private fun attemptRedeem(key: String) {
        if (key.length < 19) {
            showError(getString(R.string.redeem_key_error_invalid))
            return
        }

        setLoading(true)
        binding.tvRedeemError.isVisible = false
        binding.tvRedeemSuccess.isVisible = false

        lifecycleScope.launch {
            try {
                if (!erawanPrefs.isRegistered()) {
                    val (token, _) = ErawanApi.register(erawanPrefs.deviceId)
                    erawanPrefs.appToken = token
                }
                val result = ErawanApi.redeemKey(erawanPrefs.appToken!!, key)

                // Persist premium tier locally — same as billing flow
                erawanPrefs.tier = result.tier
                erawanPrefs.premiumExpiresAt = result.expireDate

                val formatted = formatDate(result.expireDate)
                showSuccess(getString(R.string.redeem_key_success, formatted, result.daysRemaining))

                // Notify parent after a short display pause
                view?.postDelayed({
                    parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle.EMPTY)
                    if (fromPremiumPage) {
                        activity?.setResult(Activity.RESULT_OK)
                        activity?.finish()
                    } else {
                        dismiss()
                    }
                }, 1600)

            } catch (e: ErawanApiException) {
                setLoading(false)
                showError(when (e.reasonCode) {
                    "key_already_used" -> getString(R.string.redeem_key_error_used)
                    "invalid_key"      -> getString(R.string.redeem_key_error_invalid)
                    else               -> getString(R.string.redeem_key_error_invalid)
                })
            } catch (_: Exception) {
                setLoading(false)
                showError(getString(R.string.redeem_key_error_network))
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnRedeem.isEnabled = !loading
        binding.btnCancelRedeem.isEnabled = !loading
        binding.etPremiumKey.isEnabled = !loading
        binding.btnRedeem.alpha = if (loading) 0.6f else 1f
    }

    private fun showError(msg: String) {
        binding.tvRedeemError.text = msg
        binding.tvRedeemError.isVisible = true
        binding.tvRedeemSuccess.isVisible = false
    }

    private fun showSuccess(msg: String) {
        setLoading(false)
        binding.tvRedeemSuccess.text = msg
        binding.tvRedeemSuccess.isVisible = true
        binding.tvRedeemError.isVisible = false
    }

    private fun formatDate(iso: String): String = try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = sdf.parse(iso.substringBefore('.'))
        if (date != null) SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
        else iso.substringBefore('T')
    } catch (_: Exception) { iso.substringBefore('T') }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // Auto-formats input as ERWN-XXXX-XXXX-XXXX while the user types or pastes.
    private class KeyFormatter(private val et: android.widget.EditText) : TextWatcher {
        private var selfChange = false
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) {
            if (selfChange) return
            selfChange = true
            val raw = s.filter { it.isLetterOrDigit() }.toString().uppercase().take(16)
            val formatted = buildString {
                raw.forEachIndexed { i, c ->
                    if (i == 4 || i == 8 || i == 12) append('-')
                    append(c)
                }
            }
            s.replace(0, s.length, formatted)
            selfChange = false
        }
    }
}
