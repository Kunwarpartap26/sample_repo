package com.detassist;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Regex Command Parser - Zero-AI Intent Recognition
 * 
 * ARCHITECTURE:
 * - Strict regex pattern matching with named capture groups
 * - Compound utterance splitting via "and"/"then"/"after that" boundaries
 * - Pre-compiled Patterns (compiled once, used many times)
 * - Case-insensitive matching throughout
 * - No neural networks, no ML, pure deterministic logic
 * 
 * SUPPORTED COMMAND TYPES:
 * 1. App Launch: "open [app]", "launch [app]", "start [app]"
 * 2. System Toggle: "turn on/off [feature]"
 * 3. Send Message: "send [text] to [contact] on [app]"
 * 4. Call Contact: "call [contact]"
 * 5. Timer: "set timer for [duration]"
 * 6. Search: "search [query]"
 */
public class RegexCommandParser {

    private static final String TAG = "RegexCommandParser";

    // ========================================
    // Command Types
    // ========================================
    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_OPEN_APP = 1;
    public static final int TYPE_TOGGLE = 2;
    public static final int TYPE_SEND_MESSAGE = 3;
    public static final int TYPE_CALL = 4;
    public static final int TYPE_SET_TIMER = 5;
    public static final int TYPE_SEARCH = 6;
    public static final int TYPE_CLOSE_APP = 7;
    public static final int TYPE_NAVIGATE = 8;

    // ========================================
    // Toggle States
    // ========================================
    public static final int TOGGLE_ON = 1;
    public static final int TOGGLE_OFF = 0;
    public static final int TOGGLE_UNKNOWN = -1;

    // ========================================
    // Supported System Features (for toggle)
    // ========================================
    public static final String FEATURE_WIFI = "wifi";
    public static final String FEATURE_BLUETOOTH = "bluetooth";
    public static final String FEATURE_FLASHLIGHT = "flashlight";
    public static final String FEATURE_DND = "do not disturb";
    public static final String FEATURE_AIRPLANE = "airplane mode";
    public static final String FEATURE_MOBILE_DATA = "mobile data";
    public static final String FEATURE_HOTSPOT = "hotspot";
    public static final String FEATURE_LOCATION = "location";
    public static final String FEATURE_NFC = "nfc";
    public static final String FEATURE_AUTO_ROTATE = "auto rotate";

    // ========================================
    // Pre-compiled Patterns (compiled ONCE at class load)
    // ========================================
    
    // Sequence splitter: break "open whatsapp and then call mom" into ["open whatsapp", "call mom"]
    private static final Pattern SEQUENCE_SPLITTER = Pattern.compile(
        "\\s+(?:and\\s+then|and|then|after\\s+that|also)\\s+",
        Pattern.CASE_INSENSITIVE
    );

