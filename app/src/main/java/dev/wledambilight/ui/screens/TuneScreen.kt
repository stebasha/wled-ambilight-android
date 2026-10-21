package dev.wledambilight.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wledambilight.R
import dev.wledambilight.ui.AppViewModel
import dev.wledambilight.ui.components.ScreenHeader
import dev.wledambilight.ui.components.tvFocusRing

/** Layout (calibration) and colour processing share one tab to keep the navigation at five items. */
@Composable
fun TuneScreen(viewModel: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(R.string.tune_layout, R.string.tune_color)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            ScreenHeader(title = stringResource(R.string.tune_title), subtitle = stringResource(R.string.tune_subtitle))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                tabs.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = tab == index,
                        onClick = { tab = index },
                        shape = SegmentedButtonDefaults.itemShape(index, tabs.size),
                        modifier = Modifier.tvFocusRing(),
                    ) { Text(stringResource(label)) }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) CalibrationScreen(viewModel) else ColorScreen(viewModel)
        }
    }
}
