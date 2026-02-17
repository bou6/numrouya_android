
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
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
        private BroadcastReceiver networkReceiver;
     // MQTT Configuration - Update these values for your broker
    private static final String MQTT_BROKER_HOST = "2c45a082862d4b60b6dd63904ff8c728.s1.eu.hivemq.cloud";
    private static final int MQTT_BROKER_PORT = 8883;
    private static final String CLIENT_ID = "ClientNumRouya"; // Unique client ID for MQTT connection
    private static final String MQTT_USERNAME = "Achbou6";
    private static final String PASSWORD = "AchMqt26081991@";
    private static final String SUBSCRIBE_TOPIC = "#"; // Subscribe to all topics

    private Mqtt3AsyncClient mqttClient;
    private MqttMessageAdapter adapter;
    private TextView statusTextView;
    private volatile boolean isManualConnectInProgress = false;
    private boolean isActivityDestroying = false;
    private final Handler reconnectWatchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable reconnectWatchdogRunnable = this::recoverIfReconnectStalled;
    private static final long RECONNECT_STALL_TIMEOUT_MS = 15000;
    private volatile boolean isReconnectWatchdogScheduled = false;

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
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MqttMessageAdapter();
        recyclerView.setAdapter(adapter);

        // Initialize MQTT client
        initializeMqttClient();

        // Register network change receiver
        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                boolean isConnected = activeNetwork != null && activeNetwork.isConnected();
                if (isConnected && mqttClient != null) {
                    switch (mqttClient.getState()) {
                        case CONNECTED:
                            runOnUiThread(() -> {
                                statusTextView.setText("Network restored. Already connected to MQTT.");
                            });
                            break;
                        case CONNECTING:
                            runOnUiThread(() -> {
                                statusTextView.setText("Network restored. MQTT is connecting...");
                            });
                            break;
                        case DISCONNECTED:
                            runOnUiThread(() -> {
                                statusTextView.setText("Network restored. Trying MQTT connection...");
                            });
                            connectToMqttBroker();
                            break;
                        case DISCONNECTED_RECONNECT:
                        case CONNECTING_RECONNECT:
                            runOnUiThread(() -> {
                                statusTextView.setText("Network restored. Reconnecting to MQTT (automatic)...");
                            });
                            scheduleReconnectWatchdog();
                            break;
                        default:
                            runOnUiThread(() -> {
                                statusTextView.setText("Undefined state, restart the app");
                            });
                            break;
                    }
                }
            }
        };
        registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }


