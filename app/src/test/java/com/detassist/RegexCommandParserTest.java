package com.detassist;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for RegexCommandParser.
 *
 * Covers:
 *   - Sequence splitting (compound utterances)
 *   - App launch parsing
 *   - System toggle parsing
 *   - Send message parsing
 *   - Call, timer, navigate, search
 *   - Wake word stripping
 *   - Levenshtein distance
 *   - Duration parsing failure sentinel (fix 7.4)
 */
public class RegexCommandParserTest {

    private RegexCommandParser parser;

    @Before
    public void setUp() {
        parser = new RegexCommandParser();
    }

    // ========================================
    // Sequence Splitting
    // ========================================

    @Test
    public void testSequenceSplit_andThen() {
        List<RegexCommandParser.ParsedCommand> cmds = parser.parse("open whatsapp and then call mom");
        assertEquals(2, cmds.size());
        assertEquals(RegexCommandParser.TYPE_OPEN_APP, cmds.get(0).type);
        assertEquals("whatsapp", cmds.get(0).appName);
        assertEquals(RegexCommandParser.TYPE_CALL, cmds.get(1).type);
        assertEquals("mom", cmds.get(1).callTarget);
    }

    @Test
    public void testSequenceSplit_and() {
        List<RegexCommandParser.ParsedCommand> cmds = parser.parse("turn on wifi and turn on bluetooth");
        assertEquals(2, cmds.size());
        assertEquals(RegexCommandParser.TYPE_TOGGLE, cmds.get(0).type);
        assertEquals(RegexCommandParser.TYPE_TOGGLE, cmds.get(1).type);
    }

    @Test
    public void testSequenceSplit_then() {
        List<RegexCommandParser.ParsedCommand> cmds = parser.parse("open chrome then search for recipes");
        assertEquals(2, cmds.size());
        assertEquals(RegexCommandParser.TYPE_OPEN_APP, cmds.get(0).type);
        assertEquals(RegexCommandParser.TYPE_SEARCH, cmds.get(1).type);
    }

    @Test
    public void testSequenceSplit_afterThat() {
        List<RegexCommandParser.ParsedCommand> cmds = parser.parse("turn on flashlight after that open camera");
        assertEquals(2, cmds.size());
        assertEquals(RegexCommandParser.TYPE_TOGGLE, cmds.get(0).type);
        assertEquals(RegexCommandParser.TYPE_OPEN_APP, cmds.get(1).type);
    }

    @Test
    public void testSingleCommand_noSplit() {
        List<RegexCommandParser.ParsedCommand> cmds = parser.parse("open whatsapp");
        assertEquals(1, cmds.size());
        assertEquals(RegexCommandParser.TYPE_OPEN_APP, cmds.get(0).type);
    }

    @Test
    public void testEmptyInput() {
        List<RegexCommandParser.ParsedCommand> cmds = parser.parse("");
        assertEquals(0, cmds.size());

        cmds = parser.parse(null);
        assertEquals(0, cmds.size());
    }

    // ========================================
    // Wake Word Stripping
    // ========================================

    @Test
    public void testWakeWordStripping() {
        List<RegexCommandParser.ParsedCommand> cmds = parser.parse("hey assist open whatsapp");
        assertEquals(1, cmds.size());
        assertEquals(RegexCommandParser.TYPE_OPEN_APP, cmds.get(0).type);
        assertEquals("whatsapp", cmds.get(0).appName);
    }

    @Test
    public void testWakeWordStripping_okAssist() {
        List<RegexCommandParser.ParsedCommand> cmds = parser.parse("ok assist turn on wifi");
        assertEquals(1, cmds.size());
        assertEquals(RegexCommandParser.TYPE_TOGGLE, cmds.get(0).type);
    }

    // ========================================
    // Open App
    // ========================================

