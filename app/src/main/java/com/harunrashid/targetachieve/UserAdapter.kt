package com.harunrashid.targetachieve;

import android.graphics.Color;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.FirebaseFirestore;

import java.util.List;

public class UserAdapter extends RecyclerView.Adapter<UserAdapter.UserViewHolder> {

    private List<UserModel> userList;
    private FirebaseFirestore db = FirebaseFirestore.getInstance();

    public UserAdapter(List<UserModel> userList) {
        this.userList = userList;
    }

    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user, parent, false);
        return new UserViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        UserModel user = userList.get(position);
        long currentTime = System.currentTimeMillis();

        long expiryTime = Math.max(user.getTrialExpiryMillis(), user.getSubscriptionExpiryMillis());
        boolean isActive = expiryTime > currentTime;

        holder.tvUserId.setText("ID: " + user.getUserId());

        if (isActive) {
            holder.tvUserStatus.setText("Status: ACTIVE 🟢");
            holder.tvUserStatus.setTextColor(Color.parseColor("#388E3C"));
        } else {
            holder.tvUserStatus.setText("Status: EXPIRED / INACTIVE 🔴");
            holder.tvUserStatus.setTextColor(Color.RED);
        }

        String dateString = DateFormat.format("dd/MM/yyyy hh:mm a", expiryTime).toString();
        holder.tvExpiryDate.setText("Expires on: " + dateString);

        holder.btnExtendValidity.setOnClickListener(v -> {
            long newExpiry;
            if (isActive) {
                newExpiry = expiryTime + (30L * 24 * 60 * 60 * 1000);
            } else {
                newExpiry = currentTime + (30L * 24 * 60 * 60 * 1000);
            }

            db.collection("users").document(user.getUserId())
                    .update("subscriptionExpiryMillis", newExpiry)
                    .addOnSuccessListener(aVoid -> Toast.makeText(v.getContext(), "Updated +30 days!", Toast.LENGTH_SHORT).show())
                    .addOnFailureListener(e -> Toast.makeText(v.getContext(), "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        });
    }

    @Override
    public int getItemCount() {
        return userList.size();
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        TextView tvUserId, tvUserStatus, tvExpiryDate;
        Button btnExtendValidity;

        public UserViewHolder(@NonNull View itemView) {
            super(itemView);
            tvUserId = itemView.findViewById(R.id.tvUserId);
            tvUserStatus = itemView.findViewById(R.id.tvUserStatus);
            tvExpiryDate = itemView.findViewById(R.id.tvExpiryDate);
            btnExtendValidity = itemView.findViewById(R.id.btnExtendValidity);
        }
    }
}