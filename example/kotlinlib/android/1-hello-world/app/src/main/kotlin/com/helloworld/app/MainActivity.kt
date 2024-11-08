package com.helloworld.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create a new TextView
        val textView = TextView(this)

        // Set the text to "Hello, World!"
        textView.text = "Hello, World Kotlin!"

        // Set text size
        textView.textSize = 32f

        // Center the text within the view
        textView.gravity = Gravity.CENTER

        // Set layout parameters (width and height)
        textView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        // Set the content view to display the TextView
        setContentView(textView)
    }
}
