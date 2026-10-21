package dev.wledambilight.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wledambilight.BuildConfig
import dev.wledambilight.R
import dev.wledambilight.core.model.AppTheme
import dev.wledambilight.ui.AppViewModel
import dev.wledambilight.ui.components.ChoiceChips
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.wledambilight.ui.components.ScreenHeader
import dev.wledambilight.ui.components.SectionCard
import dev.wledambilight.ui.components.tvFocusRing
import dev.wledambilight.ui.theme.TextSecondary

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var resetDialog by remember { mutableStateOf(false) }
    var licensesDialog by remember { mutableStateOf(false) }
    var profileMessage by remember { mutableStateOf<Int?>(null) }
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(title = stringResource(R.string.settings_title))

        SectionCard(title = stringResource(R.string.settings_profile), subtitle = stringResource(R.string.settings_profile_body)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(viewModel.exportProfile()))
                        profileMessage = R.string.settings_profile_copied
                    },
                    modifier = Modifier.tvFocusRing(),
                ) { Text(stringResource(R.string.settings_profile_copy)) }
                OutlinedButton(
                    onClick = {
                        val text = clipboard.getText()?.text.orEmpty()
                        profileMessage = if (viewModel.importProfile(text)) {
                            R.string.settings_profile_imported
                        } else {
                            R.string.settings_profile_invalid
                        }
                    },
                    modifier = Modifier.tvFocusRing(),
                ) { Text(stringResource(R.string.settings_profile_paste)) }
            }
            profileMessage?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }

        SectionCard(title = stringResource(R.string.settings_appearance)) {
            Text(
                stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.bodyLarge,
            )
            ChoiceChips(
                options = AppTheme.entries,
                selected = config.settings.theme,
                label = {
                    stringResource(
                        if (it == AppTheme.BLACK) R.string.theme_black else R.string.theme_dark,
                    )
                },
                onSelected = { theme ->
                    viewModel.updateSettings { it.copy(theme = theme) }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_language_system), color = TextSecondary)
            }
            Text(
                stringResource(R.string.settings_language_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        SectionCard(title = stringResource(R.string.settings_privacy)) {
            Text(
                stringResource(R.string.settings_privacy_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Text(
                stringResource(R.string.settings_diagnostics_note),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            OutlinedButton(onClick = { licensesDialog = true }, modifier = Modifier.tvFocusRing()) {
                Text(stringResource(R.string.settings_licenses))
            }
        }

        SectionCard(title = stringResource(R.string.settings_protocols)) {
            Text(
                stringResource(R.string.settings_protocols_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        SectionCard(title = stringResource(R.string.settings_limitations)) {
            Text(
                stringResource(R.string.settings_limitations_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        SectionCard(title = stringResource(R.string.settings_docs)) {
            Text(
                stringResource(R.string.settings_docs_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        SectionCard(title = stringResource(R.string.settings_reset)) {
            Text(
                stringResource(R.string.settings_reset_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            OutlinedButton(onClick = { resetDialog = true }, modifier = Modifier.tvFocusRing()) {
                Text(stringResource(R.string.settings_reset), color = MaterialTheme.colorScheme.error)
            }
        }

        SectionCard(title = stringResource(R.string.settings_about)) {
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.settings_build, BuildConfig.BUILD_TYPE),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }

    if (resetDialog) {
        AlertDialog(
            onDismissRequest = { resetDialog = false },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    resetDialog = false
                    viewModel.resetAppData()
                }) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { resetDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (licensesDialog) {
        AlertDialog(
            onDismissRequest = { licensesDialog = false },
            title = { Text(stringResource(R.string.settings_licenses)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(LICENSES, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = { licensesDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

private val LICENSES = """
AndroidX, Jetpack Compose, Lifecycle, Navigation, DataStore — Apache License 2.0
Kotlin, kotlinx.coroutines, kotlinx.serialization — Apache License 2.0
OkHttp — Apache License 2.0
Material Icons — Apache License 2.0
""".trimIndent()
