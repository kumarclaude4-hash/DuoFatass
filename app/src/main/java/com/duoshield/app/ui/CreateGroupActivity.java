package com.duoshield.app.ui;

import com.duoshield.app.util.LogRedact;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.duoshield.app.BaseActivity;
import com.duoshield.app.GroupChatActivity;
import com.duoshield.app.R;
import com.duoshield.app.crypto.GroupCipherHelper;
import com.duoshield.app.crypto.signal.SignalCipherHelper;
import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Contact;
import com.duoshield.app.models.Group;
import com.duoshield.app.models.GroupMember;
import com.duoshield.app.util.FirebaseCostGuard;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Allows the user to create a new group conversation.
 *
 * <p>Flow:
 * <ol>
 *   <li>Load contacts (paired contacts) from Room on a background thread.</li>
 *   <li>User enters a group name and selects 1+ contacts.</li>
 *   <li>On "Create":
 *     <ul>
 *       <li>Generate an AES-256-GCM group key with {@link GroupCipherHelper}.</li>
 *       <li>Write the group doc to Firestore {@code /groups/{id}}.</li>
 *       <li>Encrypt the group key with each member's Signal session and write to
 *           {@code /groups/{id}/keys/{memberUid}}.</li>
 *       <li>Save group + members + own plaintext key to Room (v12 schema).</li>
 *       <li>Launch {@link GroupChatActivity}.</li>
 *     </ul>
 *   </li>
 * </ol>
 */
public class CreateGroupActivity extends BaseActivity {

    private static final String TAG = "CreateGroupActivity";

    private EditText            etGroupName;
    private ContactSelectAdapter contactAdapter;

