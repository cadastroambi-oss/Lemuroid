package com.swordfish.lemuroid.app.tv.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.app.shared.phonecontroller.PhoneControllerFiles

class TVPhoneHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneHub.ensureStarted(this)
        val reason = intent.getStringExtra(EXTRA_REASON)

        setContent {
            val state by PhoneHub.getState().collectAsState()
            HubScreen(state, reason)
        }
    }

    override fun onResume() {
        super.onResume()
        PhoneHub.refreshUrl()
    }

    companion object {
        private const val EXTRA_REASON = "EXTRA_REASON"

        fun launch(
            context: Context,
            reason: String? = null,
        ) {
            val intent =
                Intent(context, TVPhoneHubActivity::class.java).apply {
                    reason?.let { putExtra(EXTRA_REASON, it) }
                    if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
        }
    }
}

@Composable
private fun HubScreen(
    state: PhoneHub.State,
    reason: String?,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF15151B)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(48.dp),
        ) {
            val url = state.url
            val qr = remember(url) { url?.let { PhoneControllerFiles.createQrBitmap(it) } }

            Box(
                modifier =
                    Modifier
                        .size(300.dp)
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = url,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        "Sem rede.\nConecte a TV ao Wi-Fi.",
                        color = Color.Black,
                        fontSize = 18.sp,
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.width(460.dp),
            ) {
                Text("Usar o celular", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)

                if (reason != null) {
                    Text(reason, color = Color(0xFFFFC078), fontSize = 16.sp)
                }

                Step("1", "Deixe o celular no mesmo Wi-Fi da TV.")
                Step("2", "Aponte a câmera do celular para o QR code.")
                Step("3", "Na aba \"Jogos\", envie os arquivos dos jogos para a TV. Eles aparecem aqui sozinhos.")
                Step("4", "Na aba \"Controle\", use o celular para andar pelos menus. Ao abrir um jogo, ele vira o controle do jogo.")

                url?.let {
                    Text(
                        "Ou digite no navegador: ${it.removePrefix("http://").removeSuffix("/")}",
                        color = Color(0xFF8F8CA3),
                        fontSize = 14.sp,
                    )
                }
                if (state.connectedPhones > 0) {
                    Text(
                        "Celulares conectados: ${state.connectedPhones}",
                        color = Color(0xFF8CE99A),
                        fontSize = 16.sp,
                    )
                }
                state.lastMessage?.let {
                    Text(it, color = Color(0xFF8CE99A), fontSize = 16.sp)
                }
                Text("Aperte Voltar no controle remoto para sair.", color = Color(0xFF6F6C82), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Step(
    number: String,
    text: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(28.dp).background(Color(0xFF3B5BDB), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = Color(0xFFE8E6F0), fontSize = 18.sp)
    }
}
