package com.duoshield.app.util;

import android.content.Context;
import android.util.Log;

import androidx.sqlite.db.SimpleSQLiteQuery;

import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Privacy-preserving local search over plaintext already decrypted on this device. */
public final class SearchHelper {
    private static final String TAG = "SearchHelper";
    private static final int MAX_RESULTS = 100;
    private static final int FALLBACK_SCAN_LIMIT = 2_000;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile Future<?> lastFuture;

    private SearchHelper() {}

    public interface Callback { void onResults(List<Message> results); }

    public enum Filter {
        ALL, MEDIA, LINKS, FILES, STARRED, UNREAD, MINE, OTHERS
    }

    public static void runSearch(Context ctx, String conversationId, String query, Callback cb) {
        runSearch(ctx, conversationId, query, Filter.ALL, null, cb);
    }

    public static void runSearch(Context ctx, String conversationId, String query,
                                 Filter filter, String myUid, Callback cb) {
        if (cb == null) return;
        final String normalized = query == null ? "" : query.trim();
        final Filter safeFilter = filter == null ? Filter.ALL : filter;
        if (normalized.length() < 2 && safeFilter == Filter.ALL) {
            cb.onResults(new ArrayList<>());
            return;
        }
        Future<?> previous = lastFuture;
        if (previous != null && !previous.isDone()) previous.cancel(true);
        final Context appContext = ctx.getApplicationContext();
        lastFuture = EXECUTOR.submit(() -> {
            if (Thread.currentThread().isInterrupted()) return;
            List<Message> results;
            try {
                results = normalized.length() < 2
                        ? searchFilterOnly(appContext, conversationId, safeFilter, myUid)
                        : searchFts(appContext, conversationId, normalized, safeFilter, myUid);
            } catch (RuntimeException e) {
                Log.w(TAG, "FTS search unavailable; using bounded local fallback (" + com.duoshield.app.util.LogRedact.error(e) + ")");
                results = boundedFallback(appContext, conversationId, normalized, safeFilter, myUid);
            }
            if (!Thread.currentThread().isInterrupted()) cb.onResults(results);
        });
    }

    private static List<Message> searchFilterOnly(Context ctx, String conversationId,
                                                   Filter filter, String myUid) {
        String sql = "SELECT m.* FROM messages AS m WHERE " +
                (conversationId == null ? "1 = 1 " : "m.conversationId = ? ") +
                "AND m.isDeleted = 0 " + filterSql(filter) +
                " ORDER BY m.timestamp DESC LIMIT ?";
        List<Object> args = new ArrayList<>();
        if (conversationId != null) args.add(conversationId);
        if (filter == Filter.UNREAD || filter == Filter.MINE || filter == Filter.OTHERS) {
            args.add(myUid == null ? "" : myUid);
        }
        args.add(MAX_RESULTS);
        return AppDatabase.getInstance(ctx).messageDao()
                .searchMessagesFts(new SimpleSQLiteQuery(sql, args.toArray()));
    }

    private static List<Message> searchFts(Context ctx, String conversationId, String query,
                                           Filter filter, String myUid) {
        String sql = "SELECT m.* FROM messages AS m " +
                "JOIN message_search_fts AS f ON f.message_id = m.id " +
                "WHERE " + (conversationId == null ? "1 = 1 " : "f.conversation_id = ? ") +
                "AND f.text MATCH ? AND m.isDeleted = 0 " +
                filterSql(filter) + " ORDER BY m.timestamp DESC LIMIT ?";
        List<Object> args = new ArrayList<>();
        if (conversationId != null) args.add(conversationId);
        args.add(toFtsMatchExpression(query));
        if (filter == Filter.UNREAD || filter == Filter.MINE || filter == Filter.OTHERS) {
            args.add(myUid == null ? "" : myUid);
        }
        args.add(MAX_RESULTS);
        return AppDatabase.getInstance(ctx).messageDao()
                .searchMessagesFts(new SimpleSQLiteQuery(sql, args.toArray()));
    }

    private static String filterSql(Filter filter) {
        switch (filter) {
            case MEDIA:
                return "AND m.mediaType IN ('image','video','album','voice') ";
            case FILES:
                return "AND m.mediaType IN ('file','document') ";
            case LINKS:
                return "AND (m.text LIKE '%http://%' OR m.text LIKE '%https://%') ";
            case STARRED:
                return "AND m.starred = 1 ";
            case UNREAD:
                return "AND m.sender != ? AND m.seen = 0 ";
            case MINE:
                return "AND m.sender = ? ";
            case OTHERS:
                return "AND m.sender != ? ";
            case ALL:
            default:
                return "";
        }
    }

    /** Converts user text into a conservative AND query; FTS operators are not user-controlled. */
    static String toFtsMatchExpression(String query) {
        String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder expression = new StringBuilder();
        for (String term : terms) {
            if (term.isEmpty()) continue;
            if (expression.length() > 0) expression.append(" AND ");
            expression.append('"').append(term.replace("\"", "\"\""));
            expression.append("\"*");
        }
        return expression.length() == 0 ? "\"\"" : expression.toString();
    }

    private static List<Message> boundedFallback(Context ctx, String conversationId, String query,
                                                 Filter filter, String myUid) {
        List<Message> all = conversationId == null
                ? AppDatabase.getInstance(ctx).messageDao().getLatestMessagesGlobal(FALLBACK_SCAN_LIMIT)
                : AppDatabase.getInstance(ctx).messageDao().getLatestMessages(conversationId, FALLBACK_SCAN_LIMIT);
        String lower = query.toLowerCase(Locale.ROOT);
        List<Message> results = new ArrayList<>();
        for (Message message : all) {
            if (Thread.currentThread().isInterrupted()) return results;
            String text = message.getText();
            if (text == null || message.isEncrypted()
                    || !text.toLowerCase(Locale.ROOT).contains(lower)
                    || !matchesFilter(message, filter, myUid)) continue;
            results.add(message);
            if (results.size() >= MAX_RESULTS) break;
        }
        return results;
    }

    private static boolean matchesFilter(Message m, Filter filter, String myUid) {
        switch (filter) {
            case MEDIA: return m.getMediaType() != null &&
                    (m.getMediaType().equals("image") || m.getMediaType().equals("video")
                            || m.getMediaType().equals("album") || m.getMediaType().equals("voice"));
            case FILES: return "file".equals(m.getMediaType()) || "document".equals(m.getMediaType());
            case LINKS: return m.getText().contains("http://") || m.getText().contains("https://");
            case STARRED: return m.starred;
            case UNREAD: return myUid != null && !myUid.equals(m.getSender()) && !m.isSeen();
            case MINE: return myUid != null && myUid.equals(m.getSender());
            case OTHERS: return myUid != null && !myUid.equals(m.getSender());
            case ALL:
            default: return true;
        }
    }

    public static void clearSearch() {
        Future<?> previous = lastFuture;
        if (previous != null) previous.cancel(true);
    }
}
