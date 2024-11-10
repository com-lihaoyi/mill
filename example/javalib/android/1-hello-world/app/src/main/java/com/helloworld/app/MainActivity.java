package com.helloworld.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.view.ViewGroup.LayoutParams;
import android.view.Gravity;


public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Create a new TextView
        TextView textView = new TextView(this);

        // Set the text to "Hello, World!"
        textView.setText("Hello, World!");

        // Set text size
        textView.setTextSize(32);

        // Center the text within the view
        textView.setGravity(Gravity.CENTER);

        // Set layout parameters (width and height)
        textView.setLayoutParams(new LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT));

        // Set the content view to display the TextView
        setContentView(textView);
    }
}
