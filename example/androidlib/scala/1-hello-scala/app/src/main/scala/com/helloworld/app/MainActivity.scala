package com.helloworld.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import org.jetbrains.skia.*

object MainActivity {
  def calculateRectArea(width: Float, height: Float): Float = width * height
}

class MainActivity extends Activity {
  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)

    // A simple view to prove the activity loads
    val textView = new TextView(this)
    textView.setText(getString(R.string.hello_scala))

    setContentView(textView)
  }

  def drawSomething(): Unit = {
    val size = getResources().getDimension(R.dimen.skia_size).toInt
    val surface = Surface.Companion.makeRasterN32Premul(size, size)
    val canvas = surface.getCanvas()

    val paint = new Paint()
    paint.setColor(getColor(R.color.red_skia))

    val x = getResources().getDimension(R.dimen.skia_rect_x)
    val y = getResources().getDimension(R.dimen.skia_rect_y)
    val w = getResources().getDimension(R.dimen.skia_rect_w)
    val h = getResources().getDimension(R.dimen.skia_rect_h)

    // Using our logic for logging
    val area = MainActivity.calculateRectArea(w, h)
    println(s"Drawing rectangle with area: $area")

    val rect = Rect.makeXYWH(x, y, w, h)
    canvas.drawRect(rect, paint)

    println(getString(R.string.skia_success))
  }
}
