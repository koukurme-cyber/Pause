package org.pauza.qa.origin;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class OriginActivity extends Activity {
    private TextView state;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int created = getPreferences(MODE_PRIVATE).getInt("created", 0) + 1;
        getPreferences(MODE_PRIVATE).edit().putInt("created", created).apply();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(30, 24, 30, 24);
        layout.setBackgroundColor(Color.WHITE);
        state = new TextView(this);
        state.setText("Источник ссылок — исходный экран; создание Activity: " + created);
        state.setTextSize(19f);
        layout.addView(state);
        Button open = new Button(this);
        open.setText("Открыть внешнюю ссылку");
        open.setOnClickListener((View v) -> {
            state.setText("Источник ссылок — тот же экран; создание Activity: " + created);
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/from-origin"));
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            browser.setPackage("org.pauza.qa.browser");
            startActivity(browser);
        });
        layout.addView(open);
        setContentView(layout);
    }
}