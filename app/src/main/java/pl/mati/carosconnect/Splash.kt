package pl.mati.carosconnect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Animacja startowa – pokazywana raz na uruchomienie procesu. */
object SplashState { var shown = false }

@Composable
fun SplashOverlay() {
    var visible by remember { mutableStateOf(!SplashState.shown) }
    if (!visible) return
    val fade = remember { Animatable(1f) }
    val logo = remember { Animatable(0.6f) }
    val title = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { logo.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        delay(450)
        title.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        delay(700)
        launch { logo.animateTo(1.15f, tween(350)) }
        fade.animateTo(0f, tween(350))
        SplashState.shown = true
        visible = false
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(C.Bg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.graphicsLayer { scaleX = logo.value; scaleY = logo.value; alpha = ((logo.value - 0.6f) / 0.4f).coerceIn(0f, 1f) }) {
                AnimatedLogo(140.dp)
            }
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier.graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 40f },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("CAR OS", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text, letterSpacing = 4.sp)
                Text("CONNECT", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = C.Accent, letterSpacing = 6.sp)
            }
        }
    }
}
