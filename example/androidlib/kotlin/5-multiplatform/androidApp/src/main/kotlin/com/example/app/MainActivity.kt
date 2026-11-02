package com.example.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import com.example.shared.Greeting
import com.example.shared.Message
import com.example.shared.R
import com.example.shared.getPlatform
import com.example.shared.toJson

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = getString(R.string.shared_title)
        val message = Message(Greeting().greet(), getPlatform().name)
        setContentView(TextView(this).apply {
            text = "$title: ${message.toJson()}"
            gravity = Gravity.CENTER
        })
    }
}
