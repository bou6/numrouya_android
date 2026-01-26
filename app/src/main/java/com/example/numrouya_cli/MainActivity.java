
package com.example.numrouya_cli;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import android.os.Bundle;
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

public class MainActivity extends AppCompatActivity {
    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            boolean isConnected = activeNetwork != null && activeNetwork.isConnected();
            if (isConnected && (mqttClient != null && !mqttClient.getState().isConnected())) {
                runOnUiThread(() -> {
                    statusTextView.setText("Network restored. Reconnecting...");
                });
                connectToMqttBroker();
            }
        }
    };

    // MQTT Configuration - Update these values for your broker
    private static final String MQTT_BROKER_HOST = "2c45a082862d4b60b6dd63904ff8c728.s1.eu.hivemq.cloud";
    private static final int MQTT_BROKER_PORT = 8883;
    private static final String CLIENT_ID = "Achbou6";
    private static final String PASSWORD = "AchMqt26081991@";
    private static final String SUBSCRIBE_TOPIC = "#"; // Subscribe to all topics

    private Mqtt3AsyncClient mqttClient;
    private MqttMessageAdapter adapter;
    private TextView statusTextView;

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
        registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    private void initializeMqttClient() {
        mqttClient = MqttClient.builder()
            .useMqttVersion3()
            .identifier(CLIENT_ID)
            .serverHost(MQTT_BROKER_HOST)
            .serverPort(MQTT_BROKER_PORT)
            .sslWithDefaultConfig()
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener(context -> runOnUiThread(this::subscribeToTopics))
            .buildAsync();

        connectToMqttBroker();
    }

    private void connectToMqttBroker() {
        mqttClient.connectWith()
                .simpleAuth()
                .username(CLIENT_ID)
                .password(PASSWORD.getBytes(StandardCharsets.UTF_8))
                .applySimpleAuth()
                .send()
                .whenComplete((connAck, throwable) -> {
                    if (throwable == null) {
                        runOnUiThread(() -> {
                            statusTextView.setText("Connected to MQTT broker");
                            Toast.makeText(MainActivity.this, "Connected successfully", Toast.LENGTH_SHORT).show();
                        });
                        subscribeToTopics();
                    } else {
                        runOnUiThread(() -> {
                            statusTextView.setText("Failed to connect: " + throwable.getMessage());
                            Toast.makeText(MainActivity.this, "Connection failed", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    private void subscribeToTopics() {
        mqttClient.subscribeWith()
                .topicFilter(SUBSCRIBE_TOPIC)
                .qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_MOST_ONCE)
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
                        } else {
                            statusTextView.setText("Failed to subscribe: " + throwable.getMessage());
                        }
                    });
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mqttClient != null) {
            mqttClient.disconnect();
        }

        // Unregister network change receiver
        unregisterReceiver(networkReceiver);
    }
}
