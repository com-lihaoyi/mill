package com.calculator.app;

import com.sumlib.app.Main;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup.LayoutParams;
import android.widget.TextView;

public class Calculator extends Activity {

    public static int plus(int a, int b) {
        return Main.sum(a, b);
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Create a new TextView
        TextView textView = new TextView(this);


        int size = plus(1, 2);

        // Set text size
        textView.setTextSize(size);

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
