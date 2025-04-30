package com.helloworld.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup.LayoutParams;
import android.widget.TextView;

public class MainActivity extends Activity {

  static {
    System.loadLibrary("native-lib");
  }

  native String stringFromJNI();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    String nativeMessage = stringFromJNI();

    TextView textView = new TextView(this);

    // Set the text to the string resource
    textView.setText(nativeMessage);

    // Set text size
    textView.setTextSize(32);

    // Center the text within the view
    textView.setGravity(Gravity.CENTER);

    // Set the layout parameters (width and height)
    textView.setLayoutParams(
        new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

    // Set the text color using a resource
    textView.setTextColor(getResources().getColor(R.color.text_green));

    // Set the background color using a resource
    textView.setBackgroundColor(getResources().getColor(R.color.white));

    // Set the content view to display the TextView
    setContentView(textView);
  }
}
