package com.duoshield.app.util;

import android.content.Context;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;
import java.util.ArrayList;
import java.util.List;

public class ReadReceiptHelper {

    public static void markAllRead(Context context, String convId, String myUid) {
        if (context == null || convId == null || myUid == null) return;
        FirebaseCostGuard guard = FirebaseCostGuard.getInstance(context);
        // Two bounded status queries. Reserve their minimum read cost before
        // sending them; any additional returned documents are accounted below.
        if (!guard.reserveRead(2)) return;

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        com.google.android.gms.tasks.Task<QuerySnapshot> sentTask =
                db.collection("chats").document(convId)
                  .collection("messages")
                  .whereEqualTo("status", "sent")
                  .limit(500)
                  .get();

        com.google.android.gms.tasks.Task<QuerySnapshot> deliveredTask =
                db.collection("chats").document(convId)
                  .collection("messages")
                  .whereEqualTo("status", "delivered")
                  .limit(500)
                  .get();

        Tasks.whenAllSuccess(sentTask, deliveredTask)
                .addOnSuccessListener(results -> {
                    List<DocumentSnapshot> docs = new ArrayList<>();
                    for (Object result : results) {
                        QuerySnapshot query = (QuerySnapshot) result;
                        docs.addAll(query.getDocuments());
                    }
                    if (docs.size() > 2) guard.recordReads(docs.size() - 2);

                    List<DocumentSnapshot> unreadDocs = new ArrayList<>();
                    for (DocumentSnapshot doc : docs) {
                        if (!myUid.equals(doc.getString("sender"))) unreadDocs.add(doc);
                    }

                    // The conversation unread counter is part of this operation.
                    // Reserve every document status update plus that one write
                    // before committing any batch; do not clear the badge when
                    // the status updates could not be persisted.
                    if (!guard.reserveWrite(unreadDocs.size() + 1)) return;

                    List<Task<Void>> commits = new ArrayList<>();
                    WriteBatch batch = db.batch();
                    int count = 0;
                    for (DocumentSnapshot doc : unreadDocs) {
                        batch.update(doc.getReference(), "status", "read");
                        if (++count == 450) {
                            commits.add(batch.commit());
                            batch = db.batch();
                            count = 0;
                        }
                    }
                    batch.update(db.collection("chats").document(convId),
                            "unread_" + myUid, 0L);
                    commits.add(batch.commit());

                    Tasks.whenAllSuccess(commits)
                            .addOnFailureListener(e -> android.util.Log.w(
                                    "ReadReceiptHelper", "Read receipt batch failed: " + LogRedact.error(e)));
                })
                .addOnFailureListener(e -> android.util.Log.w(
                        "ReadReceiptHelper", "Read receipt query failed: " + LogRedact.error(e)));
    }

    public static void markMessageRead(Context context, String convId, String messageId) {
        if (context == null || convId == null || messageId == null) return;
        if (!FirebaseCostGuard.getInstance(context).reserveWrite(1)) return;
        FirebaseFirestore.getInstance()
            .collection("chats").document(convId)
            .collection("messages").document(messageId)
            .update("status", "read");
    }
}
