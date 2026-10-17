package org.pauza.qa.browser;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.TextView;

public class BrowserActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView page = new TextView(this);
        page.setText("ЗАПРЕЩЁННАЯ ВНЕШНЯЯ СТРАНИЦА " + getIntent().getDataString());
        page.setBackgroundColor(Color.WHITE);
        page.setTextSize(22f);
        page.setGravity(Gravity.CENTER);
        setContentView(page);
    }
}