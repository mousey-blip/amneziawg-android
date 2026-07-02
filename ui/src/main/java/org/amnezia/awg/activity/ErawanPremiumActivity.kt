/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.activity

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.amnezia.awg.R
import org.amnezia.awg.databinding.ActivityErawanPremiumBinding
import org.amnezia.awg.databinding.ItemPremiumFeatureRowBinding
import org.amnezia.awg.erawan.ErawanBillingManager
import org.amnezia.awg.erawan.ErawanPrefs
import org.amnezia.awg.fragment.RedeemKeyDialogFragment

/**
 * Premium comparison page — dark navy + gold luxury design.
 *
 * Feature rows are driven by [FEATURES] in [populateFeatureRows]: edit that list to add/
 * remove rows without touching the layout XML. Server counts are NOT hardcoded; wording
 * uses "Premium servers & locations" so the claim stays valid as new servers are added.
 */
class ErawanPremiumActivity : AppCompatActivity() {

    private lateinit var binding: ActivityErawanPremiumBinding
    private var billingManager: ErawanBillingManager? = null

    // ─── Data-driven feature list ──────────────────────────────────────────────
    // Add or remove rows here. Names are resolved at runtime from string resources.
    // freeHas=true  → free tier gets this feature (shows ✓ in Free column)
    // freeHas=false → premium-exclusive (shows — in Free column)
    private data class FeatureRow(val nameRes: Int, val freeHas: Boolean)

    private val features = listOf(
        FeatureRow(R.string.premium_feature_one_tap,      freeHas = true),
        FeatureRow(R.string.premium_feature_amneziawg,    freeHas = true),
        FeatureRow(R.string.premium_feature_speed,        freeHas = true),
        FeatureRow(R.string.premium_feature_nolog,        freeHas = true),
        FeatureRow(R.string.premium_feature_free_servers, freeHas = true),
        FeatureRow(R.string.premium_feature_more_servers, freeHas = false),
        FeatureRow(R.string.premium_feature_vless,        freeHas = false),
        FeatureRow(R.string.premium_feature_priority,     freeHas = false),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityErawanPremiumBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = ErawanPrefs(this)
        billingManager = ErawanBillingManager(
            activity = this,
            prefs = prefs,
            onTierUpdated = { tier ->
                if (tier == "paid" || tier == "vip") {
                    Toast.makeText(this, getString(R.string.erawan_billing_success), Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish()
                }
            },
            onError = { msgRes ->
                Toast.makeText(this, getString(msgRes), Toast.LENGTH_SHORT).show()
            }
        )

        binding.btnBack.setOnClickListener { finish() }
        binding.btnGetPremium.setOnClickListener { billingManager?.queryAndLaunch() }
        binding.btnHaveKey.setOnClickListener {
            RedeemKeyDialogFragment.newInstance(fromPremiumPage = true)
                .show(supportFragmentManager, "redeem_key")
        }
        binding.tvPrivacy.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.disclosure_privacy_url))))
        }
        binding.tvTerms.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.erawan_terms_url))))
        }

        populateFeatureRows()
    }

    private fun populateFeatureRows() {
        val container: LinearLayout = binding.featureRowsContainer
        val inflater = LayoutInflater.from(this)
        val goldColor  = getColor(R.color.erawan_gold_light)
        val grayCheck  = Color.parseColor("#CCCCCC")
        val grayDash   = Color.parseColor("#666666")
        val dividerColor = Color.parseColor("#22FFFFFF")

        features.forEachIndexed { index, row ->
            val rowBinding = ItemPremiumFeatureRowBinding.inflate(inflater, container, false)
            rowBinding.tvFeatureName.text = getString(row.nameRes)

            if (row.freeHas) {
                rowBinding.tvFreeCheck.text = "✓"   // ✓
                rowBinding.tvFreeCheck.setTextColor(grayCheck)
            } else {
                rowBinding.tvFreeCheck.text = "—"   // —
                rowBinding.tvFreeCheck.setTextColor(grayDash)
            }

            // Premium always gets every feature in this list
            rowBinding.tvPremiumCheck.text = "✓"    // ✓
            rowBinding.tvPremiumCheck.setTextColor(goldColor)

            container.addView(rowBinding.root)

            if (index < features.size - 1) {
                val divider = View(this)
                divider.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                divider.setBackgroundColor(dividerColor)
                container.addView(divider)
            }
        }
    }

    override fun onDestroy() {
        billingManager?.disconnect()
        super.onDestroy()
    }
}
