package com.akash.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public class AgentService extends AccessibilityService {

    public static AgentService instance;

    static final String[] MODELS = {"gemini-3.6-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite"};
    static final int MAX_STEPS = 25;

    static final String SYSTEM_PROMPT =
            "You control an Android phone for the user by looking at the screen and choosing ONE action at a time. "
            + "You get the GOAL, the actions already done, and the CURRENT SCREEN as a list of UI elements like [id] ClassName text='..' desc='..' flags. "
            + "Flags: c=clickable, e=editable text field, s=scrollable, f=focused, k=checked. "
            + "Reply with ONLY one JSON object with these keys: "
            + "action (click, long_click, type, enter, scroll, back, home, open_app, wait, done, ask), "
            + "id (number, for click, long_click, type, scroll), text (what to type), "
            + "direction (down or up, for scroll), app (app name, for open_app), "
            + "say (short message to the user, for done and ask), why (a few words). "
            + "Rules: "
            + "1. One action per reply. "
            + "2. To type, use type with the id of the text field (click it first if needed), then use enter or click the search or send button. "
            + "3. Use open_app to start an app. Names like WhatsApp, YouTube, Chrome work. "
            + "4. If the goal is only a question or chat that needs no phone action, reply done and put the answer in say. "
            + "5. When the goal is fully complete, reply done and say what you did. "
            + "6. Never type passwords, OTP codes, UPI PINs or card details, and never confirm a payment, purchase, money transfer or account deletion. Reply ask and tell the user to do that step. "
            + "7. If the screen is empty or still loading use wait. If you are stuck or nothing changes after a few tries, reply ask. "
            + "8. Write say in Hindi (Devanagari) if the user language is Hindi, otherwise English. Keep say under 25 words.";

    Handler main = new Handler(Looper.getMainLooper());
    WindowManager wm;
    TextView bar;
    TextToSpeech tts;
    boolean ttsReady = false;
    volatile boolean stopFlag = false;
    volatile boolean running = false;
    ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
    String lastErr = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        tts = new TextToSpeech(this, st -> ttsReady = (st == TextToSpeech.SUCCESS));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    public boolean runGoal(String goal, String key, boolean hindi) {
        if (running) return false;
        running = true;
        stopFlag = false;
        showBar();
        new Thread(() -> loop(goal, key, hindi)).start();
        return true;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    void showBar() {
        main.post(() -> {
            if (bar != null) return;
            bar = new TextView(this);
            bar.setTextColor(0xFFFFFFFF);
            bar.setBackgroundColor(0xE6B00020);
            bar.setTextSize(12);
            bar.setPadding(20, 6, 20, 6);
            bar.setGravity(Gravity.CENTER);
            bar.setSingleLine(true);
            bar.setText("STOP (tap here) | starting...");
            bar.setOnClickListener(v -> stopFlag = true);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            try {
                wm.addView(bar, lp);
            } catch (Exception e) {
                bar = null;
            }
        });
    }

    void setBar(String s) {
        main.post(() -> {
            if (bar != null) bar.setText("STOP (tap here) | " + s);
        });
    }

    void hideBar() {
        main.post(() -> {
            if (bar != null) {
                try {
                    wm.removeView(bar);
                } catch (Exception e) {
                    // ignore
                }
                bar = null;
            }
        });
    }

    void finish(String msg, boolean hindi) {
        hideBar();
        running = false;
        main.post(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            if (tts != null && ttsReady) {
                tts.setLanguage(hindi ? new Locale("hi", "IN") : new Locale("en", "IN"));
                tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "agent");
            }
        });
    }

    void loop(String goal, String key, boolean hindi) {
        ArrayList<String> doneList = new ArrayList<>();
        String finalMsg = hindi ? "काम पूरा हुआ" : "Done";
        String lastDesc = "";
        int repeat = 0;
        int step;
        try {
            for (step = 1; step <= MAX_STEPS; step++) {
                if (stopFlag) {
                    finalMsg = hindi ? "रोक दिया" : "Stopped";
                    break;
                }
                setBar("step " + step + ": reading screen");
                sleep(600);
                String screen = snapshot();
                JSONObject act = askModel(buildPrompt(goal, step, doneList, screen, hindi), key);
                if (stopFlag) {
                    finalMsg = hindi ? "रोक दिया" : "Stopped";
                    break;
                }
                if (act == null) {
                    finalMsg = (hindi ? "Gemini से जवाब नहीं मिला: " : "No answer from Gemini: ") + lastErr;
                    break;
                }
                String action = act.optString("action", "");
                if (action.equals("done") || action.equals("ask")) {
                    String say = act.optString("say", "");
                    if (!say.isEmpty()) finalMsg = say;
                    break;
                }
                String desc = describe(act);
                setBar("step " + step + ": " + desc);
                if (desc.equals(lastDesc)) {
                    repeat++;
                } else {
                    repeat = 0;
                }
                lastDesc = desc;
                if (repeat >= 2) {
                    finalMsg = hindi ? "मैं अटक गया हूँ, आगे आप कर लो" : "I am stuck, please continue yourself";
                    break;
                }
                String result = perform(act);
                doneList.add(step + ". " + desc + " -> " + result);
                sleep(action.equals("open_app") ? 2500 : 1300);
            }
            if (step > MAX_STEPS) {
                finalMsg = hindi ? "बहुत स्टेप हो गए, रोक रहा हूँ" : "Too many steps, stopping";
            }
        } catch (Exception e) {
            finalMsg = "Error: " + e;
        }
        finish(finalMsg, hindi);
    }

    String describe(JSONObject act) {
        String a = act.optString("action", "");
        StringBuilder sb = new StringBuilder(a);
        if (act.has("id")) sb.append(" id=").append(act.optInt("id", -1));
        if (act.has("text")) sb.append(" text=").append(act.optString("text", ""));
        if (act.has("app")) sb.append(" app=").append(act.optString("app", ""));
        if (act.has("direction")) sb.append(" dir=").append(act.optString("direction", ""));
        return sb.toString();
    }

    String buildPrompt(String goal, int step, ArrayList<String> done, String screen, boolean hindi) {
        StringBuilder sb = new StringBuilder();
        sb.append("GOAL: ").append(goal).append("\n");
        sb.append("User language: ").append(hindi ? "Hindi" : "English").append("\n");
        sb.append("STEP ").append(step).append(" of ").append(MAX_STEPS).append("\n");
        sb.append("ACTIONS SO FAR:\n");
        if (done.isEmpty()) sb.append("(none)\n");
        int from = Math.max(0, done.size() - 10);
        for (int i = from; i < done.size(); i++) {
            sb.append(done.get(i)).append("\n");
        }
        sb.append("CURRENT SCREEN:\n").append(screen);
        return sb.toString();
    }

    String snapshot() {
        nodes.clear();
        AccessibilityNodeInfo root = null;
        for (int i = 0; i < 4 && root == null; i++) {
            root = getRootInActiveWindow();
            if (root == null) sleep(500);
        }
        if (root == null) return "(screen not readable)";
        StringBuilder sb = new StringBuilder();
        sb.append("app package: ").append(root.getPackageName()).append("\n");
        walk(root, sb);
        if (nodes.isEmpty()) sb.append("(no elements found, screen may be loading)\n");
        return sb.toString();
    }

    String clip(String s) {
        s = s.replace("\n", " ").replace("'", "`");
        return s.length() > 70 ? s.substring(0, 70) : s;
    }

    void walk(AccessibilityNodeInfo n, StringBuilder sb) {
        if (n == null || nodes.size() >= 140) return;
        if (!n.isVisibleToUser()) return;
        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        boolean hasText = text != null && text.length() > 0;
        boolean hasDesc = desc != null && desc.length() > 0;
        if (n.isClickable() || n.isEditable() || n.isScrollable() || n.isCheckable() || hasText || hasDesc) {
            int id = nodes.size();
            nodes.add(n);
            String cls = n.getClassName() == null ? "" : n.getClassName().toString();
            cls = cls.substring(cls.lastIndexOf('.') + 1);
            sb.append('[').append(id).append("] ").append(cls);
            if (n.isPassword()) {
                sb.append(" PASSWORD_FIELD");
            } else if (hasText) {
                sb.append(" text='").append(clip(text.toString())).append("'");
            }
            if (hasDesc) sb.append(" desc='").append(clip(desc.toString())).append("'");
            sb.append(" ");
            if (n.isClickable()) sb.append('c');
            if (n.isEditable()) sb.append('e');
            if (n.isScrollable()) sb.append('s');
            if (n.isFocused()) sb.append('f');
            if (n.isChecked()) sb.append('k');
            sb.append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            walk(n.getChild(i), sb);
        }
    }

    AccessibilityNodeInfo nodeById(JSONObject act) {
        int id = act.optInt("id", -1);
        if (id < 0 || id >= nodes.size()) return null;
        return nodes.get(id);
    }

    String perform(JSONObject act) {
        String a = act.optString("action", "");
        try {
            switch (a) {
                case "click":
                case "long_click": {
                    AccessibilityNodeInfo n = nodeById(act);
                    if (n == null) return "bad id";
                    return clickNode(n, a.equals("long_click")) ? "ok" : "failed";
                }
                case "type": {
                    AccessibilityNodeInfo n = nodeById(act);
                    if (n == null) return "bad id";
                    n.refresh();
                    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    Bundle b = new Bundle();
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            act.optString("text", ""));
                    return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) ? "ok" : "failed";
                }
                case "enter": {
                    if (Build.VERSION.SDK_INT >= 30) {
                        AccessibilityNodeInfo root = getRootInActiveWindow();
                        if (root != null) {
                            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                            if (f != null && f.performAction(
                                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
                                return "ok";
                            }
                        }
                    }
                    return "enter did not work, click the search or send button instead";
                }
                case "scroll": {
                    boolean down = !act.optString("direction", "down").equals("up");
                    AccessibilityNodeInfo t = nodeById(act);
                    int up = 0;
                    while (t != null && !t.isScrollable() && up < 5) {
                        t = t.getParent();
                        up++;
                    }
                    if (t != null && t.isScrollable()) {
                        if (t.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                            return "ok";
                        }
                    }
                    swipeScroll(down);
                    return "swiped";
                }
                case "back":
                    return performGlobalAction(GLOBAL_ACTION_BACK) ? "ok" : "failed";
                case "home":
                    return performGlobalAction(GLOBAL_ACTION_HOME) ? "ok" : "failed";
                case "open_app":
                    return openApp(act.optString("app", ""));
                case "wait":
                    sleep(2000);
                    return "waited";
                default:
                    return "unknown action";
            }
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    boolean clickNode(AccessibilityNodeInfo n, boolean longClick) {
        n.refresh();
        AccessibilityNodeInfo t = n;
        int up = 0;
        while (t != null && !(longClick ? t.isLongClickable() : t.isClickable()) && up < 5) {
            t = t.getParent();
            up++;
        }
        if (t != null) {
            boolean ok = t.performAction(longClick ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                    : AccessibilityNodeInfo.ACTION_CLICK);
            if (ok) return true;
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        return gesture(p, longClick ? 800 : 60);
    }

    void swipeScroll(boolean down) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int hgt = dm.heightPixels;
        Path p = new Path();
        p.moveTo(w / 2f, down ? hgt * 0.7f : hgt * 0.3f);
        p.lineTo(w / 2f, down ? hgt * 0.3f : hgt * 0.7f);
        gesture(p, 400);
    }

    boolean gesture(Path p, long dur) {
        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(p, 0, dur));
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] ok = {false};
        boolean sent = dispatchGesture(gb.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                ok[0] = true;
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription g) {
                latch.countDown();
            }
        }, null);
        if (!sent) return false;
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // ignore
        }
        return ok[0];
    }

    String openApp(String name) {
        String n = name.toLowerCase(Locale.ROOT).trim();
        if (n.isEmpty()) return "no app name";
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        ResolveInfo found = null;
        for (ResolveInfo ri : apps) {
            String label = ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
            if (label.equals(n)) {
                found = ri;
                break;
            }
        }
        if (found == null) {
            for (ResolveInfo ri : apps) {
                String label = ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
                if (label.contains(n) || (label.length() >= 3 && n.contains(label))) {
                    found = ri;
                    break;
                }
            }
        }
        if (found == null) return "app not found";
        Intent l = pm.getLaunchIntentForPackage(found.activityInfo.packageName);
        if (l == null) return "cannot launch";
        l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(l);
        return "opened " + found.loadLabel(pm);
    }

    JSONObject askModel(String prompt, String key) {
        lastErr = "";
        byte[] payload;
        try {
            JSONObject body = new JSONObject();
            body.put("system_instruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", SYSTEM_PROMPT))));
            body.put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts",
                    new JSONArray().put(new JSONObject().put("text", prompt)))));
            body.put("generationConfig", new JSONObject().put("responseMimeType", "application/json"));
            payload = body.toString().getBytes("UTF-8");
        } catch (Exception e) {
            lastErr = e.toString();
            return null;
        }

        for (String model : MODELS) {
            if (stopFlag) return null;
            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                        + model + ":generateContent");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("x-goog-api-key", key);
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(payload);
                os.close();

                int code = c.getResponseCode();
                InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (is != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                }
                if (code == 200) {
                    JSONObject r = new JSONObject(sb.toString());
                    JSONArray parts = r.getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts");
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < parts.length(); i++) {
                        out.append(parts.getJSONObject(i).optString("text", ""));
                    }
                    String txt = out.toString();
                    int a = txt.indexOf('{');
                    int b = txt.lastIndexOf('}');
                    if (a >= 0 && b > a) {
                        return new JSONObject(txt.substring(a, b + 1));
                    }
                    lastErr = model + " gave no JSON";
                } else {
                    lastErr = model + " HTTP " + code;
                    if (code == 400 || code == 403) return null;
                }
            } catch (Exception e) {
                lastErr = model + " " + e;
            }
        }
        return null;
    }
}
