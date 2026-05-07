package com.helloworld.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.google.gson.Gson

case class Greeting(message: String, version: Int)

object MainActivity {
  private val gson = Gson()

  def parseMessage(json: String): String = {
    val g = gson.fromJson(json, classOf[Greeting])
    g.message
  }
}

class MainActivity extends Activity {
  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)

    // Parse message using Gson
    val json = getString(R.string.json_greeting)
    val message = MainActivity.parseMessage(json)

    // Create a new TextView
    val textView = TextView(this)
    textView.setText(message)

    setContentView(textView)
  }
}