    // Open/Launch/Start app
    private static final Pattern PATTERN_OPEN_APP = Pattern.compile(
        "^\\s*(?:open|launch|start|run|go\\s+to|show\\s+me)\\s+(?<appName>.+?)\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Close app
    private static final Pattern PATTERN_CLOSE_APP = Pattern.compile(
        "^\\s*(?:close|kill|stop|exit|quit|end)\\s+(?<appName>.+?)\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Toggle on/off system features
    private static final Pattern PATTERN_TOGGLE = Pattern.compile(
        "^\\s*(?:turn|switch|enable|disable)\\s+(?<state>on|off)\\s+(?<feature>.+?)\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Alternative toggle: "enable wifi", "disable bluetooth"
    private static final Pattern PATTERN_TOGGLE_ALT = Pattern.compile(
        "^\\s*(?<action>enable|disable)\\s+(?<feature>.+?)\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Send message: "send [text] to [contact] on [app]"
    private static final Pattern PATTERN_SEND_MESSAGE = Pattern.compile(
        "^\\s*send\\s+(?:a\\s+)?(?:message|msg|text)\\s+(?:(?:saying|with\\s+the\\s+message|that\\s+says)\\s+)?(?<messageText>.+?)\\s+to\\s+(?<contact>.+?)(?:\\s+on\\s+(?<app>.+?))?\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Simpler send: "text [contact] [message]"
    private static final Pattern PATTERN_SEND_TEXT = Pattern.compile(
        "^\\s*text\\s+(?<contact>.+?)\\s+(?<messageText>.+?)\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Call contact
    private static final Pattern PATTERN_CALL = Pattern.compile(
        "^\\s*(?:call|phone|dial|ring)\\s+(?<contact>.+?)\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Set timer
    private static final Pattern PATTERN_TIMER = Pattern.compile(
        "^\\s*set\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?(?<duration>.+?)\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Alternative timer: "timer [duration]"
    private static final Pattern PATTERN_TIMER_ALT = Pattern.compile(
        "^\\s*timer\\s+(?<duration>.+?)\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Search
    private static final Pattern PATTERN_SEARCH = Pattern.compile(
        "^\\s*(?:search|look\\s+up|find|google)\\s+(?:for\\s+)?(?<query>.+?)\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Navigate: "go to [url/location]"
    private static final Pattern PATTERN_NAVIGATE = Pattern.compile(
        "^\\s*(?:navigate|go|drive|take\\s+me)\\s+(?:to)\\s+(?<destination>.+?)\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Wake word filter (strip from beginning of utterance)
    private static final Pattern PATTERN_WAKE_WORD = Pattern.compile(
        "^(?:hey\\s+assist|ok\\s+assist|hi\\s+assist|assist)\\s+",
        Pattern.CASE_INSENSITIVE
    );

    // Duration parser for timers
    private static final Pattern PATTERN_DURATION_MINUTES = Pattern.compile(
        "(?<mins>\\d+)\\s*(?:minutes?|mins?|m)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_DURATION_SECONDS = Pattern.compile(
        "(?<secs>\\d+)\\s*(?:seconds?|secs?|s)",
        Pattern.CASE_INSENSITIVE
    );

    // ========================================
    // Parsed Command Structure
    // ========================================
    public static class ParsedCommand {
        public int type = TYPE_UNKNOWN;
        public String rawText = "";
        
        // For TYPE_OPEN_APP / TYPE_CLOSE_APP
        public String appName = "";
        
        // For TYPE_TOGGLE
        public int toggleState = TOGGLE_UNKNOWN;
        public String feature = "";
        
        // For TYPE_SEND_MESSAGE
        public String messageText = "";
        public String contact = "";
        public String messagingApp = "";
        
        // For TYPE_CALL
        public String callTarget = "";
        
        // For TYPE_SET_TIMER
        public long durationMs = 0;
        
        // For TYPE_SEARCH
        public String searchQuery = "";
        
        // For TYPE_NAVIGATE
        public String destination = "";

        @Override
        public String toString() {
            switch (type) {
                case TYPE_OPEN_APP:
                    return "OpenApp(" + appName + ")";
                case TYPE_CLOSE_APP:
                    return "CloseApp(" + appName + ")";
                case TYPE_TOGGLE:
                    return "Toggle(" + feature + ", " + (toggleState == TOGGLE_ON ? "ON" : "OFF") + ")";
                case TYPE_SEND_MESSAGE:
                    return "SendMessage(" + messageText + " -> " + contact + " via " + messagingApp + ")";
                case TYPE_CALL:
                    return "Call(" + callTarget + ")";
                case TYPE_SET_TIMER:
                    return "Timer(" + durationMs + "ms)";
                case TYPE_SEARCH:
                    return "Search(" + searchQuery + ")";
                case TYPE_NAVIGATE:
                    return "Navigate(" + destination + ")";
                default:
                    return "Unknown(" + rawText + ")";
            }
        }
    }

    // ========================================
    // Public API
    // ========================================

    /**
     * Parse a spoken utterance into a list of discrete commands.
     * Handles compound utterances like "open whatsapp and then call mom".
     * 
     * @param utterance raw decoded speech text
     * @return list of parsed commands (may be empty if unrecognized)
     */
    public List<ParsedCommand> parse(String utterance) {
        List<ParsedCommand> commands = new ArrayList<>();
        
        if (utterance == null || utterance.trim().isEmpty()) {
            return commands;
        }
        
        // Step 1: Strip wake word prefix
        String cleaned = stripWakeWord(utterance);
        Log.d(TAG, "Cleaned utterance: '" + cleaned + "'");
        
        // Step 2: Split compound utterances by sequence boundaries
        String[] segments = SEQUENCE_SPLITTER.split(cleaned);
        
        // Step 3: Parse each segment independently
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) continue;
            
            ParsedCommand cmd = parseSingleCommand(trimmed);
            if (cmd != null) {
                commands.add(cmd);
            } else {
                Log.w(TAG, "Unrecognized command segment: '" + trimmed + "'");
                // Create unknown command to signal failure for halting
                ParsedCommand unknown = new ParsedCommand();
                unknown.type = TYPE_UNKNOWN;
                unknown.rawText = trimmed;
                commands.add(unknown);
            }
        }
        
        return commands;
    }

    /**
     * Parse a single command segment (no splitting).
     */
    public ParsedCommand parseSingleCommand(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        
        String trimmed = text.trim();
        Log.d(TAG, "Parsing command: '" + trimmed + "'");
        
        Matcher matcher;
        
        // Try each pattern in priority order (most specific first)
        
        // 1. Send message (most complex, try first)
        matcher = PATTERN_SEND_MESSAGE.matcher(trimmed);
        if (matcher.matches()) {
            ParsedCommand cmd = new ParsedCommand();
            cmd.type = TYPE_SEND_MESSAGE;
            cmd.rawText = trimmed;
            cmd.messageText = matcher.group("messageText").trim();
            cmd.contact = matcher.group("contact").trim();
            cmd.messagingApp = matcher.group("app") != null ? matcher.group("app").trim() : "";
            Log.i(TAG, "Matched SEND_MESSAGE: " + cmd);
            return cmd;
        }
        
        // 1b. Text command
        matcher = PATTERN_SEND_TEXT.matcher(trimmed);
        if (matcher.matches()) {
            ParsedCommand cmd = new ParsedCommand();
            cmd.type = TYPE_SEND_MESSAGE;
            cmd.rawText = trimmed;
            cmd.contact = matcher.group("contact").trim();
            cmd.messageText = matcher.group("messageText").trim();
            cmd.messagingApp = ""; // Default SMS
            Log.i(TAG, "Matched SEND_TEXT: " + cmd);
            return cmd;
        }
        
        // 2. Toggle
        matcher = PATTERN_TOGGLE.matcher(trimmed);
        if (matcher.matches()) {
            ParsedCommand cmd = new ParsedCommand();
            cmd.type = TYPE_TOGGLE;
            cmd.rawText = trimmed;
            String state = matcher.group("state").trim().toLowerCase();
            cmd.toggleState = state.equals("on") ? TOGGLE_ON : TOGGLE_OFF;
            cmd.feature = normalizeFeatureName(matcher.group("feature").trim());
            Log.i(TAG, "Matched TOGGLE: " + cmd);
            return cmd;
        }
        
        // 2b. Enable/Disable
        matcher = PATTERN_TOGGLE_ALT.matcher(trimmed);
        if (matcher.matches()) {
            ParsedCommand cmd = new ParsedCommand();
            cmd.type = TYPE_TOGGLE;
            cmd.rawText = trimmed;
            String action = matcher.group("action").trim().toLowerCase();
            cmd.toggleState = action.equals("enable") ? TOGGLE_ON : TOGGLE_OFF;
            cmd.feature = normalizeFeatureName(matcher.group("feature").trim());
            Log.i(TAG, "Matched TOGGLE_ALT: " + cmd);
            return cmd;
        }
        
        // 3. Call
        matcher = PATTERN_CALL.matcher(trimmed);
        if (matcher.matches()) {
            ParsedCommand cmd = new ParsedCommand();
            cmd.type = TYPE_CALL;
            cmd.rawText = trimmed;
            cmd.callTarget = matcher.group("contact").trim();
            Log.i(TAG, "Matched CALL: " + cmd);
            return cmd;
        }
        
        // 4. Timer
        matcher = PATTERN_TIMER.matcher(trimmed);
        if (matcher.matches()) {
            ParsedCommand cmd = new ParsedCommand();
            cmd.type = TYPE_SET_TIMER;
            cmd.rawText = trimmed;
            cmd.durationMs = parseDuration(matcher.group("duration").trim());
            Log.i(TAG, "Matched TIMER: " + cmd);
            return cmd;
        }
        
        matcher = PATTERN_TIMER_ALT.matcher(trimmed);
        if (matcher.matches()) {
            ParsedCommand cmd = new ParsedCommand();
            cmd.type = TYPE_SET_TIMER;
            cmd.rawText = trimmed;
            cmd.durationMs = parseDuration(matcher.group("duration").trim());
            Log.i(TAG, "Matched TIMER_ALT: " + cmd);
            return cmd;
        }
        
        // 5. Navigate
        matcher = PATTERN_NAVIGATE.matcher(trimmed);
        if (matcher.matches()) {
            ParsedCommand cmd = new ParsedCommand();
            cmd.type = TYPE_NAVIGATE;
            cmd.rawText = trimmed;
            cmd.destination = matcher.group("destination").trim();
            Log.i(TAG, "Matched NAVIGATE: " + cmd);
            return cmd;
        }
        
        // 6. Search
        matcher = PATTERN_SEARCH.matcher(trimmed);
        if (matcher.matches()) {
            ParsedCommand cmd = new ParsedCommand();
            cmd.type = TYPE_SEARCH;
            cmd.rawText = trimmed;
            cmd.searchQuery = matcher.group("query").trim();
            Log.i(TAG, "Matched SEARCH: " + cmd);
            return cmd;
        }
        
        // 7. Open/Launch app
        matcher = PATTERN_OPEN_APP.matcher(trimmed);
        if (matcher.matches()) {
            ParsedCommand cmd = new ParsedCommand();
            cmd.type = TYPE_OPEN_APP;
            cmd.rawText = trimmed;
            cmd.appName = matcher.group("appName").trim();
            Log.i(TAG, "Matched OPEN_APP: " + cmd);
            return cmd;
        }
        
        // 8. Close app
        matcher = PATTERN_CLOSE_APP.matcher(trimmed);
        if (matcher.matches()) {
            ParsedCommand cmd = new ParsedCommand();
            cmd.type = TYPE_CLOSE_APP;
            cmd.rawText = trimmed;
            cmd.appName = matcher.group("appName").trim();
            Log.i(TAG, "Matched CLOSE_APP: " + cmd);
            return cmd;
        }
        
        // No match
        Log.w(TAG, "No pattern matched for: '" + trimmed + "'");
        return null;
    }

    // ========================================
    // Levenshtein Distance (Optimized Two-Row)
    // ========================================

    // Pre-allocated rows for Levenshtein computation
    private int[] mLevRow0 = new int[256];
    private int[] mLevRow1 = new int[256];

    /**
     * Calculate Levenshtein edit distance between two strings.
     * Uses pre-allocated two-row arrays to eliminate runtime allocation.
     * 
     * Thread-unsafe: caller must ensure single-threaded access to the parser
     * or synchronize externally.
     * 
     * @param a first string
     * @param b second string
     * @return edit distance (0 = exact match)
     */
    public int calculateDistance(String a, String b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        if (a.equals(b)) return 0;
        
        int lenA = a.length();
        int lenB = b.length();
        
        if (lenA == 0) return lenB;
        if (lenB == 0) return lenA;
        
        // Ensure lenA <= lenB for space optimization
        if (lenA > lenB) {
            String temp = a; a = b; b = temp;
            int t = lenA; lenA = lenB; lenB = t;
        }
        
        // Ensure arrays are large enough
        if (lenA + 1 > mLevRow0.length) {
            mLevRow0 = new int[lenA + 1];
            mLevRow1 = new int[lenA + 1];
        }
        
        // Initialize first row
        for (int i = 0; i <= lenA; i++) {
            mLevRow0[i] = i;
        }
        
        // Fill rows
        for (int j = 1; j <= lenB; j++) {
            mLevRow1[0] = j;
            char charB = b.charAt(j - 1);
            
            for (int i = 1; i <= lenA; i++) {
                int cost = (a.charAt(i - 1) == charB) ? 0 : 1;
                mLevRow1[i] = Math.min(
                    Math.min(mLevRow1[i - 1] + 1, mLevRow0[i] + 1),
                    mLevRow0[i - 1] + cost
                );
            }
            
            // Swap rows
            int[] temp = mLevRow0;
            mLevRow0 = mLevRow1;
            mLevRow1 = temp;
        }
        
        return mLevRow0[lenA];
    }

    // ========================================
    // Utility Methods
    // ========================================

    /**
     * Strip wake word prefix from utterance.
     */
    private String stripWakeWord(String utterance) {
        Matcher matcher = PATTERN_WAKE_WORD.matcher(utterance);
        if (matcher.find()) {
            return matcher.replaceFirst("");
        }
        return utterance;
    }

    /**
     * Normalize spoken feature names to canonical form.
     */
    private String normalizeFeatureName(String spoken) {
        if (spoken == null) return "";
        
        String lower = spoken.toLowerCase().trim();
        
        // Normalize common variations
        switch (lower) {
            case "wi-fi":
            case "wi fi":
            case "wifi":
            case "wireless":
                return FEATURE_WIFI;
                
            case "bluetooth":
            case "bt":
            case "blue tooth":
                return FEATURE_BLUETOOTH;
                
            case "flashlight":
            case "torch":
            case "flash light":
            case "lamp":
                return FEATURE_FLASHLIGHT;
                
            case "do not disturb":
            case "dnd":
            case "do not disruption":
            case "don't disturb":
            case "silent mode":
            case "silent":
                return FEATURE_DND;
                
            case "airplane mode":
            case "airplane":
            case "aeroplane mode":
            case "aeroplane":
            case "flight mode":
                return FEATURE_AIRPLANE;
                
            case "mobile data":
            case "cellular data":
            case "data":
            case "cell data":
                return FEATURE_MOBILE_DATA;
                
            case "hotspot":
            case "hot spot":
            case "tethering":
            case "wifi hotspot":
                return FEATURE_HOTSPOT;
                
            case "location":
            case "gps":
            case "location services":
            case "geo location":
                return FEATURE_LOCATION;
                
            case "nfc":
            case "near field":
            case "near field communication":
                return FEATURE_NFC;
                
            case "auto rotate":
            case "rotation":
            case "screen rotation":
            case "auto rotation":
                return FEATURE_AUTO_ROTATE;
                
            default:
                return lower;
        }
    }

    /**
     * Parse spoken duration into milliseconds.
     * Handles "5 minutes", "30 seconds", "1 minute 30 seconds", etc.
     *
     * FIX (7.4): Returns -1 when no duration pattern matches at all,
     * instead of silently defaulting to 5 minutes.
     * The caller (ActionDispatcher.executeTimer) treats -1 as a failure
     * and surfaces "I didn't catch how long" feedback.
     *
     * @param spoken the spoken duration text
     * @return duration in milliseconds, or -1 if unparseable
     */
    private long parseDuration(String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) {
            return -1; // FIX (7.4): sentinel for failure
        }

        long totalMs = 0;
        boolean matched = false;

        Matcher minMatcher = PATTERN_DURATION_MINUTES.matcher(spoken);
        if (minMatcher.find()) {
            totalMs += Long.parseLong(minMatcher.group("mins")) * 60 * 1000;
            matched = true;
        }

        Matcher secMatcher = PATTERN_DURATION_SECONDS.matcher(spoken);
        if (secMatcher.find()) {
            totalMs += Long.parseLong(secMatcher.group("secs")) * 1000;
            matched = true;
        }

        // Handle hours
        String lower = spoken.toLowerCase(Locale.ROOT);
        if (lower.contains("hour") || lower.contains("hr")) {
            java.util.regex.Pattern hourPattern = java.util.regex.Pattern.compile(
                "(\\d+)\\s*(?:hours?|hrs?|h)", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher hourMatcher = hourPattern.matcher(spoken);
            if (hourMatcher.find()) {
                totalMs += Long.parseLong(hourMatcher.group(1)) * 60 * 60 * 1000;
                matched = true;
            }
        }

        // FIX (7.4): Return -1 sentinel if nothing matched, instead of defaulting
        if (!matched) {
            Log.w(TAG, "parseDuration: no pattern matched for '" + spoken + "'");
            return -1;
        }

        return totalMs;
    }

    /**
     * Get maximum Levenshtein distance threshold for fuzzy matching.
     * Dynamic limit: max(1, floor(length / 3))
     */
    public static int getMaxDistance(int length) {
        return Math.max(1, length / 3);
    }
}
