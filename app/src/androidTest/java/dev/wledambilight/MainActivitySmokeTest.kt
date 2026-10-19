package dev.wledambilight

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test that the single activity can compose the Compose tree. It runs on a device/emulator;
 * the repository CI compiles it, manual execution is documented in TESTING.md.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityComposes() {
        composeRule.onRoot().assertExists()
    }
}
