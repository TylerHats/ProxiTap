package com.tylerhats.proxitap.ui.screens

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException

fun generateQrCode(text: String, size: Int): Bitmap? {
    return try {
        val bitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap
    } catch (e: WriterException) {
        e.printStackTrace()
        null
    }
}

@Composable
fun LobbyScreen(
    qrPayload: String,
    buttonText: String = "Start Call",
    qrBitmap: Bitmap? = null,
    onStartCallClick: () -> Unit
) {
    var localQrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val displayBitmap = qrBitmap ?: localQrBitmap
    
    if (qrBitmap == null) {
        LaunchedEffect(qrPayload) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                localQrBitmap = generateQrCode(qrPayload, 300)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Lobby Started", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        
        if (displayBitmap != null) {
            Image(
                bitmap = displayBitmap.asImageBitmap(),
                contentDescription = "QR Code for Lobby",
                modifier = Modifier
                    .size(250.dp)
                    .background(Color.White)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Have your friends scan this QR code to join the lobby.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val context = androidx.compose.ui.platform.LocalContext.current
            OutlinedButton(
                onClick = {
                    val sendIntent = android.content.Intent().apply {
                        action = android.content.Intent.ACTION_SEND
                        putExtra(android.content.Intent.EXTRA_TEXT, qrPayload)
                        type = "text/plain"
                    }
                    val shareIntent = android.content.Intent.createChooser(sendIntent, null)
                    context.startActivity(shareIntent)
                },
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Text("Share URL")
            }

            Button(
                onClick = onStartCallClick,
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Text(buttonText)
            }
        }
    }
}
