package org.pijota.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the shared HTML-entity decoding used by the web tools. */
class WebToolsTest {

    @Test
    void decodesTheFullEntitySet() {
        assertEquals("a & b < c > d \" e ' f ' g   h", WebTools
                                        .decodeEntities("a &amp; b &lt; c &gt; d &quot; e &#39; f &apos; g &nbsp; h"));
    }

    @Test
    void bothApostropheEntitiesDecode() {
        // The correctness fix: &apos; (previously dropped by websearch) and &#39; both become '.
        assertEquals("it's it's", WebTools.decodeEntities("it&apos;s it&#39;s"));
    }

    @Test
    void ampersandIsDecodedFirst() {
        // Documented ordering: &amp; runs first, so an encoded "&amp;lt;" collapses through to '<'.
        assertEquals("<", WebTools.decodeEntities("&amp;lt;"));
    }

    @Test
    void textWithoutEntitiesIsUnchanged() {
        assertEquals("plain text, no entities", WebTools.decodeEntities("plain text, no entities"));
    }

    @Test
    void nullPassesThrough() {
        assertNull(WebTools.decodeEntities(null));
    }
}
