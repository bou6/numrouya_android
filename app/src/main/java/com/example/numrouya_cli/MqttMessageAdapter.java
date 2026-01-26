package com.example.numrouya_cli;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MqttMessageAdapter extends RecyclerView.Adapter<MqttMessageAdapter.MessageViewHolder> {

    private List<MqttMessage> messages = new ArrayList<>();

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mqtt_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        MqttMessage message = messages.get(position);
        holder.topicTextView.setText(message.getTopic());
        holder.messageTextView.setText(message.getMessage());
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public void updateMessage(String topic, String message) {
        // Check if topic already exists
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getTopic().equals(topic)) {
                messages.get(i).setMessage(message);
                notifyItemChanged(i);
                return;
            }
        }
        // If topic doesn't exist, add new message
        messages.add(new MqttMessage(topic, message));
        notifyItemInserted(messages.size() - 1);
    }

    public void clear() {
        messages.clear();
        notifyDataSetChanged();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView topicTextView;
        TextView messageTextView;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            topicTextView = itemView.findViewById(R.id.topicTextView);
            messageTextView = itemView.findViewById(R.id.messageTextView);
        }
    }
}
