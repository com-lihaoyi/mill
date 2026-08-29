package com.helloworld.gradle8

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this)
        textView.text = getString(R.string.app_name)
        textView.textSize = SampleLogic.textSize()
        textView.gravity = Gravity.CENTER
        textView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        textView.setTextColor(getColor(R.color.text_green))
        textView.setBackgroundColor(getColor(R.color.white))
        setContentView(textView)
    }
}
