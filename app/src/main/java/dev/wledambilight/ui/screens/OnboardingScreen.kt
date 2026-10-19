package dev.wledambilight.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wledambilight.R
import dev.wledambilight.ui.components.SectionCard
import dev.wledambilight.ui.components.tvFocusRing
import dev.wledambilight.ui.theme.Amber
import dev.wledambilight.ui.theme.TextSecondary

@Composable
fun OnboardingScreen(onFinish: () -> Unit, onOpenDevices: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    val primaryFocus = FocusRequester()

    LaunchedEffect(step) {
        runCatching { primaryFocus.requestFocus() }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.onboarding_step, step + 1, 3),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { index ->
                        Box(
                            Modifier
                                .size(if (index == step) 22.dp else 8.dp, 8.dp)
                                .background(
                                    if (index == step) Amber else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (step) {
                    0 -> WelcomeStep()
                    1 -> PermissionStep()
                    else -> SetupStep()
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    TextButton(onClick = { step-- }, modifier = Modifier.tvFocusRing(RoundedCornerShape(12.dp))) {
                        Text(stringResource(R.string.action_back))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (step < 2) {
                    Button(
                        onClick = { step++ },
                        modifier = Modifier
                            .focusRequester(primaryFocus)
                            .tvFocusRing(),
                    ) {
                        Text(stringResource(R.string.action_continue))
                    }
                } else {
                    TextButton(onClick = onFinish) {
                        Text(stringResource(R.string.action_skip))
                    }
                    Button(
                        onClick = onOpenDevices,
                        modifier = Modifier
                            .focusRequester(primaryFocus)
                            .tvFocusRing(),
                    ) {
                        Text(stringResource(R.string.onboarding_open_devices))
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Text(
        text = stringResource(R.string.onboarding_title),
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = stringResource(R.string.onboarding_subtitle),
        style = MaterialTheme.typography.bodyLarge,
        color = TextSecondary,
    )
    val points = listOf(
        R.string.onboarding_point_1,
        R.string.onboarding_point_2,
        R.string.onboarding_point_3,
        R.string.onboarding_point_4,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        points.forEach { point ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(stringResource(point), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PermissionStep() {
    Text(
        text = stringResource(R.string.onboarding_permission_title),
        style = MaterialTheme.typography.headlineMedium,
    )
    Text(
        text = stringResource(R.string.onboarding_permission_body),
        style = MaterialTheme.typography.bodyLarge,
        color = TextSecondary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Text(
            text = stringResource(R.string.settings_privacy_body),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
    SectionCard(title = stringResource(R.string.onboarding_drm_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Text(
                text = stringResource(R.string.onboarding_drm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun SetupStep() {
    Text(
        text = stringResource(R.string.onboarding_setup_title),
        style = MaterialTheme.typography.headlineMedium,
    )
    Text(
        text = stringResource(R.string.onboarding_setup_body),
        style = MaterialTheme.typography.bodyLarge,
        color = TextSecondary,
    )
    SectionCard(title = stringResource(R.string.devices_title)) {
        Text(
            text = stringResource(R.string.devices_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
    Text(
        text = stringResource(R.string.onboarding_permission_body),
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
    )
}
