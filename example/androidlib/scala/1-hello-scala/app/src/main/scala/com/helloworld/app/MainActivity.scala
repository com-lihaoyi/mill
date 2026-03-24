package com.helloworld.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import org.jetbrains.skia.*

class MainActivity extends Activity:
  override def onCreate(savedInstanceState: Bundle): Unit =
    super.onCreate(savedInstanceState)

    // A simple view to prove the activity loads
    val textView = new TextView(this)
    textView.setText("Hello from Scala 3 on Android!")

    setContentView(textView)

  def drawSomething(): Unit =
    val surface = Surface.Companion.makeRasterN32Premul(100, 100)
    val canvas = surface.getCanvas()

    val paint = new Paint()
    paint.setColor(0xFFFF0000) // ARGB Red

    val rect = Rect.makeXYWH(10f, 10f, 80f, 80f)
    canvas.drawRect(rect, paint)

    println("Skia drew a red rectangle successfully!")
