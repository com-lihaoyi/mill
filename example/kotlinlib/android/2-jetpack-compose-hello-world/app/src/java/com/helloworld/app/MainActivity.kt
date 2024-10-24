package com.helloworld.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val linearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.white)) // Use color from resources
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }

        val textView = TextView(this).apply {
            text = getString(R.string.hello_world)
            textSize = 32f
            setTextColor(getColor(R.color.text_green)) // Use color from resources
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16) 
        }

        linearLayout.addView(textView)

        setContentView(linearLayout)
    }
}
