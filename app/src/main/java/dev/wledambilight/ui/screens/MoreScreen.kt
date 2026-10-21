package dev.wledambilight.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wledambilight.R
import dev.wledambilight.ui.SubRoute
import dev.wledambilight.ui.components.ListRow
import dev.wledambilight.ui.components.ScreenHeader
import dev.wledambilight.ui.theme.Mint
import dev.wledambilight.ui.theme.Sky

@Composable
fun MoreScreen(onOpen: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(title = stringResource(R.string.more_title))
        ListRow(
            icon = Icons.Rounded.SettingsRemote,
            title = stringResource(R.string.ctrl_title),
            subtitle = stringResource(R.string.more_controller_desc),
            onClick = { onOpen(SubRoute.CONTROLLER) },
        )
        ListRow(
            icon = Icons.Rounded.MonitorHeart,
            title = stringResource(R.string.diagnostics_title),
            subtitle = stringResource(R.string.more_diagnostics_desc),
            tint = Mint,
            onClick = { onOpen(SubRoute.DIAGNOSTICS) },
        )
        ListRow(
            icon = Icons.Rounded.Settings,
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.more_settings_desc),
            tint = Sky,
            onClick = { onOpen(SubRoute.SETTINGS) },
        )
    }
}
