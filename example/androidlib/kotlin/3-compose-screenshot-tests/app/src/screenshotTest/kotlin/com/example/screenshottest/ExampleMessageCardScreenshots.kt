package com.example.screenshottest

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.android.tools.screenshot.PreviewTest

class ExampleMessageCardScreenshots {

    @PreviewTest
    @PreviewScreenSizes
    @Composable
    fun oMessageCardScreenshot() {
        previewMessageCard()
    }
}

@Preview
@Composable
internal fun previewMessageCard() {
    ExamplePreviewsScreenshots().messageCard("Android!!!")
}
