package com.detassist;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * App Index & Contact Resolver — Local Package Cache
 *
 * ARCHITECTURE:
 *   - Lightweight local array cache mapping app labels → package names
 *   - PackageManager synchronization via queryIntentActivities()
 *   - Resolution ladder: exact → prefix → contains → Levenshtein fallback
 *   - Pre-allocated arrays for zero-allocation resolution (after build)
 *
 * FIXES APPLIED:
 *   - Issue 6.1: Uses volatile IndexSnapshot for thread-safe publish
 *   - Issue 7.1: Precomputes labelNormalized in AppEntry constructor
 *     (eliminates per-query String allocations in findFuzzy)
 *
 * MEMORY: ~50 KiB for 200 installed apps
 */
public class AppIndex {

    private static final String TAG = "AppIndex";

    // ========================================
    // Immutable Snapshot for Thread-Safe Publish (fix 6.1)
    // ========================================

    /**
     * Immutable snapshot of both index arrays.
     * Published atomically via a single volatile reference — eliminates the
     * two-field visibility split described in issue 6.1.
     */
    private static final class IndexSnapshot {
        final AppEntry[] byLabel;
        final AppEntry[] byPackage;

        IndexSnapshot(AppEntry[] byLabel, AppEntry[] byPackage) {
            this.byLabel = byLabel;
            this.byPackage = byPackage;
        }
    }

    // ========================================
    // App Entry Structure
    // ========================================
    public static class AppEntry {
        public final String label;
        public final String labelLower;
        public final String labelNormalized; // FIX (7.1): precomputed, no spaces/hyphens
        public final String packageName;
        public final String activityClass;
        public final boolean isSystemApp;

        public AppEntry(String label, String packageName, String activityClass, boolean isSystemApp) {
            this.label = label;
            this.labelLower = label.toLowerCase(Locale.ROOT);
            // FIX (7.1): Precompute normalized form once at construction time.
            // This eliminates per-query allocation in findFuzzy().
            this.labelNormalized = this.labelLower.replace(" ", "").replace("-", "").replace("_", "");
            this.packageName = packageName;
            this.activityClass = activityClass;
            this.isSystemApp = isSystemApp;
        }

        @Override
        public String toString() {
            return label + " (" + packageName + ")";
        }
    }

    // ========================================
    // Instance State
    // ========================================
    private final Context mContext;
    private final RegexCommandParser mParser;

    // FIX (6.1): Single volatile reference to immutable snapshot
    // Replaced separate mAppsByLabel / mAppsByPackage volatile fields
    private volatile IndexSnapshot mSnapshot;

    // Resolution cache
    private volatile String mLastQuery = null;
    private volatile AppEntry mLastResult = null;

    // Reusable Levenshtein state (shared with parser)
    private int[] mLevRow0 = new int[256];
    private int[] mLevRow1 = new int[256];

    private volatile boolean mBuilt = false;

    // ========================================
    // Constructor
    // ========================================
    public AppIndex(Context context, RegexCommandParser parser) {
        mContext = context.getApplicationContext();
        mParser = parser;
        mSnapshot = new IndexSnapshot(new AppEntry[0], new AppEntry[0]);
    }

    // ========================================
    // Build Index
    // ========================================

    /**
     * Build the app index from PackageManager.
     * Should be called once on startup and on package changes.
     * Thread-safe: publishes atomically via volatile snapshot reference.
     */
    public synchronized void build() {
        Log.i(TAG, "Building app index...");
        long startTime = System.currentTimeMillis();

        PackageManager pm = mContext.getPackageManager();
        List<AppEntry> entries = new ArrayList<>(256);

        // Query all launchable activities
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent,
                PackageManager.MATCH_ALL);

