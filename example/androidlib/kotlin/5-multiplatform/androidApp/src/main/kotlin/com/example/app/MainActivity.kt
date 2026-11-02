package com.example.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import shared.Message
import shared.greeting
import shared.platformName
import shared.toJson

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = Message(greeting(), platformName()).toJson()
        })
    }
}
