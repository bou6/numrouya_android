
package com.example.numrouya_cli;
import static com.hivemq.client.mqtt.MqttClientState.DISCONNECTED;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    // MQTT Configuration - supplied from Gradle properties into BuildConfig
    private static final int MQTT_BROKER_PORT = 8883;
    private static final String CLIENT_ID_PREFIX = "ClientNumRouya-";
    private static final String PREFS_NAME = "numrouya_prefs";
    private static final String PREF_CLIENT_ID = "mqtt_client_id";
    private static final String SUBSCRIBE_TOPIC = "#"; // Subscribe to all topics
    private Mqtt3AsyncClient mqttClient;
    private MqttMessageAdapter adapter;
    private TextView statusTextView;
    private ProgressBar waitingProgressBar;
    private static final long WAITING_ANIMATION_TIMEOUT_MS = 20_000L;
    private final Handler waitingHandler = new Handler(Looper.getMainLooper());
    private boolean hasReceivedFirstMessage = false;
    private final Runnable stopWaitingAnimationRunnable = this::handleWaitingAnimationTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialize UI components
        statusTextView = findViewById(R.id.statusTextView);
        waitingProgressBar = findViewById(R.id.waitingProgressBar);
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MqttMessageAdapter();
        recyclerView.setAdapter(adapter);
        startWaitingAnimation();

        if (!hasValidMqttConfiguration()) {
            hideWaitingAnimation();
            statusTextView.setText(R.string.disconnected);
            Toast.makeText(this, "Missing MQTT configuration. Set MQTT_BROKER_HOST, MQTT_USERNAME, and MQTT_PASSWORD in Gradle properties.", Toast.LENGTH_LONG).show();
            return;
        }

        // Initialize MQTT client
        initializeMqttClient();
    }

    private boolean hasValidMqttConfiguration() {
        return !BuildConfig.MQTT_BROKER_HOST.trim().isEmpty()
                && !BuildConfig.MQTT_USERNAME.trim().isEmpty()
                && !BuildConfig.MQTT_PASSWORD.trim().isEmpty();
    }

    private void initializeMqttClient() {
        final String clientId = getOrCreateClientId();
        Log.d("Initialization", "Using MQTT client ID: " + clientId);
        mqttClient = MqttClient.builder()
            .identifier(clientId)
            .serverHost(BuildConfig.MQTT_BROKER_HOST)
            .serverPort(MQTT_BROKER_PORT)
            .sslWithDefaultConfig()
            .useMqttVersion3()
            .addConnectedListener(context -> {
                Log.d("Initialization", "Connected successfully");
                runOnUiThread(() -> {
                    statusTextView.setText(R.string.connected);
                });
                subscribeToTopics();
            })
            .addDisconnectedListener(context -> {
                Throwable cause = context.getCause();
                Log.d("Initialization", "Disconnected :" + (cause != null ? cause.getMessage() : "Unknown cause"));
                if (mqttClient != null) {
                    Log.d("Initialization", "Current MQTT state: " + mqttClient.getState());
                    switch (mqttClient.getState()) {
                        case DISCONNECTED_RECONNECT:
                        case CONNECTING_RECONNECT:
                            Log.d("Initialization", "Reconnecting...");
                            runOnUiThread(() -> statusTextView.setText(R.string.reconnecting));
                            break;
                        default:
                            Log.d("Initialization", "Disconnected "+ (cause != null ? "with cause: " + cause.getMessage() : "without specific cause"));
                            runOnUiThread(() -> statusTextView.setText(R.string.disconnected));
                            connectToMqttBroker();
                            break;
                    }
                }
            })
            .buildAsync();

        connectToMqttBroker();
    }

    private String getOrCreateClientId() {
        String storedClientId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_CLIENT_ID, null);

        if (storedClientId != null && !storedClientId.isEmpty()) {
            return storedClientId;
        }

        String newClientId = CLIENT_ID_PREFIX + UUID.randomUUID();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_CLIENT_ID, newClientId)
                .apply();
        return newClientId;
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d("Start", "started");

        if (mqttClient == null) {
            if (!hasValidMqttConfiguration()) {
                runOnUiThread(() -> statusTextView.setText(R.string.disconnected));
                return;
            }
            initializeMqttClient();
            return;
        }

        switch (mqttClient.getState()) {
            case DISCONNECTED:
                // write a debug message to logcat
                Log.d("Start", "Disconnected");
                runOnUiThread(() -> statusTextView.setText(R.string.disconnected));
                connectToMqttBroker();
                break;
            case CONNECTED:
                Log.d("Start", "Already connected.");
                runOnUiThread(() -> statusTextView.setText(R.string.connected));
                break;
            /////// ########### these cases to be removed, as the client should handle reconnection automatically, and we will rely on the watchdog to recover if reconnection stalls
            case CONNECTING:
                Log.d("Start", "Connecting ...");
                runOnUiThread(() -> statusTextView.setText(R.string.connecting));
                break;
            case CONNECTING_RECONNECT:
                Log.d("Start", "Reconnecting ...");
                runOnUiThread(() -> statusTextView.setText(R.string.reconnecting));
                break;
            case DISCONNECTED_RECONNECT:
                Log.d("Start", "Reconnecting ...");
                runOnUiThread(() -> statusTextView.setText(R.string.reconnecting));
                break;
            default:
                Log.d("Start", "Default");
                break;
        }
    }

    private void connectToMqttBroker() {

        mqttClient.connectWith()
                .simpleAuth()
            .username(BuildConfig.MQTT_USERNAME)
            .password(BuildConfig.MQTT_PASSWORD.getBytes(StandardCharsets.UTF_8))
                .applySimpleAuth()
                .cleanSession(false) // Keep session to receive missed messages
                .keepAlive(60)
                .send()
                .whenComplete((connAck, throwable) -> {
                    if (throwable != null) {
                            runOnUiThread(() -> statusTextView.setText(getString(R.string.disconnected, throwable.getMessage())));
                    }
                    else{
                        runOnUiThread(() -> statusTextView.setText(R.string.connected));
                    }
                });
    }

    private void subscribeToTopics() {
        mqttClient.subscribeWith()
                .topicFilter(SUBSCRIBE_TOPIC)
                .qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_LEAST_ONCE)
                .callback(publish -> {
                    String topic = publish.getTopic().toString();
                    String payload = publish.getPayload()
                        .map(buf -> {
                            byte[] arr = new byte[buf.remaining()];
                            buf.get(arr);
                            return new String(arr, StandardCharsets.UTF_8);
                        })
                        .orElse("");
                    runOnUiThread(() -> {
                        if (!hasReceivedFirstMessage) {
                            hasReceivedFirstMessage = true;
                            hideWaitingAnimation();
                        }
                        adapter.updateMessage(topic, payload);
                    });
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    runOnUiThread(() -> {
                        if (throwable == null) {
                            Log.d("Subscription", "Subscribed to topic: " + SUBSCRIBE_TOPIC);
                        } else {
                            Toast.makeText(MainActivity.this, getString(R.string.subscription_failed, throwable.getMessage()), Toast.LENGTH_LONG).show();
                        }
                    });
                });
    }

    private void startWaitingAnimation() {
        hasReceivedFirstMessage = false;
        waitingProgressBar.setVisibility(View.VISIBLE);
        waitingHandler.removeCallbacks(stopWaitingAnimationRunnable);
        waitingHandler.postDelayed(stopWaitingAnimationRunnable, WAITING_ANIMATION_TIMEOUT_MS);
    }

    private void hideWaitingAnimation() {
        waitingHandler.removeCallbacks(stopWaitingAnimationRunnable);
        waitingProgressBar.setVisibility(View.GONE);
    }

    private void handleWaitingAnimationTimeout() {
        if (hasReceivedFirstMessage) {
            hideWaitingAnimation();
            return;
        }

        hideWaitingAnimation();
        statusTextView.setText(R.string.no_message_received_timeout);
        Toast.makeText(this, R.string.no_message_received_timeout, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        waitingHandler.removeCallbacks(stopWaitingAnimationRunnable);
        if (mqttClient != null) {
            mqttClient.disconnect();
        }
    }
}
