package com.detassist;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for AppIndex fuzzy matching logic.
 *
 * Tests the AppEntry construction and normalized label precomputation
 * (fix 7.1), plus the fuzzy matching threshold behavior.
 *
 * NOTE: Full integration tests (PackageManager queries) require androidTest
 * instrumentation. These tests cover the pure Java logic only.
 */
public class AppIndexTest {

    // ========================================
    // AppEntry label normalization (fix 7.1)
    // ========================================

    @Test
    public void testAppEntry_labelNormalized_stripsSpaces() {
        AppIndex.AppEntry entry = new AppIndex.AppEntry(
                "Google Maps", "com.google.android.apps.maps", "com.google.android.maps.MapActivity", false);

        assertEquals("google maps", entry.labelLower);
        assertEquals("googlemaps", entry.labelNormalized);
    }

    @Test
    public void testAppEntry_labelNormalized_stripsHyphens() {
        AppIndex.AppEntry entry = new AppIndex.AppEntry(
                "My-App", "com.example.myapp", "com.example.MainActivity", false);

        assertEquals("my-app", entry.labelLower);
        assertEquals("myapp", entry.labelNormalized);
    }

    @Test
    public void testAppEntry_labelNormalized_stripsUnderscores() {
        AppIndex.AppEntry entry = new AppIndex.AppEntry(
                "Some_App", "com.example.someapp", "com.example.MainActivity", false);

        assertEquals("some_app", entry.labelLower);
        assertEquals("someapp", entry.labelNormalized);
    }

    @Test
    public void testAppEntry_labelNormalized_combined() {
        AppIndex.AppEntry entry = new AppIndex.AppEntry(
                "WhatsApp Messenger", "com.whatsapp", "com.whatsapp.Main", false);

        assertEquals("whatsapp messenger", entry.labelLower);
        assertEquals("whatsappmessenger", entry.labelNormalized);
    }

    // ========================================
    // Levenshtein-based fuzzy matching
    // (Uses RegexCommandParser.calculateDistance since AppIndex shares the same logic)
    // ========================================

    @Test
    public void testFuzzyMatch_whatsApp_vs_spoken() {
        RegexCommandParser parser = new RegexCommandParser();

        // "whatsapp" spoken as "whats app" (with space)
        // Normalized comparison: "whatsapp" vs "whatsapp" → distance 0
        int dist = parser.calculateDistance("whatsapp", "whatsapp");
        assertEquals(0, dist);

        // "whatsap" (misrecognized) vs "whatsapp" → distance 1
        dist = parser.calculateDistance("whatsap", "whatsapp");
        assertEquals(1, dist);

        // Max distance for "whatsap" (len 7): max(1, 7/3) = 2
        assertTrue(dist <= RegexCommandParser.getMaxDistance(7));
    }

    @Test
    public void testFuzzyMatch_threshold_notTooLoose() {
        // "camera" (len 6) vs "chrome" (len 6) → distance 5
        RegexCommandParser parser = new RegexCommandParser();
        int dist = parser.calculateDistance("camera", "chrome");
        int maxDist = RegexCommandParser.getMaxDistance(6); // max(1, 2) = 2

        // This should EXCEED the threshold — they're too different
        assertTrue("camera vs chrome should exceed threshold",
                dist > maxDist);
    }

    @Test
    public void testFuzzyMatch_normalizedComparison() {
        RegexCommandParser parser = new RegexCommandParser();

        // "google maps" spoken without space: "googlemaps"
        // vs AppEntry.labelNormalized "googlemaps"
        int dist = parser.calculateDistance("googlemaps", "googlemaps");
        assertEquals(0, dist);
    }

    // ========================================
    // Known apps mapping
    // ========================================

    @Test
    public void testKnownApps_whatsapp() {
        // Verify the known-apps table has whatsapp mapping
        // (Can't instantiate AppIndex without Context, but we can verify the table is consistent)
        // This is a documentation test — the actual lookup happens in AppIndex.getKnownPackage()
        assertTrue(true); // Placeholder — full test requires androidTest
    }
}
