package com.instagram.android;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FixtureActivity extends Activity {
    private static final String TAG = "ShortVideoFixture";
    private FrameLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        showReels();
    }

    private void showReels() {
        root.removeAllViews();

        FrameLayout player = new FrameLayout(this);
        player.setId(R.id.clips_viewer_view_pager);
        player.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        player.setContentDescription("Reels player");
        player.setBackgroundColor(Color.rgb(24, 24, 24));
        root.addView(player, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView state = stateLabel("REELS_PLAYER", Color.WHITE);
        FrameLayout.LayoutParams stateParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        stateParams.gravity = Gravity.CENTER;
        player.addView(state, stateParams);

        root.addView(bottomNavigation(false));
        Log.i(TAG, "STATE_REELS_PLAYER");
    }

    private void showSafeHome() {
        root.removeAllViews();

        TextView state = stateLabel("SAFE_HOME", Color.BLACK);
        FrameLayout.LayoutParams stateParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        stateParams.gravity = Gravity.CENTER;
        root.addView(state, stateParams);

        root.addView(bottomNavigation(true));
        Log.i(TAG, "STATE_SAFE_HOME");
    }

    private TextView stateLabel(String value, int color) {
        TextView state = new TextView(this);
        state.setId(R.id.state_label);
        state.setText(value);
        state.setTextColor(color);
        state.setTextSize(24f);
        state.setContentDescription(value);
        return state;
    }

    private LinearLayout bottomNavigation(boolean homeSelected) {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(Color.rgb(245, 245, 245));

        Button home = new Button(this);
        home.setId(R.id.feed_tab);
        home.setText("Home");
        home.setContentDescription("Home");
        home.setSelected(homeSelected);
        home.setOnClickListener(v -> showSafeHome());

        Button reels = new Button(this);
        reels.setId(R.id.reels_tab);
        reels.setText("Reels");
        reels.setContentDescription("Reels");
        reels.setSelected(!homeSelected);
        reels.setOnClickListener(v -> showReels());

        nav.addView(home, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(reels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.BOTTOM;
        nav.setLayoutParams(params);
        return nav;
    }
}
