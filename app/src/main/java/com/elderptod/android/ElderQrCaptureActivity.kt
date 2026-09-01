package com.elderptod.android

import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size

class ElderQrCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scanner = findViewById<DecoratedBarcodeView>(
            com.google.zxing.client.android.R.id.zxing_barcode_scanner,
        )
        val frameSize = (resources.displayMetrics.widthPixels * 0.62f).toInt()
        scanner.barcodeView.setFramingRectSize(Size(frameSize, frameSize))
        scanner.setStatusText("請把 QR code 放在框內")
    }
}
