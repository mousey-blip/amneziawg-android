/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.activity

import android.content.Intent
import android.view.View
import android.widget.Toast
import com.google.zxing.client.android.Intents
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.amnezia.awg.R
import org.amnezia.awg.util.QrCodeFromFileScanner

/**
 * The same camera QR scanner zxing-android-embedded provides (via [CaptureActivity]), with one
 * addition: a gallery icon overlaid in the bottom corner so a user mid-scan can instead pick an
 * existing photo of a QR code. [CaptureActivity.onCreate] drives the whole camera/permission
 * lifecycle unchanged — this subclass only swaps the content view and handles the gallery pick.
 *
 * [CaptureActivity] is a plain [android.app.Activity], not a [androidx.activity.ComponentActivity],
 * so the modern Activity Result API isn't available here; this uses the classic
 * startActivityForResult/onActivityResult pair instead (ACTION_GET_CONTENT needs no permission
 * on any Android version, same as the modern GetContent contract).
 */
class ErawanQrScannerActivity : CaptureActivity() {
    private val activityScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.erawan_qr_scanner_activity)
        findViewById<View>(R.id.qr_gallery_button).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), GALLERY_REQUEST_CODE)
        }
        return findViewById(R.id.zxing_barcode_scanner)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != GALLERY_REQUEST_CODE) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        activityScope.launch {
            try {
                val result = QrCodeFromFileScanner(contentResolver, QRCodeReader()).scan(uri)
                // Mirrors the extra a successful camera scan returns (Intents.Scan.RESULT ==
                // "SCAN_RESULT"), so ScanContract/ScanIntentResult on the caller side parses a
                // gallery-decoded QR identically to one read by the camera.
                setResult(RESULT_OK, Intent().putExtra(Intents.Scan.RESULT, result.text))
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@ErawanQrScannerActivity, getString(R.string.error_no_qr_found), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val GALLERY_REQUEST_CODE = 5821
    }
}
