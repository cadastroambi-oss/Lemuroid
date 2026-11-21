package com.swordfish.lemuroid.app.tv.game

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelPhoneController

@Composable
fun TVPhoneControllerOverlay(state: GameViewModelPhoneController.UiState) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.qrVisible && state.url != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        ) {
            QrCard(state)
        }

        AnimatedVisibility(
            visible = state.message != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
        ) {
            Text(
                text = state.message ?: "",
                color = Color.White,
                fontSize = 16.sp,
                modifier =
                    Modifier
                        .background(Color(0xCC1B1B22), RoundedCornerShape(50))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun QrCard(state: GameViewModelPhoneController.UiState) {
    val url = state.url ?: return
    val qr = remember(url) { createQrBitmap(url) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .width(220.dp)
                .background(Color(0xE61B1B22), RoundedCornerShape(16.dp))
                .padding(16.dp),
    ) {
        Text(
            text = "Jogar pelo celular",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        if (qr != null) {
            Image(
                bitmap = qr,
                contentDescription = url,
                filterQuality = FilterQuality.None,
                modifier =
                    Modifier
                        .size(170.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(8.dp),
            )
        }
        Text(
            text = "Escaneie com a câmera (mesmo Wi-Fi)",
            color = Color(0xFFC9C6D6),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = url.removePrefix("http://").removeSuffix("/"),
            color = Color(0xFF8F8CA3),
            fontSize = 11.sp,
        )
        if (state.connectedPlayers.isNotEmpty()) {
            Text(
                text = "Conectados: " + state.connectedPlayers.joinToString { "J$it" },
                color = Color(0xFF8CE99A),
                fontSize = 12.sp,
            )
        }
    }
}

private fun createQrBitmap(text: String): ImageBitmap? {
    return runCatching {
        val matrix =
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.MARGIN to 0),
            )
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
}
