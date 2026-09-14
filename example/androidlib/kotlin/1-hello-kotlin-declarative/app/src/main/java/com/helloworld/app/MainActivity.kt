package com.helloworld.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
import android.widget.TextView
import com.helloworld.SampleLogic

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create a new TextView
        val textView = TextView(this)

        // Set the text to the string resource
        textView.text = getString(R.string.hello_world)

        // Set text size
        textView.textSize = SampleLogic.textSize()

        // Center the text within the view
        textView.gravity = Gravity.CENTER

        // Set layout parameters (width and height)
        textView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        // Set the text color using a resource
        textView.setTextColor(getColor(R.color.text_green))

        // Set the background color using a resource
        textView.setBackgroundColor(getColor(R.color.white))

        // Set the content view to display the TextView
        setContentView(textView)
    }
}