    private String myUid;
    private AppDatabase        localDb;
    private FirebaseFirestore  db;
    private ExecutorService    executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_group);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("New Group");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        myUid = prefs.getString("my_uid", null);

        localDb  = AppDatabase.getInstance(this);
        db       = FirebaseFirestore.getInstance();
        executor = Executors.newSingleThreadExecutor();

        etGroupName = findViewById(R.id.et_group_name);

        RecyclerView recycler = findViewById(R.id.recycler_contacts);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        contactAdapter = new ContactSelectAdapter();
        recycler.setAdapter(contactAdapter);

        findViewById(R.id.btn_create).setOnClickListener(v -> attemptCreate());

        android.widget.TextView tvNoContacts = findViewById(R.id.tv_no_contacts);
        executor.execute(() -> {
            List<Contact> contacts = localDb.contactDao().getAll();
            runOnUiThread(() -> {
                contactAdapter.setContacts(contacts);
                if (tvNoContacts != null) {
                    tvNoContacts.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
                }
            });
        });
    }

    private void attemptCreate() {
        String name = etGroupName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            etGroupName.setError("Enter a group name");
            etGroupName.requestFocus();
            return;
        }
        List<Contact> selected = contactAdapter.getSelected();
        if (selected.isEmpty()) {
            Toast.makeText(this, "Select at least one member", Toast.LENGTH_SHORT).show();
            return;
        }
        if (myUid == null) {
            Toast.makeText(this, "Not signed in", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!SignalKeyManager.isInitialized(this)) {
            Toast.makeText(this, "Signal keys not ready — wait a moment", Toast.LENGTH_SHORT).show();
            return;
        }

        findViewById(R.id.btn_create).setEnabled(false);
        executor.execute(() -> createGroup(name, selected));
    }

    private void createGroup(String name, List<Contact> members) {
        String groupId  = UUID.randomUUID().toString();
        String groupKey;
        try {
            groupKey = GroupCipherHelper.generateGroupKey();
        } catch (Exception e) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Key generation failed", Toast.LENGTH_SHORT).show();
                findViewById(R.id.btn_create).setEnabled(true);
            });
            return;
        }

        // ── 1. Write Firestore group doc ────────────────────────────────────
        List<String> memberUids = new ArrayList<>();
        memberUids.add(myUid);
        for (Contact c : members) memberUids.add(c.uid);

        Map<String, Object> groupDoc = new HashMap<>();
        groupDoc.put("name",      name);
        groupDoc.put("createdBy", myUid);
        groupDoc.put("createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
        groupDoc.put("members",   memberUids);

        FirebaseCostGuard guard = FirebaseCostGuard.getInstance(this);
        if (guard.reserveWrite(1)) {
            db.collection("groups").document(groupId)
              .set(groupDoc, SetOptions.merge());
        }

        // ── 2. Encrypt group key for each member via Signal + write to Firestore ──
        for (Contact c : members) {
            try {
                SignalCipherHelper.EncryptResult r =
                        SignalCipherHelper.encrypt(this, c.uid, groupKey);
                Map<String, Object> keyDoc = new HashMap<>();
                keyDoc.put("encryptedKey", r.ciphertextB64);
                keyDoc.put("sigType",      r.sigType);
                keyDoc.put("senderUid",    myUid);
                if (guard.reserveWrite(1)) {
                    db.collection("groups").document(groupId)
                      .collection("keys").document(c.uid)
                      .set(keyDoc);
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "Failed to encrypt group key for " + LogRedact.uid(c.uid) + " (" + LogRedact.error(e) + ")");
            }
        }

        // ── BUG-S01 fix: Write the creator's own encrypted key so they can
        //    re-decrypt messages after a reinstall (their own Signal session
        //    lets them decrypt a message addressed to themselves). ──────────────
        try {
            SignalCipherHelper.EncryptResult r =
                    SignalCipherHelper.encrypt(this, myUid, groupKey);
            Map<String, Object> myKeyDoc = new HashMap<>();
            myKeyDoc.put("encryptedKey", r.ciphertextB64);
            myKeyDoc.put("sigType",      r.sigType);
            myKeyDoc.put("senderUid",    myUid);
            if (guard.reserveWrite(1)) {
                db.collection("groups").document(groupId)
                  .collection("keys").document(myUid)
                  .set(myKeyDoc);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "Failed to encrypt group key for creator " + LogRedact.uid(myUid) + " (" + LogRedact.error(e) + ")");
        }

        // ── 3. Persist group + members + own plaintext key to Room ──────────
        Group group    = new Group(groupId, name, myUid);
        group.groupKey = groupKey;

        List<GroupMember> roomMembers = new ArrayList<>();
        String myDisplayName;
        com.google.firebase.auth.FirebaseUser me = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (me != null && me.getDisplayName() != null && !me.getDisplayName().isEmpty()) {
            myDisplayName = me.getDisplayName();
        } else if (me != null && me.getEmail() != null) {
            myDisplayName = me.getEmail();
        } else {
            myDisplayName = myUid;
        }
        roomMembers.add(new GroupMember(groupId, myUid, myDisplayName));
        for (Contact c : members) {
            roomMembers.add(new GroupMember(groupId, c.uid, c.displayName));
        }

        localDb.groupDao().insertGroup(group);
        localDb.groupDao().insertMembers(roomMembers);

        // ── 4. Launch GroupChatActivity ─────────────────────────────────────
        runOnUiThread(() -> {
            Intent intent = new Intent(this, GroupChatActivity.class);
            intent.putExtra("group_id", groupId);
            startActivity(intent);
            finish();
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) executor.shutdownNow();
    }

    // ── Inner adapter ─────────────────────────────────────────────────────────

    private static class ContactSelectAdapter
            extends RecyclerView.Adapter<ContactSelectAdapter.VH> {

        private final List<Contact>    items    = new ArrayList<>();
        private final Set<Integer>     selected = new HashSet<>();

        /** Teal palette to colour avatar initials. */
        private static final int[] COLORS = {
            0xFF3A2898, 0xFF4A38B0, 0xFF6654E8, 0xFF5040C0, 0xFF2A1880
        };

        void setContacts(List<Contact> newContacts) {
            final List<Contact> oldItems = new ArrayList<>(items);
            items.clear();
            selected.clear();
            items.addAll(newContacts);
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return oldItems.size(); }
                @Override public int getNewListSize() { return items.size(); }
                @Override public boolean areItemsTheSame(int o, int n) {
                    return oldItems.get(o).uid != null
                            && oldItems.get(o).uid.equals(items.get(n).uid);
                }
                @Override public boolean areContentsTheSame(int o, int n) {
                    Contact a = oldItems.get(o), b = items.get(n);
                    String na = a.displayName != null ? a.displayName : "";
                    String nb = b.displayName != null ? b.displayName : "";
                    return na.equals(nb);
                }
            });
            diff.dispatchUpdatesTo(this);
        }

        List<Contact> getSelected() {
            List<Contact> out = new ArrayList<>();
            for (int i : selected) {
                if (i < items.size()) out.add(items.get(i));
            }
            return out;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_member_select, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Contact c    = items.get(pos);
            boolean chk  = selected.contains(pos);
            String  name = c.displayName != null ? c.displayName : "Unknown";

            h.name.setText(name);
            h.uid.setText(c.uid.length() > 12 ? c.uid.substring(0, 12) + "…" : c.uid);
            h.checkBox.setChecked(chk);

            String initial = name.isEmpty() ? "?" : String.valueOf(name.charAt(0)).toUpperCase();
            h.initial.setText(initial);
            int color = COLORS[Math.abs(name.hashCode()) % COLORS.length];
            h.initial.getBackground().setTint(color);

            h.itemView.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (selected.contains(p)) selected.remove(p);
                else selected.add(p);
                notifyItemChanged(p);
            });
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            CheckBox checkBox;
            TextView initial, name, uid;
            VH(@NonNull View v) {
                super(v);
                checkBox = v.findViewById(R.id.checkbox);
                initial  = v.findViewById(R.id.tv_initial);
                name     = v.findViewById(R.id.tv_name);
                uid      = v.findViewById(R.id.tv_uid);
            }
        }
    }
}
