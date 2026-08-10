package org.pijota.tui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The banner's column arithmetic, at a width, without a terminal. This is the part that has actually gone wrong: the
 * meta was built at full length while only the title was cut to fit, so once the meta alone was wider than the terminal
 * the title floor guaranteed the overflow it existed to prevent.
 */
class StartupBannerTest {

    @Test
    void bannerSessionMetaReadsAtAGlanceWithoutTwoCompetingTimeFormats() {
        var item = new SessionItem(Path.of("/tmp/s.jsonl"), "id", "a session", System.currentTimeMillis(), false, 12,
                                        277);
        String meta = StartupBanner.bannerSessionMeta(item, 80);
        assertTrue(meta.contains("12 msgs"), meta);
        assertTrue(meta.contains("~277"), meta);
        // The selector's detail line also carries an absolute timestamp and the session id; at three banner rows a
        // second time format only competes with the relative age, and the id is not what you recognize a session by.
        assertFalse(meta.matches(".*\\b\\d{2}:\\d{2}\\b.*"), "no clock time: " + meta);
        assertFalse(meta.contains("id"), meta);
    }

    @Test
    void bannerSessionMetaGivesUpItsTailRatherThanOverflowANarrowRow() {
        var item = new SessionItem(Path.of("/tmp/s.jsonl"), "id", "scan the codebase",
                                        System.currentTimeMillis() - 3_600_000, false, 921, 349_000);
        // 30 columns is the width the banner actually broke at: two GROUP separators cost 4 and the title keeps 8.
        int budget = 30 - 4 - 8;
        String meta = StartupBanner.bannerSessionMeta(item, budget);
        assertTrue(AnsiWidth.visibleWidth(meta) <= budget, "meta must fit its budget: '" + meta + "'");
        // The age survives because it is what the banner is for; the token estimate is the first thing to go.
        assertTrue(meta.contains("ago"), meta);
        assertFalse(meta.contains("~349K"), "the tail gives way before the age does: " + meta);
    }

    @Test
    void bannerSessionMetaOmitsCountsForAnEmptySession() {
        var empty = new SessionItem(Path.of("/tmp/s.jsonl"), "id", "empty", System.currentTimeMillis(), false, 0, 0);
        String meta = StartupBanner.bannerSessionMeta(empty, 80);
        assertFalse(meta.contains("msg"), "0 msgs is noise, not information: " + meta);
        assertFalse(meta.endsWith("·") || meta.startsWith("·"), "no dangling separator: " + meta);
    }
}
