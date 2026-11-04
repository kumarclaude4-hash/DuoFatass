package com.duoshield.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.duoshield.app.BaseActivity;
import com.duoshield.app.ChatMediaActivity;
import com.duoshield.app.R;
import com.duoshield.app.call.CallActivity;
import com.duoshield.app.call.CallHistoryAdapter;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.db.CallRecord;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ContactDetailActivity extends BaseActivity {

    public static final String EXTRA_PARTNER_UID  = "partner_uid";
    public static final String EXTRA_PARTNER_NAME = "partner_name";
    public static final String EXTRA_CONV_ID      = "conversation_id";

    private static final int REQ_CALL_VOICE = 301;
    private static final int REQ_CALL_VIDEO = 302;

    private String pendingPartnerUid;
    private String pendingPartnerName;
    private String pendingConvId;  // F6: chatId for bilateral contact gate in calls rule
    private boolean pendingIsVideo;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contact_detail);

        Toolbar toolbar = findViewById(R.id.contactDetailToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Contact Info");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        String partnerUid  = getIntent().getStringExtra(EXTRA_PARTNER_UID);
        String partnerName = getIntent().getStringExtra(EXTRA_PARTNER_NAME);
        String convId      = getIntent().getStringExtra(EXTRA_CONV_ID);
        if (partnerName == null) partnerName = "Unknown";

        pendingPartnerUid  = partnerUid;
        pendingPartnerName = partnerName;
        pendingConvId      = convId;

        // Avatar initial
        TextView tvInitial = findViewById(R.id.tvContactInitial);
        TextView tvName    = findViewById(R.id.tvContactName);
        TextView tvUid     = findViewById(R.id.tvContactUid);

        tvName.setText(partnerName);
        tvInitial.setText(partnerName.substring(0, 1).toUpperCase(Locale.US));
        tvUid.setText(partnerUid != null ? partnerUid : "");

        // Chat button
        MaterialButton btnOpenChat = findViewById(R.id.btnOpenChat);
        String finalConvId      = convId;
        String finalPartnerUid  = partnerUid;
        String finalPartnerName = partnerName;
        btnOpenChat.setOnClickListener(v -> {
            Intent i = new Intent(this, ChatMediaActivity.class);
            if (finalConvId != null)     i.putExtra("conversation_id", finalConvId);
            if (finalPartnerUid != null) i.putExtra("partner_uid",     finalPartnerUid);
            i.putExtra("partner_name", finalPartnerName);
            startActivity(i);
        });

        // Voice call button
        MaterialButton btnVoiceCall = findViewById(R.id.btnVoiceCall);
        btnVoiceCall.setOnClickListener(v -> requestCallPermissions(false));

        // Video call button
        MaterialButton btnVideoCall = findViewById(R.id.btnVideoCall);
        btnVideoCall.setOnClickListener(v -> requestCallPermissions(true));

        executor = Executors.newSingleThreadExecutor();

        if (partnerUid != null) {
            loadStats(partnerUid);
        } else {
            showEmptyStats();
        }
    }

    private void loadStats(String partnerUid) {
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            List<CallRecord> records  = db.callHistoryDao().getByPartnerId(partnerUid);
            int totalCalls            = records.size();
            int totalDuration         = db.callHistoryDao().totalDurationByPartnerId(partnerUid);

            int missed   = 0, outgoing = 0, incoming = 0;
            long lastCallTs = 0;
            for (CallRecord r : records) {
                if (CallRecord.OUTCOME_MISSED.equals(r.outcome)
                        || CallRecord.OUTCOME_DECLINED.equals(r.outcome)) missed++;
                if (CallRecord.DIRECTION_OUTGOING.equals(r.direction)) outgoing++;
                else incoming++;
                if (r.startedAt > lastCallTs) lastCallTs = r.startedAt;
            }

            final int fMissed    = missed;
            final int fOut       = outgoing;
            final int fIn        = incoming;
            final long fLastTs   = lastCallTs;
            final int fDuration  = totalDuration;
            final List<CallRecord> fRecords = records;

            runOnUiThread(() -> {
                ((TextView) findViewById(R.id.tvTotalCalls)).setText(String.valueOf(totalCalls));
                ((TextView) findViewById(R.id.tvTotalDuration)).setText(formatDuration(fDuration));
                ((TextView) findViewById(R.id.tvMissedCalls)).setText(String.valueOf(fMissed));
                ((TextView) findViewById(R.id.tvOutgoingCount)).setText(fOut + " outgoing");
                ((TextView) findViewById(R.id.tvIncomingCount)).setText(fIn + " incoming");

                TextView tvLast = findViewById(R.id.tvLastCallTime);
                if (fLastTs > 0) {
                    tvLast.setText("Last: " + new SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                            .format(new Date(fLastTs)));
                    tvLast.setVisibility(View.VISIBLE);
                } else {
                    tvLast.setVisibility(View.GONE);
                }

                // Recent calls list
                RecyclerView rv = findViewById(R.id.rvContactCalls);
                TextView tvNoCallsYet = findViewById(R.id.tvNoCallsYet);
                if (fRecords.isEmpty()) {
                    rv.setVisibility(View.GONE);
                    tvNoCallsYet.setVisibility(View.VISIBLE);
                } else {
                    tvNoCallsYet.setVisibility(View.GONE);
                    rv.setVisibility(View.VISIBLE);
                    rv.setLayoutManager(new LinearLayoutManager(this));
                    rv.setNestedScrollingEnabled(false);
                    CallHistoryAdapter adapter = new CallHistoryAdapter(null);
                    adapter.setItems(fRecords);
                    rv.setAdapter(adapter);
                }
            });
        });
    }

    private void showEmptyStats() {
        ((TextView) findViewById(R.id.tvTotalCalls)).setText("0");
        ((TextView) findViewById(R.id.tvTotalDuration)).setText("0:00");
        ((TextView) findViewById(R.id.tvMissedCalls)).setText("0");
        ((TextView) findViewById(R.id.tvOutgoingCount)).setText("0 outgoing");
        ((TextView) findViewById(R.id.tvIncomingCount)).setText("0 incoming");
        findViewById(R.id.tvNoCallsYet).setVisibility(View.VISIBLE);
        findViewById(R.id.rvContactCalls).setVisibility(View.GONE);
    }

    private void requestCallPermissions(boolean isVideo) {
        pendingIsVideo = isVideo;
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO);
        if (isVideo && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (needed.isEmpty()) {
            launchCall(isVideo);
        } else {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]),
                    isVideo ? REQ_CALL_VIDEO : REQ_CALL_VOICE);
        }
    }

    private void launchCall(boolean isVideo) {
        if (pendingPartnerUid == null) {
            Toast.makeText(this, "Cannot start call — no contact ID", Toast.LENGTH_SHORT).show();
            return;
        }
        String myUid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        Intent i = new Intent(this, com.duoshield.app.call.CallActivity.class);
        // CallActivity lives in its own task (see AndroidManifest taskAffinity).
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(com.duoshield.app.call.CallActivity.EXTRA_IS_CALLER,    true);
        i.putExtra(com.duoshield.app.call.CallActivity.EXTRA_IS_VIDEO,     isVideo);
        i.putExtra(com.duoshield.app.call.CallActivity.EXTRA_MY_UID,       myUid);
        i.putExtra(com.duoshield.app.call.CallActivity.EXTRA_CALLEE_ID,    pendingPartnerUid);
        i.putExtra(com.duoshield.app.call.CallActivity.EXTRA_PARTNER_NAME, pendingPartnerName);
        // F6: pass chatId so CallManager can include it in the call doc for bilateral gate
        if (pendingConvId != null) i.putExtra(com.duoshield.app.call.CallActivity.EXTRA_CHAT_ID, pendingConvId);
        startActivity(i);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALL_VOICE || requestCode == REQ_CALL_VIDEO) {
            boolean audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            if (!audioGranted) {
                Toast.makeText(this, "Microphone permission required for calls", Toast.LENGTH_LONG).show();
                return;
            }
            boolean isVideo = (requestCode == REQ_CALL_VIDEO);
            boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            if (isVideo && !cameraGranted) {
                Toast.makeText(this, "Camera denied — starting audio-only call", Toast.LENGTH_SHORT).show();
                launchCall(false);
            } else {
                launchCall(isVideo);
            }
        }
    }

    private String formatDuration(int seconds) {
        if (seconds <= 0) return "0:00";
        int h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }
}
