package com.akash.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity implements RecognitionListener {

    static final String[] MODELS = {"gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3.8-flash"};
    static final String SYSTEM_PROMPT =
            "You are a friendly voice assistant on an Android phone. "
            + "Reply in the same language the user spoke: Hindi in Devanagari script, or English. "
            + "Keep answers short, at most two or three sentences. "
            + "Do not use markdown, lists, asterisks or emojis.";

    TextView status;
    Button talk;
    Button langBtn;
    Button keyBtn;
    SpeechRecognizer recognizer;
    TextToSpeech tts;
    Handler h = new Handler(Looper.getMainLooper());
    boolean hindi = true;
    boolean active = false;
    boolean ttsReady = false;
    ArrayList<String[]> history = new ArrayList<>();
    String lastErr = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);

        status = new TextView(this);
        status.setTextSize(20);
        status.setGravity(Gravity.CENTER);

        talk = new Button(this);
        talk.setTextSize(26);
        talk.setOnClickListener(v -> setActive(!active));

        langBtn = new Button(this);
        langBtn.setOnClickListener(v -> {
            hindi = !hindi;
            updateLang();
        });

        keyBtn = new Button(this);
        keyBtn.setOnClickListener(v -> askKey());

        root.addView(status);
        root.addView(talk);
        root.addView(langBtn);
        root.addView(keyBtn);
        setContentView(root);
        updateLang();

        tts = new TextToSpeech(this, st -> ttsReady = (st == TextToSpeech.SUCCESS));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }
            @Override public void onError(String utteranceId) { restart(800); }
            @Override public void onDone(String utteranceId) { restart(800); }
        });

        requestPermissions(new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS}, 1);
    }

    void updateLang() {
        talk.setText(active ? (hindi ? "बंद करो" : "STOP") : "BOLO");
        if (hindi) {
            langBtn.setText("भाषा: हिंदी (बदलो)");
            keyBtn.setText("KEY डालो");
            status.setText("BOLO ek baar dabao, phir bolte raho. Band karne ke liye dobara dabao.\n\nटॉर्च चालू करो\nव्हाट्सऐप ओपन करो\nभारत की राजधानी क्या है");
        } else {
            langBtn.setText("Language: English (change)");
            keyBtn.setText("Set KEY");
            status.setText("Press BOLO once, then keep talking. Press again to stop.\n\ntorch on\nopen WhatsApp\ntell me a joke");
        }
    }

    void setActive(boolean on) {
        active = on;
        h.removeCallbacksAndMessages(null);
        if (on) {
            talk.setText(hindi ? "बंद करो" : "STOP");
            if (tts != null) tts.stop();
            startListening();
        } else {
            talk.setText("BOLO");
            if (recognizer != null) recognizer.cancel();
            if (tts != null) tts.stop();
            status.setText(hindi ? "बंद कर दिया. फिर शुरू करने के लिए BOLO दबाओ"
                    : "Stopped. Press BOLO to start again");
        }
    }

    void restart(long delay) {
        if (!active) return;
        h.postDelayed(() -> {
            if (active) startListening();
        }, delay);
    }

    void askKey() {
        EditText et = new EditText(this);
        et.setHint("Gemini API key");
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Gemini API key")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String k = et.getText().toString().trim();
                    getSharedPreferences("cfg", MODE_PRIVATE)
                            .edit().putString("key", k).apply();
                    speak("की सेव हो गई", "Key saved");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    String getKey() {
        return getSharedPreferences("cfg", MODE_PRIVATE).getString("key", "");
    }

    void say(String msg, String id) {
        status.setText(msg);
        if (tts != null && ttsReady) {
            tts.setLanguage(hindi ? new Locale("hi", "IN") : new Locale("en", "IN"));
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, id);
        } else {
            restart(1500);
        }
    }

    void speak(String hi, String en) {
        say(hindi ? hi : en, "msg");
    }

    void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            setActive(false);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
        } else {
            recognizer.cancel();
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, hindi ? "hi-IN" : "en-IN");
        recognizer.startListening(i);
        status.setText(hindi ? "सुन रहा हूँ..." : "Listening...");
    }

    boolean has(String t, String... keys) {
        for (String k : keys) {
            if (t.contains(k)) return true;
        }
        return false;
    }

    String stripWords(String text, String... junk) {
        StringBuilder sb = new StringBuilder();
        for (String tok : text.split("\\s+")) {
            boolean skip = false;
            for (String j : junk) {
                if (tok.equals(j)) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(tok);
            }
        }
        return sb.toString().trim();
    }

    String digitsOf(String t) {
        StringBuilder sb = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (Character.isDigit(c)) sb.append(Character.digit(c, 10));
        }
        return sb.toString();
    }

    void handle(String raw) {
        int words = raw.trim().split("\\s+").length;
        if (words <= 8 && handleLocal(raw)) return;

        String key = getKey();
        if (key.isEmpty()) {
            speak("पहले KEY बटन से Gemini की डालो", "Please set the Gemini key first using the KEY button");
            return;
        }
        askGemini(raw, key);
    }

    boolean handleLocal(String raw) {
        String t = raw.toLowerCase(Locale.ROOT).trim();

        if (has(t, "torch", "flashlight", "flash light", "टॉर्च", "टार्च", "फ्लैश", "फ्लेश")) {
            boolean off = has(t, "off", "ऑफ", "band", "बंद", "बन्द");
            torch(!off);
            return true;
        }

        if (has(t, "battery", "बैटरी")) {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            speak("बैटरी " + pct + " प्रतिशत है", "Battery is " + pct + " percent");
            return true;
        }

        if (has(t, "alarm", "अलार्म")) {
            setAlarm(digitsOf(t));
            return true;
        }

        if (has(t, "volume", "वॉल्यूम", "वोल्यूम", "आवाज")) {
            boolean down = has(t, "down", "kam", "कम", "घटा", "decrease", "low");
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    down ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI);
            speak(down ? "आवाज कम की" : "आवाज बढ़ाई",
                    down ? "Volume down" : "Volume up");
            return true;
        }

        if (has(t, "time", "समय", "टाइम", "कितने बजे")) {
            String now = new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date());
            speak("अभी समय है " + now, "The time is " + now);
            return true;
        }

        if (has(t, "call", "कॉल", "फोन", "phone")) {
            String digits = digitsOf(t);
            if (digits.length() >= 3) {
                dial(digits, digits);
            } else {
                String name = stripWords(t, "call", "कॉल", "करो", "कर", "लगाओ", "लगा",
                        "फोन", "phone", "to", "को", "please", "प्लीज", "दो", "मिलाओ");
                if (name.isEmpty()) {
                    speak("किसे कॉल करूँ?", "Whom should I call?");
                } else {
                    String number = findNumber(name);
                    if (number == null) {
                        speak(name + " नहीं मिला", "Contact not found: " + name);
                    } else {
                        dial(number, name);
                    }
                }
            }
            return true;
        }

        if (has(t, "open", "ओपन", "खोल", "kholo", "khol", "start", "स्टार्ट", "चलाओ")) {
            String name = stripWords(t, "open", "ओपन", "kholo", "khol", "खोलो", "खोल",
                    "खोलिए", "खोलिये", "करो", "कर", "दो", "दीजिए", "को", "ऐप", "एप",
                    "app", "please", "प्लीज", "start", "स्टार्ट", "चलाओ", "जी", "ज़रा");
            openApp(aliasName(name));
            return true;
        }

        if (has(t, "search", "सर्च", "खोजो", "ढूंढो", "ढूँढो")) {
            String q = stripWords(t, "search", "सर्च", "google", "गूगल", "करो", "कर",
                    "पर", "पे", "for", "on", "खोजो", "ढूंढो", "ढूँढो");
            if (q.isEmpty()) {
                speak("क्या सर्च करूँ?", "What should I search?");
            } else {
                speak("सर्च कर रहा हूँ " + q, "Searching " + q);
                Intent s = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)));
                startActivity(s);
            }
            return true;
        }

        return false;
    }

    void askGemini(String userText, String key) {
        status.setText(hindi ? "सोच रहा हूँ..." : "Thinking...");
        new Thread(() -> {
            String reply = null;
            int errCode = 0;
            lastErr = "";
            byte[] payload = null;
            try {
                JSONObject body = new JSONObject();
                body.put("system_instruction", new JSONObject().put("parts",
                        new JSONArray().put(new JSONObject().put("text", SYSTEM_PROMPT))));
                JSONArray contents = new JSONArray();
                for (String[] hh : history) {
                    contents.put(new JSONObject().put("role", hh[0]).put("parts",
                            new JSONArray().put(new JSONObject().put("text", hh[1]))));
                }
                contents.put(new JSONObject().put("role", "user").put("parts",
                        new JSONArray().put(new JSONObject().put("text", userText))));
                body.put("contents", contents);
                payload = body.toString().getBytes("UTF-8");
            } catch (Exception e) {
                errCode = -1;
                lastErr = e.toString();
            }

            if (payload != null) {
                for (String model : MODELS) {
                    try {
                        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                                + model + ":generateContent");
                        HttpURLConnection c = (HttpURLConnection) url.openConnection();
                        c.setRequestMethod("POST");
                        c.setRequestProperty("Content-Type", "application/json");
                        c.setRequestProperty("x-goog-api-key", key);
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(20000);
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
                            reply = out.toString().replace("*", "").replace("#", "").trim();
                            errCode = 0;
                            break;
                        } else {
                            errCode = code;
                            lastErr = model + " -> HTTP " + code;
                            if (code == 400 || code == 403) break;
                        }
                    } catch (Exception e) {
                        errCode = -1;
                        lastErr = model + " -> " + e.toString();
                    }
                }
            }

            final String finalReply = reply;
            final int finalErr = errCode;
            runOnUiThread(() -> {
                if (!active) return;
                if (finalReply != null && !finalReply.isEmpty()) {
                    history.add(new String[]{"user", userText});
                    history.add(new String[]{"model", finalReply});
                    while (history.size() > 12) {
                        history.remove(0);
                    }
                    say(finalReply, "gemini");
                } else if (finalErr == 400 || finalErr == 403) {
                    speak("Key सही नहीं है, फिर से डालो", "The key looks wrong, please set it again");
                } else if (finalErr == 429) {
                    speak("आज की free limit पूरी हो गई, थोड़ी देर बाद try करो",
                            "Free limit reached, try again later");
                } else {
                    status.setText("Error: " + lastErr);
                    restart(2500);
                }
            });
        }).start();
    }

    void torch(boolean on) {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String id = cm.getCameraIdList()[0];
            cm.setTorchMode(id, on);
            speak(on ? "टॉर्च चालू" : "टॉर्च बंद", on ? "Torch on" : "Torch off");
        } catch (Exception e) {
            speak("टॉर्च नहीं चली", "Torch did not work");
        }
    }

    void setAlarm(String digits) {
        if (digits.isEmpty()) {
            speak("समय बोलो, जैसे अलार्म 7 30", "Say a time, like alarm 7 30");
            return;
        }
        int hr;
        int mi;
        try {
            if (digits.length() <= 2) {
                hr = Integer.parseInt(digits);
                mi = 0;
            } else {
                hr = Integer.parseInt(digits.substring(0, digits.length() - 2));
                mi = Integer.parseInt(digits.substring(digits.length() - 2));
            }
        } catch (Exception e) {
            speak("समय समझ नहीं आया", "Could not understand the time");
            return;
        }
        if (hr > 23 || mi > 59) {
            speak("समय सही नहीं है", "That time is not valid");
            return;
        }
        Intent a = new Intent(AlarmClock.ACTION_SET_ALARM);
        a.putExtra(AlarmClock.EXTRA_HOUR, hr);
        a.putExtra(AlarmClock.EXTRA_MINUTES, mi);
        a.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        startActivity(a);
        speak("अलार्म लगा दिया " + hr + " बजकर " + mi + " मिनट", "Alarm set for " + hr + ":" + mi);
    }

    void dial(String number, String label) {
        speak("कॉल कर रहा हूँ " + label, "Calling " + label);
        Intent call;
        if (checkSelfPermission(Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) {
            call = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
        } else {
            call = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
        }
        startActivity(call);
    }

    String findNumber(String name) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        Cursor c = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, null);
        if (c == null) return null;
        try {
            while (c.moveToNext()) {
                String dn = c.getString(0);
                if (dn != null && dn.toLowerCase(Locale.ROOT).contains(name)) {
                    return c.getString(1);
                }
            }
        } finally {
            c.close();
        }
        return null;
    }

    String aliasName(String n) {
        if (has(n, "व्हाट्सएप", "व्हाट्सऐप", "वाट्सएप", "वाट्सऐप", "व्हाट्स ऐप", "व्हाट्स एप")) return "whatsapp";
        if (has(n, "यूट्यूब", "यू ट्यूब", "यूटयूब")) return "youtube";
        if (has(n, "कैमरा")) return "camera";
        if (has(n, "गैलरी")) return "gallery";
        if (has(n, "सेटिंग")) return "settings";
        if (has(n, "क्रोम")) return "chrome";
        if (has(n, "इंस्टाग्राम", "इंस्टा")) return "instagram";
        if (has(n, "फेसबुक")) return "facebook";
        if (has(n, "मैप")) return "maps";
        if (has(n, "जीमेल")) return "gmail";
        if (has(n, "कैलकुलेटर")) return "calculator";
        if (has(n, "घड़ी")) return "clock";
        if (has(n, "फोटो")) return "photos";
        return n;
    }

    void openApp(String name) {
        if (name.isEmpty()) {
            speak("कौन सा ऐप खोलूँ?", "Which app should I open?");
            return;
        }
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        for (ResolveInfo ri : apps) {
            String label = ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
            boolean match = label.contains(name) || (label.length() >= 3 && name.contains(label));
            if (match) {
                Intent launch = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                if (launch != null) {
                    speak("खोल रहा हूँ " + label, "Opening " + label);
                    startActivity(launch);
                    return;
                }
            }
        }
        speak(name + " ऐप नहीं मिला", name + " app not found");
    }

    @Override
    public void onResults(Bundle results) {
        if (!active) return;
        ArrayList<String> list =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) {
            handle(list.get(0));
        } else {
            restart(300);
        }
    }

    @Override
    public void onError(int error) {
        if (!active) return;
        if (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            restart(300);
        } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                || error == SpeechRecognizer.ERROR_CLIENT) {
            if (recognizer != null) recognizer.cancel();
            restart(1000);
        } else {
            setActive(false);
            status.setText(hindi ? "मिक या इंटरनेट में दिक्कत (error " + error + ")"
                    : "Mic or internet problem (error " + error + ")");
        }
    }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onPartialResults(Bundle partialResults) { }
    @Override public void onEvent(int eventType, Bundle params) { }

    @Override
    protected void onPause() {
        super.onPause();
        if (active) {
            active = false;
            h.removeCallbacksAndMessages(null);
            if (recognizer != null) recognizer.cancel();
            talk.setText("BOLO");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
    }
}