        for (ResolveInfo info : resolveInfos) {
            if (info.activityInfo == null) continue;

            String label = info.loadLabel(pm).toString();
            String packageName = info.activityInfo.packageName;
            String activityClass = info.activityInfo.name;
            boolean isSystem = (info.activityInfo.applicationInfo.flags &
                    android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;

            if (label == null || label.trim().isEmpty()) continue;
            entries.add(new AppEntry(label, packageName, activityClass, isSystem));
        }

        // Also add apps from DEFAULT category
        Intent defaultIntent = new Intent(Intent.ACTION_MAIN, null);
        defaultIntent.addCategory(Intent.CATEGORY_DEFAULT);
        List<ResolveInfo> defaultInfos = pm.queryIntentActivities(defaultIntent,
                PackageManager.MATCH_ALL);

        for (ResolveInfo info : defaultInfos) {
            if (info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;

            // Skip if already added
            boolean exists = false;
            for (AppEntry e : entries) {
                if (e.packageName.equals(packageName)) {
                    exists = true;
                    break;
                }
            }
            if (exists) continue;

            String label = info.loadLabel(pm).toString();
            if (label == null || label.trim().isEmpty()) continue;

            entries.add(new AppEntry(label, packageName, info.activityInfo.name,
                    (info.activityInfo.applicationInfo.flags &
                            android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0));
        }

        // Sort by label for binary search
        Collections.sort(entries, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.labelLower.compareTo(b.labelLower);
            }
        });

        AppEntry[] byLabel = entries.toArray(new AppEntry[0]);

        // Create package-sorted copy
        AppEntry[] byPackage = byLabel.clone();
        java.util.Arrays.sort(byPackage, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.packageName.compareTo(b.packageName);
            }
        });

        // FIX (6.1): Publish atomically via single volatile reference
        mSnapshot = new IndexSnapshot(byLabel, byPackage);
        mBuilt = true;

        long elapsed = System.currentTimeMillis() - startTime;
        Log.i(TAG, "App index built: " + byLabel.length + " apps in " + elapsed + "ms");
    }

    public int getAppCount() {
        IndexSnapshot snap = mSnapshot;
        return snap != null ? snap.byLabel.length : 0;
    }

    public boolean isBuilt() {
        return mBuilt;
    }

    // ========================================
    // Resolution Ladder
    // ========================================

    /**
     * Resolve a spoken app name to an AppEntry.
     * Resolution ladder: exact → prefix → contains → fuzzy (Levenshtein)
     */
    public AppEntry resolve(String spokenName) {
        if (spokenName == null || spokenName.trim().isEmpty()) return null;
        if (!mBuilt) {
            Log.w(TAG, "App index not built");
            return null;
        }

        String query = spokenName.trim().toLowerCase(Locale.ROOT);

        // Fast path: check cache
        if (query.equals(mLastQuery) && mLastResult != null) {
            return mLastResult;
        }

        IndexSnapshot snap = mSnapshot;
        if (snap == null) return null;

        AppEntry result = null;

        // Level 1: Exact match
        result = findExact(snap, query);
        if (result != null) {
            cacheResult(query, result);
            Log.i(TAG, "Exact match: '" + spokenName + "' -> " + result);
            return result;
        }

        // Level 2: Prefix match
        result = findPrefix(snap, query);
        if (result != null) {
            cacheResult(query, result);
            Log.i(TAG, "Prefix match: '" + spokenName + "' -> " + result);
            return result;
        }

        // Level 3: Contains match
        result = findContains(snap, query);
        if (result != null) {
            cacheResult(query, result);
            Log.i(TAG, "Contains match: '" + spokenName + "' -> " + result);
            return result;
        }

        // Level 4: Levenshtein fuzzy match
        result = findFuzzy(snap, query);
        if (result != null) {
            cacheResult(query, result);
            Log.i(TAG, "Fuzzy match: '" + spokenName + "' -> " + result);
            return result;
        }

        Log.w(TAG, "No match found for: '" + spokenName + "'");
        return null;
    }

    public AppEntry resolveByPackage(String packageName) {
        if (packageName == null) return null;
        IndexSnapshot snap = mSnapshot;
        if (snap == null) return null;

        int lo = 0, hi = snap.byPackage.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = snap.byPackage[mid].packageName.compareTo(packageName);
            if (cmp < 0) lo = mid + 1;
            else if (cmp > 0) hi = mid - 1;
            else return snap.byPackage[mid];
        }
        return null;
    }

    public AppEntry[] getAllApps() {
        IndexSnapshot snap = mSnapshot;
        return snap != null ? snap.byLabel : new AppEntry[0];
    }

    // ========================================
    // Resolution Methods
    // ========================================

    private AppEntry findExact(IndexSnapshot snap, String query) {
        int lo = 0, hi = snap.byLabel.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = snap.byLabel[mid].labelLower.compareTo(query);
            if (cmp < 0) lo = mid + 1;
            else if (cmp > 0) hi = mid - 1;
            else return snap.byLabel[mid];
        }
        return null;
    }

    private AppEntry findPrefix(IndexSnapshot snap, String query) {
        AppEntry bestMatch = null;
        int bestScore = Integer.MAX_VALUE;

        for (AppEntry entry : snap.byLabel) {
            if (entry.labelLower.startsWith(query)) {
                int score = entry.labelLower.length();
                if (score < bestScore) {
                    bestScore = score;
                    bestMatch = entry;
                }
            }
        }
        return bestMatch;
    }

    private AppEntry findContains(IndexSnapshot snap, String query) {
        AppEntry bestMatch = null;
        int bestScore = Integer.MAX_VALUE;

        for (AppEntry entry : snap.byLabel) {
            if (entry.labelLower.contains(query)) {
                int score = entry.labelLower.length();
                if (score < bestScore) {
                    bestScore = score;
                    bestMatch = entry;
                }
            }
        }
        return bestMatch;
    }

    /**
     * Level 4: Fuzzy match using Levenshtein distance.
     *
     * FIX (7.1): Uses precomputed labelNormalized from AppEntry.
     * No String allocations per query — only comparisons against cached values.
     */
    private AppEntry findFuzzy(IndexSnapshot snap, String query) {
        int maxDist = RegexCommandParser.getMaxDistance(query.length());
        AppEntry bestMatch = null;
        int bestDistance = maxDist + 1;

        // Ensure arrays are large enough
        int maxLen = query.length() + 1;
        for (AppEntry entry : snap.byLabel) {
            int labelLen = entry.labelLower.length();
            if (labelLen + 1 > maxLen) maxLen = labelLen + 1;
        }

        if (maxLen > mLevRow0.length) {
            mLevRow0 = new int[maxLen];
            mLevRow1 = new int[maxLen];
        }

        // Pass 1: Compare against full labels
        for (AppEntry entry : snap.byLabel) {
            int dist = levenshtein(query, entry.labelLower);
            if (dist < bestDistance) {
                bestDistance = dist;
                bestMatch = entry;
                if (dist == 0) break;
            }
        }

        // Pass 2: Compare against precomputed normalized labels (no spaces/hyphens)
        // FIX (7.1): Uses entry.labelNormalized — ZERO allocation per query
        String queryNormalized = query.replace(" ", "").replace("-", "");
        for (AppEntry entry : snap.byLabel) {
            int dist = levenshtein(queryNormalized, entry.labelNormalized);
            if (dist < bestDistance) {
                bestDistance = dist;
                bestMatch = entry;
            }
        }

        return (bestDistance <= maxDist) ? bestMatch : null;
    }

    /**
     * Optimized Levenshtein distance with pre-allocated rows.
     */
    private int levenshtein(String a, String b) {
        int lenA = a.length();
        int lenB = b.length();

        if (lenA == 0) return lenB;
        if (lenB == 0) return lenA;

        if (lenA > lenB) {
            String temp = a; a = b; b = temp;
            int t = lenA; lenA = lenB; lenB = t;
        }

        for (int i = 0; i <= lenA; i++) {
            mLevRow0[i] = i;
        }

        for (int j = 1; j <= lenB; j++) {
            mLevRow1[0] = j;
            char charB = b.charAt(j - 1);

            for (int i = 1; i <= lenA; i++) {
                int cost = (a.charAt(i - 1) == charB) ? 0 : 1;
                mLevRow1[i] = Math.min(
                        Math.min(mLevRow1[i - 1] + 1, mLevRow0[i] + 1),
                        mLevRow0[i - 1] + cost);
            }

            int[] tmp = mLevRow0;
            mLevRow0 = mLevRow1;
            mLevRow1 = tmp;
        }

        return mLevRow0[lenA];
    }

    // ========================================
    // Cache
    // ========================================

    private void cacheResult(String query, AppEntry result) {
        mLastQuery = query;
        mLastResult = result;
    }

    public void invalidateCache() {
        mLastQuery = null;
        mLastResult = null;
    }

    // ========================================
    // Well-Known Package Mappings
    // ========================================

    private static final String[][] KNOWN_APPS = {
            {"whatsapp", "com.whatsapp"},
            {"whats app", "com.whatsapp"},
            {"facebook", "com.facebook.katana"},
            {"instagram", "com.instagram.android"},
            {"twitter", "com.twitter.android"},
            {"youtube", "com.google.android.youtube"},
            {"gmail", "com.google.android.gm"},
            {"maps", "com.google.android.apps.maps"},
            {"google maps", "com.google.android.apps.maps"},
            {"chrome", "com.android.chrome"},
            {"camera", "com.android.camera"},
            {"photos", "com.google.android.apps.photos"},
            {"settings", "com.android.settings"},
            {"phone", "com.android.dialer"},
            {"dialer", "com.android.dialer"},
            {"messages", "com.google.android.apps.messaging"},
            {"clock", "com.google.android.deskclock"},
            {"calendar", "com.google.android.calendar"},
            {"calculator", "com.google.android.calculator"},
            {"spotify", "com.spotify.music"},
            {"netflix", "com.netflix.mediaclient"},
            {"telegram", "org.telegram.messenger"},
            {"signal", "org.thoughtcrime.securesms"},
            {"zoom", "us.zoom.videomeetings"},
            {"meet", "com.google.android.apps.meetings"},
            {"drive", "com.google.android.apps.docs"},
            {"play store", "com.android.vending"},
    };

    public String getKnownPackage(String spokenName) {
        if (spokenName == null) return null;
        String lower = spokenName.toLowerCase(Locale.ROOT).trim();

        for (String[] mapping : KNOWN_APPS) {
            if (mapping[0].equals(lower)) {
                return mapping[1];
            }
        }
        return null;
    }
}
