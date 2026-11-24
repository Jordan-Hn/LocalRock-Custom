package com.kodraliu.localrock.ui.intro

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.kodraliu.localrock.resources.Res
import com.kodraliu.localrock.resources.app_icon
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource


/** A brief, quiet launch: the icon settles into the middle of a plain page, the name sits at the foot. */
@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(260), label = "splashFade")
    val scale by animateFloatAsState(if (shown) 1f else 0.92f, tween(320), label = "splashScale")

    LaunchedEffect(Unit) {
        shown = true
        delay(700)
        onTimeout()
    }
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) {
        Image(
            painter = painterResource(Res.drawable.app_icon),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .size(96.dp)
                .scale(scale)
                .alpha(alpha)
                .clip(RoundedCornerShape(26.dp)),
        )
        Text(
            "LocalRock",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
                .alpha(alpha),
        )
    }
}
