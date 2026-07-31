package org.pijota.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModalityTest {

    @Test
    void mimeMapsToModality() {
        assertEquals(Modality.IMAGE, Modality.fromMime("image/png"));
        assertEquals(Modality.AUDIO, Modality.fromMime("audio/mpeg"));
        assertEquals(Modality.VIDEO, Modality.fromMime("video/mp4"));
        assertEquals(Modality.PDF, Modality.fromMime("application/pdf"));
        assertEquals(Modality.TEXT, Modality.fromMime("text/plain"));
        assertEquals(Modality.TEXT, Modality.fromMime(null));
    }

    @Test
    void onlyJpegPngGifWebpAreSupportedImages() {
        assertTrue(Modality.isSupportedImage("image/jpeg"));
        assertTrue(Modality.isSupportedImage("image/png"));
        assertTrue(Modality.isSupportedImage("image/gif"));
        assertTrue(Modality.isSupportedImage("image/webp"));
        assertTrue(Modality.isSupportedImage("IMAGE/PNG"), "case-insensitive");
        // provider/model limitation: these formats are rejected (no native transcoding in the static image)
        assertFalse(Modality.isSupportedImage("image/heic"));
        assertFalse(Modality.isSupportedImage("image/tiff"));
        assertFalse(Modality.isSupportedImage("image/bmp"));
        assertFalse(Modality.isSupportedImage(null));
    }
}
