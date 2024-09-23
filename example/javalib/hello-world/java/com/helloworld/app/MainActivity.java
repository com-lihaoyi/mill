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
        setContentView(R.layout.activity_main);
    }
}