// ############ make client Id unique by appending a random number to it, to avoid conflicts with other clients using the same ID ############
    private void initializeMqttClient() {
        mqttClient = MqttClient.builder()
            .identifier(CLIENT_ID)
            .serverHost(MQTT_BROKER_HOST)
            .serverPort(MQTT_BROKER_PORT)
            .sslWithDefaultConfig()
            //.automaticReconnect()
            // .initialDelay(1, TimeUnit.SECONDS)
            //.maxDelay(10, TimeUnit.SECONDS)
            //.applyAutomaticReconnect()
            .useMqttVersion3()
            .addConnectedListener(context -> {
                Log.d(">>>>>MainActivity", "Connected to MQTT broker");
                cancelReconnectWatchdog();
                runOnUiThread(() -> {
                    statusTextView.setText("Connected to MQTT broker");
                    Toast.makeText(MainActivity.this, "Connected successfully", Toast.LENGTH_SHORT).show();
                });
                subscribeToTopics();
            })
            .addDisconnectedListener(context -> {
                Throwable cause = context.getCause();
                Log.d(">>>>>MainActivity", "Disconnected from MQTT broker: " + (cause != null ? cause.getMessage() : "Unknown cause"));
                runOnUiThread(() -> {
                    if (mqttClient != null) {
                        switch (mqttClient.getState()) {
                            case DISCONNECTED_RECONNECT:
                            case CONNECTING_RECONNECT:
                                Log.d(">>>>>1- MainActivity", "Reconnecting to MQTT broker...");
                                statusTextView.setText("Reconnecting to MQTT broker...");
                                scheduleReconnectWatchdog();
                                break;
                            default:
                                if (cause != null && cause.getMessage() != null) {
                                    if (isNotAuthorizedError(cause)) {
                                        Log.d(">>>>>2- MainActivity", "Disconnected: NOT_AUTHORIZED (check MQTT username/password in app config)");
                                        statusTextView.setText("MQTT auth failed (NOT_AUTHORIZED). Check username/password.");
                                        return;
                                    }
                                    Log.d(">>>>>3- MainActivity", "Disconnected: " + cause.getMessage());
                                    statusTextView.setText("Disconnected: " + cause.getMessage());
                                } else {
                                    Log.d(">>>>>3- MainActivity", "Disconnected from MQTT broker");
                                    statusTextView.setText("Disconnected from MQTT broker");
                                }
                                break;
                        }
                    } else {
                        statusTextView.setText("Disconnected from MQTT broker");
                    }
                });
                if (mqttClient != null && DISCONNECTED == mqttClient.getState() && !isNotAuthorizedError(cause)) {
                    connectToMqttBroker();
                }
            })
            .buildAsync();

        connectToMqttBroker();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(">>>>>0 ", "onStart called");
        if (mqttClient == null) {
            Log.d(">>>>>0.1 ", "MQTT client is null in onStart, skipping connection attempt");
            return;
        }
        if (mqttClient != null) {
            Log.d(">>>>>1.0 ", "MQTT client is not null in onStart, skipping connection attempt");
            switch (mqttClient.getState()) {
                case DISCONNECTED:
                    // write a debug message to logcat
                    Log.d(">>>>>1.1 ", "App resumed. Reconnecting to MQTT...");
                    runOnUiThread(() -> statusTextView.setText("App resumed. Reconnecting to MQTT..."));
                    connectToMqttBroker();
                    break;
                case CONNECTING:
                    Log.d(">>>>>2", "App resumed. MQTT is connecting...");
                    runOnUiThread(() -> statusTextView.setText("MQTT is connecting..."));
                    break;
                case CONNECTING_RECONNECT:
                    Log.d(">>>>>3", "App resumed. MQTT is reconnecting...");
                    runOnUiThread(() -> statusTextView.setText("MQTT is reconnecting..."));
                    scheduleReconnectWatchdog();
                    break;
                case CONNECTED:
                    Log.d(">>>>>4 ", "App resumed. Already connected to MQTT.");
                    runOnUiThread(() -> statusTextView.setText("Connected to MQTT broker"));
                    break;
                case DISCONNECTED_RECONNECT:
                    Log.d(">>>>>5 ", "App resumed. MQTT is in disconnected-reconnect state.");
                    runOnUiThread(() -> statusTextView.setText("MQTT is reconnecting..."));
                    scheduleReconnectWatchdog();
                    break;
                default:
                    Log.d(">>>>>6 ", "Default");
                    break;
            }
        }
    }

    private void connectToMqttBroker() {
        if (mqttClient == null) {
            return;
        }

        if (MQTT_USERNAME.trim().isEmpty() || PASSWORD.trim().isEmpty()) {
            runOnUiThread(() -> statusTextView.setText("MQTT config error: username/password is empty"));
            return;
        }

        switch (mqttClient.getState()) {
            case CONNECTED:
                Log.d(">>>>>300.1 ", "App resumed. Already connected to MQTT.");
            case CONNECTING:
                Log.d(">>>>>300.2 ", "App resumed. MQTT is connecting...");
            case DISCONNECTED_RECONNECT:
                Log.d(">>>>>300.3 ", "App resumed. MQTT is in disconnected-reconnect state.");
            case CONNECTING_RECONNECT:
                Log.d(">>>>>300.4 ", "App resumed. MQTT is reconnecting...");
            default:
                break;
        }

        if (isManualConnectInProgress) {
            Log.d(">>>>>300.5 ", "App resumed. Manual connect in progress.");
            return;
        }

        isManualConnectInProgress = true;

        mqttClient.connectWith()
                .simpleAuth()
                .username(MQTT_USERNAME)
                .password(PASSWORD.getBytes(StandardCharsets.UTF_8))
                .applySimpleAuth()
                .cleanSession(false) // Keep session to receive missed messages
                .keepAlive(60)
                .send()
                .whenComplete((connAck, throwable) -> {
                    isManualConnectInProgress = false;
                    if (throwable != null) {
                        runOnUiThread(() -> {
                            if (isNotAuthorizedError(throwable)) {
                                statusTextView.setText("MQTT auth failed (NOT_AUTHORIZED). Check username/password.");
                            } else {
                                statusTextView.setText("Failed to connect: " + throwable.getMessage());
                            }
                            Toast.makeText(MainActivity.this, "Connection failed", Toast.LENGTH_SHORT).show();
                        });
                    }
                    // Success is handled by addConnectedListener
                });
    }

    private boolean isNotAuthorizedError(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null) {
            return false;
        }
        return throwable.getMessage().toUpperCase(Locale.ROOT).contains("NOT_AUTHORIZED");
    }

    private void scheduleReconnectWatchdog() {
        if (isReconnectWatchdogScheduled) {
            return;
        }
        isReconnectWatchdogScheduled = true;
        reconnectWatchdogHandler.postDelayed(reconnectWatchdogRunnable, RECONNECT_STALL_TIMEOUT_MS);
    }

    private void cancelReconnectWatchdog() {
        reconnectWatchdogHandler.removeCallbacks(reconnectWatchdogRunnable);
        isReconnectWatchdogScheduled = false;
    }

    private void recoverIfReconnectStalled() {
        isReconnectWatchdogScheduled = false;
        Log.d(">>>>>100", "Reconnection stalled. Checking...");
        if (isActivityDestroying || mqttClient == null) {
            return;
        }

        switch (mqttClient.getState()) {
            case DISCONNECTED_RECONNECT:
            case CONNECTING_RECONNECT:
                Log.w(">>>>>MainActivity", "Reconnect appears stalled. Forcing fresh MQTT connect attempt.");
                runOnUiThread(() -> statusTextView.setText("Reconnect stalled. Retrying fresh MQTT connection..."));
                forceRestartMqttConnection();
                scheduleReconnectWatchdog();
                break;
            default:
                break;
        }
    }

    private void forceRestartMqttConnection() {
        Log.d(">>>>>200", "Forece restarting MQTT connection...");
        if (mqttClient == null || isActivityDestroying || isManualConnectInProgress) {
            return;
        }

        isManualConnectInProgress = true;
        mqttClient.disconnect().whenComplete((ignored, disconnectThrowable) -> {
            isManualConnectInProgress = false;
            if (disconnectThrowable != null) {
                Log.w(">>>>>MainActivity", "Force disconnect before reconnect failed: " + disconnectThrowable.getMessage());
            }
            connectToMqttBroker();
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
                        adapter.updateMessage(topic, payload);
                    });
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    runOnUiThread(() -> {
                        if (throwable == null) {
                            statusTextView.setText("Subscribed to topics. Waiting for messages...");
                            Toast.makeText(MainActivity.this, "Subscribed successfully", Toast.LENGTH_SHORT).show();
                        } else {
                            statusTextView.setText("Failed to subscribe: " + throwable.getMessage());
                            Toast.makeText(MainActivity.this, "Subscription failed: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActivityDestroying = true;
        cancelReconnectWatchdog();
        if (mqttClient != null) {
            mqttClient.disconnect();
        }
        if (networkReceiver != null) {
            unregisterReceiver(networkReceiver);
        }
    }
}
