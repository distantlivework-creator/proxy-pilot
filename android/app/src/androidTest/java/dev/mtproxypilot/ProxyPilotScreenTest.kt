package dev.mtproxypilot

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import dev.mtproxypilot.domain.Availability
import dev.mtproxypilot.domain.MtProtoProxy
import dev.mtproxypilot.domain.ProxyAvailability
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class ProxyPilotScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val proxy = ProxyAvailability(
        proxy = MtProtoProxy("proxy.example", 443, "9c1d9ff498b2baa7cba0b0336239f509"),
        attempts = 2,
        successfulAttempts = 2,
        medianLatencyMs = 82,
        availability = Availability.AVAILABLE,
    )

    @Test
    fun userOpensGuideAndChoosesReachableProxy() {
        var opened: ProxyAvailability? = null
        compose.setContent {
            MaterialTheme {
                PilotScreen(
                    state = MainUiState(
                        stage = AppStage.READY,
                        results = listOf(proxy),
                        totalCandidates = 4,
                    ),
                    onRefresh = {},
                    onDismissSharedMessage = {},
                    onOpenProxy = { opened = it },
                )
            }
        }

        compose.onNodeWithText("Доступно 1 из 4").assertIsDisplayed()
        compose.onNodeWithTag("brand").assertIsDisplayed()
        compose.onNodeWithTag("guide").performClick()
        compose.onNodeWithTag("guideDialog").assertIsDisplayed()
        compose.onNodeWithText("Вход, регистрация и ваш Telegram-аккаунт не нужны.").assertIsDisplayed()
        captureScreen("proxy-pilot-guide.png")
        compose.onNodeWithTag("guideDone").performClick()
        compose.onNodeWithTag("mainScroll").performScrollToIndex(3)
        compose.onNodeWithTag("openProxy").assertIsDisplayed().performClick()
        captureScreen("proxy-pilot-user-flow.png")

        assertEquals(proxy, opened)
    }

    @Test
    fun sharedTelegramMessageIsExplainedOnScreen() {
        compose.setContent {
            MaterialTheme {
                PilotScreen(
                    state = MainUiState(
                        stage = AppStage.READY,
                        sharedLinksAdded = 2,
                        sharedMessage = "Получено из Telegram: 2. Проверяем с этого устройства.",
                    ),
                    onRefresh = {},
                    onDismissSharedMessage = {},
                    onOpenProxy = {},
                )
            }
        }

        compose.onNodeWithTag("mainScroll").performScrollToIndex(2)
        compose.onNodeWithTag("sharedNotice").assertIsDisplayed()
        compose.onNodeWithText("Получено из Telegram: 2. Проверяем с этого устройства.").assertIsDisplayed()
    }

    private fun captureScreen(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        FileOutputStream(output).use { stream ->
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        instrumentation.uiAutomation
            .executeShellCommand("cp ${output.absolutePath} /sdcard/Download/$name")
            .close()
    }
}
