package com.fason.app.features.overlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.util.Log;

public class OverlayAccessibilityService extends AccessibilityService {
    private static final String TAG = "OverlayA11y";
    private OverlayManager overlayManager;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        overlayManager = OverlayManager.getInstance(this);
        Log.i(TAG, "Overlay accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (overlayManager != null) {
            overlayManager.handleAccessibilityEvent(event);
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Overlay accessibility service destroyed");
    }
}
