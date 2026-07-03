package org.telegram.messenger.askan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

/**
 * Regression tests for the AskanFilter decision logic — the core of the product's
 * filtering promise. These lock in the behaviour that bypasses historically came from:
 * case-insensitive matching, fail-closed defaults, numeric-id vs username, allow/block
 * precedence, and word-boundary content filtering.
 *
 * Runs on the JVM (no device): the filter is built via its private no-arg constructor and
 * its in-memory sets are populated by reflection — mirroring what loadFromCache produces
 * (server-side values are stored lowercased/@-stripped, so the sets hold normalized keys).
 */
public class AskanFilterTest {

    private AskanFilter filter;

    @Before
    public void setUp() throws Exception {
        Constructor<AskanFilter> c = AskanFilter.class.getDeclaredConstructor();
        c.setAccessible(true);
        filter = c.newInstance();
    }

    // ── reflection helpers ────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private void seed(String fieldName, String... values) throws Exception {
        Field f = AskanFilter.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        ((Collection<String>) f.get(filter)).addAll(Arrays.asList(values));
    }

    private void setFlag(String fieldName, boolean value) throws Exception {
        Field f = AskanFilter.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.setBoolean(filter, value);
    }

    private static TLRPC.Chat channel(long id, String username) {
        TLRPC.TL_channel ch = new TLRPC.TL_channel();
        ch.id = id; ch.username = username; ch.broadcast = true; ch.megagroup = false;
        ch.title = username;
        return ch;
    }

    private static TLRPC.Chat megagroup(long id, String username) {
        TLRPC.TL_channel ch = new TLRPC.TL_channel();
        ch.id = id; ch.username = username; ch.broadcast = false; ch.megagroup = true;
        ch.title = username;
        return ch;
    }

    private static TLRPC.Chat basicGroup(long id) {
        TLRPC.TL_chat g = new TLRPC.TL_chat();
        g.id = id; g.title = "group";
        return g;
    }

    private static TLRPC.User user(long id, String username, boolean bot) {
        TLRPC.TL_user u = new TLRPC.TL_user();
        u.id = id; u.username = username; u.bot = bot;
        return u;
    }

    // ── invariant #1: case-insensitive matching ───────────────────────────────
    @Test
    public void allowList_isCaseInsensitive() throws Exception {
        seed("globalAllow", "technews"); // server stores lowercased
        assertTrue(filter.isChannelAllowed("@TechNews"));
        assertTrue(filter.isChannelAllowed("TechNews"));
        assertTrue(filter.isChannelAllowed("technews"));
        assertFalse(filter.isChannelAllowed("othernews"));
    }

    @Test
    public void approvedChannel_withMixedCaseUsername_isNotBlocked() throws Exception {
        seed("globalAllow", "goodchan");
        assertFalse(filter.isChatBlocked(channel(100, "GoodChan"), null));
    }

    // ── allow by numeric id vs username ────────────────────────────────────────
    @Test
    public void explicitlyAllowed_byIdOrUsername() throws Exception {
        seed("globalAllow", "technews", "555");
        assertTrue(filter.isExplicitlyAllowed("555", null));        // by id
        assertTrue(filter.isExplicitlyAllowed("999", "@TechNews")); // by username
        assertFalse(filter.isExplicitlyAllowed("999", "@nope"));
    }

    // ── fail-closed: unknown channel is blocked ────────────────────────────────
    @Test
    public void unknownChannel_isBlocked() throws Exception {
        assertTrue(filter.isChatBlocked(channel(100, "random"), null));
    }

    @Test
    public void allowedChannel_byId_isNotBlocked() throws Exception {
        seed("userAllow", "100");
        assertFalse(filter.isChatBlocked(channel(100, "random"), null));
    }

    // ── megagroup rules ────────────────────────────────────────────────────────
    @Test
    public void unknownMegagroup_isBlocked() throws Exception {
        assertTrue(filter.isChatBlocked(megagroup(200, "chat"), null));
    }

    @Test
    public void megagroup_linkedToApprovedChannel_isNotBlocked() throws Exception {
        seed("globalAllow", "300"); // the linked channel id
        TLRPC.TL_channelFull full = new TLRPC.TL_channelFull();
        full.linked_chat_id = 300;
        assertFalse(filter.isChatBlocked(megagroup(201, "discussion"), full));
    }

    // ── basic groups are not filtered (only explicit block applies) ────────────
    @Test
    public void basicGroup_isNotFiltered() throws Exception {
        assertFalse(filter.isChatBlocked(basicGroup(77), null));
    }

    @Test
    public void basicGroup_explicitlyBlocked_isBlocked() throws Exception {
        seed("blockedChats", "77");
        assertTrue(filter.isChatBlocked(basicGroup(77), null));
    }

    // ── block precedence: explicit block wins over allow ───────────────────────
    @Test
    public void explicitBlock_beatsAllow() throws Exception {
        seed("globalAllow", "goodchan");
        seed("blockedChats", "goodchan");
        assertTrue(filter.isChatBlocked(channel(100, "GoodChan"), null));
    }

    // ── users / bots ───────────────────────────────────────────────────────────
    @Test
    public void normalUser_isNotBlocked() throws Exception {
        assertFalse(filter.isUserBlocked(user(500, "person", false)));
    }

    @Test
    public void unknownBot_isBlocked() throws Exception {
        assertTrue(filter.isUserBlocked(user(600, "somebot", true)));
    }

    @Test
    public void allowedBot_isNotBlocked() throws Exception {
        seed("userAllow", "okbot");
        assertFalse(filter.isUserBlocked(user(600, "OkBot", true)));
    }

    @Test
    public void explicitlyBlockedUser_isBlocked() throws Exception {
        seed("blockedChats", "700");
        assertTrue(filter.isUserBlocked(user(700, "person", false)));
    }

    // ── content word filter (word-boundary, toggle) ────────────────────────────
    @Test
    public void blockedWord_matchesOnBoundary_notSubstring() throws Exception {
        setFlag("contentFilterEnabled", true);
        seed("blockedWords", "spam");
        assertTrue(filter.containsBlockedWord("this is spam"));
        assertFalse(filter.containsBlockedWord("spammer paradise")); // boundary, not substring
        assertFalse(filter.containsBlockedWord("totally clean text"));
    }

    @Test
    public void contentFilterDisabled_neverMatches() throws Exception {
        setFlag("contentFilterEnabled", false);
        seed("blockedWords", "spam");
        assertFalse(filter.containsBlockedWord("this is spam"));
    }

    // ── block-reason explanation (must mirror the block decision) ──────────────
    @Test
    public void reason_explicitBlock() throws Exception {
        seed("blockedChats", "goodchan");
        assertEquals(AskanFilter.BlockReason.EXPLICIT,
                filter.getChatBlockReason(channel(100, "GoodChan"), null));
    }

    @Test
    public void reason_notApprovedChannel() throws Exception {
        assertEquals(AskanFilter.BlockReason.NOT_APPROVED,
                filter.getChatBlockReason(channel(100, "random"), null));
    }

    @Test
    public void reason_approvedChannel_notBlocked() throws Exception {
        seed("globalAllow", "goodchan");
        assertEquals(AskanFilter.BlockReason.NOT_BLOCKED,
                filter.getChatBlockReason(channel(100, "GoodChan"), null));
    }

    @Test
    public void reason_basicGroup_notBlocked() throws Exception {
        assertEquals(AskanFilter.BlockReason.NOT_BLOCKED,
                filter.getChatBlockReason(basicGroup(77), null));
    }

    @Test
    public void reason_unknownBot_notApproved() throws Exception {
        assertEquals(AskanFilter.BlockReason.NOT_APPROVED,
                filter.getUserBlockReason(user(600, "somebot", true)));
    }

    @Test
    public void reason_normalUser_notBlocked() throws Exception {
        assertEquals(AskanFilter.BlockReason.NOT_BLOCKED,
                filter.getUserBlockReason(user(500, "person", false)));
    }
}