    @Test
    public void testOpenApp() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("open whatsapp");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_OPEN_APP, cmd.type);
        assertEquals("whatsapp", cmd.appName);
    }

    @Test
    public void testLaunchApp() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("launch youtube");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_OPEN_APP, cmd.type);
        assertEquals("youtube", cmd.appName);
    }

    @Test
    public void testStartApp() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("start spotify");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_OPEN_APP, cmd.type);
        assertEquals("spotify", cmd.appName);
    }

    @Test
    public void testOpenMultiWordApp() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("open play store");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_OPEN_APP, cmd.type);
        assertEquals("play store", cmd.appName);
    }

    // ========================================
    // Toggle
    // ========================================

    @Test
    public void testToggleOn() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("turn on wifi");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_TOGGLE, cmd.type);
        assertEquals(RegexCommandParser.TOGGLE_ON, cmd.toggleState);
        assertEquals(RegexCommandParser.FEATURE_WIFI, cmd.feature);
    }

    @Test
    public void testToggleOff() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("turn off bluetooth");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_TOGGLE, cmd.type);
        assertEquals(RegexCommandParser.TOGGLE_OFF, cmd.toggleState);
        assertEquals(RegexCommandParser.FEATURE_BLUETOOTH, cmd.feature);
    }

    @Test
    public void testToggleFlashlight() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("turn on flashlight");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_TOGGLE, cmd.type);
        assertEquals(RegexCommandParser.FEATURE_FLASHLIGHT, cmd.feature);
    }

    @Test
    public void testToggleTorch() {
        // "torch" should normalize to "flashlight"
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("turn on torch");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.FEATURE_FLASHLIGHT, cmd.feature);
    }

    @Test
    public void testEnableBluetooth() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("enable bluetooth");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_TOGGLE, cmd.type);
        assertEquals(RegexCommandParser.TOGGLE_ON, cmd.toggleState);
        assertEquals(RegexCommandParser.FEATURE_BLUETOOTH, cmd.feature);
    }

    @Test
    public void testDisableDND() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("disable do not disturb");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_TOGGLE, cmd.type);
        assertEquals(RegexCommandParser.TOGGLE_OFF, cmd.toggleState);
        assertEquals(RegexCommandParser.FEATURE_DND, cmd.feature);
    }

    // ========================================
    // Send Message
    // ========================================

    @Test
    public void testSendMessage() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("send message hello to john on whatsapp");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_SEND_MESSAGE, cmd.type);
        assertEquals("hello", cmd.messageText);
        assertEquals("john", cmd.contact);
        assertEquals("whatsapp", cmd.messagingApp);
    }

    @Test
    public void testSendMessageNoApp() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("send message hey there to mom");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_SEND_MESSAGE, cmd.type);
        assertEquals("hey there", cmd.messageText);
        assertEquals("mom", cmd.contact);
    }

    @Test
    public void testSendText() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("text john are you coming");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_SEND_MESSAGE, cmd.type);
        assertEquals("john", cmd.contact);
        assertEquals("are you coming", cmd.messageText);
    }

    // ========================================
    // Call
    // ========================================

    @Test
    public void testCall() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("call mom");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_CALL, cmd.type);
        assertEquals("mom", cmd.callTarget);
    }

    @Test
    public void testDialContact() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("dial john");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_CALL, cmd.type);
        assertEquals("john", cmd.callTarget);
    }

    // ========================================
    // Timer
    // ========================================

    @Test
    public void testTimerMinutes() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("set timer for 5 minutes");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_SET_TIMER, cmd.type);
        assertEquals(5 * 60 * 1000, cmd.durationMs);
    }

    @Test
    public void testTimerSeconds() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("set timer for 30 seconds");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_SET_TIMER, cmd.type);
        assertEquals(30 * 1000, cmd.durationMs);
    }

    @Test
    public void testTimerCombined() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("set timer for 2 minutes 30 seconds");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_SET_TIMER, cmd.type);
        assertEquals((2 * 60 + 30) * 1000, cmd.durationMs);
    }

    /**
     * FIX (7.4): Test that unparseable duration returns -1 sentinel.
     */
    @Test
    public void testTimerUnparseableDuration_returnsSentinel() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("set timer for a couple minutes");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_SET_TIMER, cmd.type);
        // "a couple minutes" doesn't match any numeric pattern
        assertEquals(-1, cmd.durationMs);
    }

    @Test
    public void testTimerNoDuration_returnsSentinel() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("set timer");
        // "set timer" without duration — the regex won't match since duration is required (.+?)
        // This tests the edge case
        assertNull(cmd); // The pattern requires at least some duration text
    }

    // ========================================
    // Navigate
    // ========================================

    @Test
    public void testNavigate() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("navigate to office");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_NAVIGATE, cmd.type);
        assertEquals("office", cmd.destination);
    }

    @Test
    public void testGoTo() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("go to home");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_NAVIGATE, cmd.type);
        assertEquals("home", cmd.destination);
    }

    // ========================================
    // Search
    // ========================================

    @Test
    public void testSearch() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("search for recipes");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_SEARCH, cmd.type);
        assertEquals("recipes", cmd.searchQuery);
    }

    @Test
    public void testGoogle() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("google weather today");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_SEARCH, cmd.type);
        assertEquals("weather today", cmd.searchQuery);
    }

    // ========================================
    // Close App
    // ========================================

    @Test
    public void testCloseApp() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("close whatsapp");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_CLOSE_APP, cmd.type);
        assertEquals("whatsapp", cmd.appName);
    }

    @Test
    public void testKillApp() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("kill chrome");
        assertNotNull(cmd);
        assertEquals(RegexCommandParser.TYPE_CLOSE_APP, cmd.type);
        assertEquals("chrome", cmd.appName);
    }

    // ========================================
    // Levenshtein Distance
    // ========================================

    @Test
    public void testLevenshtein_identical() {
        assertEquals(0, parser.calculateDistance("hello", "hello"));
    }

    @Test
    public void testLevenshtein_oneEdit() {
        assertEquals(1, parser.calculateDistance("hello", "hallo"));
    }

    @Test
    public void testLevenshtein_insertion() {
        assertEquals(1, parser.calculateDistance("hell", "hello"));
    }

    @Test
    public void testLevenshtein_deletion() {
        assertEquals(1, parser.calculateDistance("hello", "hell"));
    }

    @Test
    public void testLevenshtein_empty() {
        assertEquals(5, parser.calculateDistance("hello", ""));
        assertEquals(5, parser.calculateDistance("", "hello"));
    }

    @Test
    public void testLevenshtein_completelyDifferent() {
        assertEquals(5, parser.calculateDistance("abcde", "fghij"));
    }

    @Test
    public void testGetMaxDistance() {
        assertEquals(1, RegexCommandParser.getMaxDistance(1));
        assertEquals(1, RegexCommandParser.getMaxDistance(2));
        assertEquals(1, RegexCommandParser.getMaxDistance(3));
        assertEquals(1, RegexCommandParser.getMaxDistance(5));
        assertEquals(2, RegexCommandParser.getMaxDistance(6));
        assertEquals(3, RegexCommandParser.getMaxDistance(9));
        assertEquals(3, RegexCommandParser.getMaxDistance(10));
    }

    // ========================================
    // Unknown Commands
    // ========================================

    @Test
    public void testUnknownCommand() {
        RegexCommandParser.ParsedCommand cmd = parser.parseSingleCommand("what is the meaning of life");
        assertNull(cmd);
    }

    @Test
    public void testUnknownInSequence() {
        List<RegexCommandParser.ParsedCommand> cmds = parser.parse("open whatsapp and what time is it");
        assertEquals(2, cmds.size());
        assertEquals(RegexCommandParser.TYPE_OPEN_APP, cmds.get(0).type);
        assertEquals(RegexCommandParser.TYPE_UNKNOWN, cmds.get(1).type);
    }
}
