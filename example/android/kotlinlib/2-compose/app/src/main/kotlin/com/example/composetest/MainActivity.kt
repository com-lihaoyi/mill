package com.example.composetest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.composetest.ui.theme.ComposeTestTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComposeTestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    CounterScreen(
                        modifier = Modifier.padding(),
                    )
                }
            }
        }
    }
}

@Suppress("FunctionName")
@Composable
fun CounterScreen(modifier: Modifier = Modifier) {
    val clickCount = remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Button clicked: ${clickCount.intValue} times",
            modifier = Modifier.padding(bottom = 16.dp),
        )

        Button(
            onClick = { clickCount.intValue++ },
            colors = ButtonDefaults.buttonColors().copy(
                containerColor = Color.Cyan,
            ),
        ) {
            Text("Click me!")
        }
    }
}
