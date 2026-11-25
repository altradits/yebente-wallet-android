package com.yebente.wallet.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.yebente.wallet.ui.components.NeutralButton
import com.yebente.wallet.ui.components.Note
import com.yebente.wallet.ui.components.StanHeader
import com.yebente.wallet.ui.theme.Mono
import com.yebente.wallet.ui.theme.ObsidianPlum
import com.yebente.wallet.ui.theme.Sans
import com.yebente.wallet.util.ScanPurposes

@Composable
fun ScannerScreen(purpose: String, onCode: (String) -> Unit, onBack: () -> Unit) {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current
  var granted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  var manual by remember { mutableStateOf("") }
  val frames = remember { mutableStateListOf<String>() }
  var frameTotal by remember { mutableStateOf<Int?>(null) }
  var finished by remember { mutableStateOf(false) }
  val onCodeRef by rememberUpdatedState(onCode)

  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted = it }

  LaunchedEffect(Unit) {
    if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
  }

  fun handleBarcode(raw: String) {
    if (finished) return
    val clean = raw.trim()
    if (clean.isEmpty()) return
    if (purpose == ScanPurposes.PROMISE && clean.startsWith("YB1:")) {
      val numbers = clean.split(":")
      if (numbers.size < 4) return
      val total = numbers[1].toIntOrNull() ?: return
      numbers[2].toIntOrNull() ?: return
      if (clean !in frames) frames.add(clean)
      frameTotal = total
      if (frames.size >= total) {
        finished = true
        val ordered = frames.sortedBy { it.split(":")[2].toIntOrNull() ?: 0 }
        onCodeRef(ordered.joinToString("\n"))
      }
      return
    }
    finished = true
    onCodeRef(clean)
  }

  val handleRef by rememberUpdatedState(::handleBarcode)

  Column(
    modifier = Modifier.fillMaxSize().padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StanHeader(title = "Scan Code", onBack = onBack)

    if (granted) {
      AndroidView(
        factory = { ctx ->
          PreviewView(ctx).also { previewView ->
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
              val provider = future.get()
              val preview = Preview.Builder().build()
              preview.setSurfaceProvider(previewView.surfaceProvider)
              val scanner = BarcodeScanning.getClient()
              val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
              analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                val media = proxy.image
                if (media == null) {
                  proxy.close()
                  return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                scanner.process(image)
                  .addOnSuccessListener { barcodes ->
                    barcodes.firstNotNullOfOrNull { it.rawValue }?.let { handleRef(it) }
                  }
                  .addOnCompleteListener { proxy.close() }
              }
              provider.unbindAll()
              provider.bindToLifecycle(
                lifecycle,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
              )
            }, ContextCompat.getMainExecutor(ctx))
          }
        },
        modifier = Modifier.fillMaxWidth().weight(1f),
      )
      val total = frameTotal
      if (total != null && total > 1) {
        Text(
          text = "Part ${frames.size} of $total. Keep scanning.",
          fontFamily = Mono,
          fontSize = 12.sp,
          color = ObsidianPlum.TextSecondary,
        )
      }
    } else {
      Note(text = "Camera permission is needed to scan. You can paste a code below instead.", error = true)
    }

    Spacer(modifier = Modifier.height(4.dp))
    TextField(
      value = manual,
      onValueChange = { manual = it },
      label = { Text("Paste a code instead", fontFamily = Sans) },
      modifier = Modifier.fillMaxWidth(),
      colors = TextFieldDefaults.colors(
        focusedContainerColor = ObsidianPlum.BgInput,
        unfocusedContainerColor = ObsidianPlum.BgInput,
        focusedTextColor = ObsidianPlum.TextPrimary,
        unfocusedTextColor = ObsidianPlum.TextPrimary,
      ),
    )
    NeutralButton(
      label = "Use Pasted Code",
      onClick = { if (manual.isNotBlank() && !finished) { finished = true; onCode(manual.trim()) } },
    )
  }
}
