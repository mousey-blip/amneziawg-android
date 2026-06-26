/*
 * One-time first-launch VPN disclosure screen.
 * Shown before any VPN permission request — Play Store transparency requirement.
 * Tracks display via ErawanPrefs.disclosureShown; shown exactly once.
 */
package org.amnezia.awg.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import org.amnezia.awg.R
import org.amnezia.awg.erawan.ErawanPrefs

class VpnDisclosureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind status/nav bars for full immersive dark look
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        setContentView(R.layout.activity_vpn_disclosure)
        supportActionBar?.hide()

        val prefs = ErawanPrefs(this)

        findViewById<android.view.View>(R.id.btn_continue).setOnClickListener {
            prefs.disclosureShown = true
            finish()
        }

        findViewById<android.view.View>(R.id.btn_privacy_policy).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.disclosure_privacy_url)))
            )
        }
    }

    // Pressing back exits the app — user must tap Continue to proceed.
    // On next launch the disclosure appears again until they tap Continue.
}
