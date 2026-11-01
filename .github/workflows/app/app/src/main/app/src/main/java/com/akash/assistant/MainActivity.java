package com.akash.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements RecognitionListener {

    TextView status;
    SpeechRecognizer recognizer;
    TextToSpeech tts;

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
        status.setText("Button dabao aur bolo:\nopen WhatsApp\ncall 9876543210");

        Button talk = new Button(this);
        talk.setText("BOLO");
        talk.setTextSize(24);
        talk.setOnClickListener(v -> startListening());

        root.addView(status);
        root.addView(talk);
        setContentView(root);

        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("en", "IN"));
            }
        });

        requestPermissions(new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CALL_PHONE}, 1);
    }

    void speak(String msg) {
        status.setText(msg);
        if (tts != null) {
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "msg");
        }
    }

    void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
        recognizer.startListening(i);
        status.setText("Sun raha hu...");
    }

    void handle(String text) {
        text = text.toLowerCase().trim();

        if (text.contains("call")) {
            String digits = text.replaceAll("[^0-9]", "");
            if (digits.length() >= 3) {
                speak("Calling " + digits);
                Intent call = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + digits));
                startActivity(call);
            } else {
                speak("Number bolo, jaise call 9876543210");
            }
            return;
        }

        if (text.contains("open") || text.contains("khol")) {
            String name = text.replace("open", "")
                    .replace("kholo", "")
                    .replace("khol", "")
                    .replace("karo", "")
                    .trim();
            openApp(name);
            return;
        }

        speak("Samajh nahi aaya: " + text);
    }

    void openApp(String name) {
        if (name.isEmpty()) {
            speak("Kaunsa app kholu?");
            return;
        }
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        for (ResolveInfo ri : apps) {
            String label = ri.loadLabel(pm).toString().toLowerCase();
            if (label.contains(name) || name.contains(label)) {
                Intent launch = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                if (launch != null) {
                    speak("Opening " + label);
                    startActivity(launch);
                    return;
                }
            }
        }
        speak(name + " app nahi mila");
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> list =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) {
            handle(list.get(0));
        }
    }

    @Override
    public void onError(int error) {
        status.setText("Phir se try karo (error " + error + ")");
    }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onPartialResults(Bundle partialResults) { }
    @Override public void onEvent(int eventType, Bundle params) { }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
    }
}
