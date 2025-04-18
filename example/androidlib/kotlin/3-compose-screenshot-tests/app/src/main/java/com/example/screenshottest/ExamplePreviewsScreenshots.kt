package com.example.screenshottest

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

class ExamplePreviewsScreenshots {

    @Composable
    fun messageCard(name: String) {
        Text(text = "Hello, $name!")
    }
}
