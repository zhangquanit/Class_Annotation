package com.annotation;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import injecter.api.Injecter;
import injecter.annotation.BindView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Injecter.bind(this);
        textView.setText("我通过BindView注解生成的");
    }
    @BindView(R.id.textview)
    TextView textView;
}
