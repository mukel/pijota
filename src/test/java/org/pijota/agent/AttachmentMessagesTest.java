package org.pijota.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentMessagesTest {
    @Test
    void pathLabelOnlyMatchesUnsupportedImageExtensions() {
        assertEquals("HEIC", AttachmentMessages.unsupportedImagePathLabel("./photo.heic"));
        assertEquals("TIFF", AttachmentMessages.unsupportedImagePathLabel("scan.TIFF"));
        assertEquals("BMP", AttachmentMessages.unsupportedImagePathLabel("dir/image.bmp"));
        assertNull(AttachmentMessages.unsupportedImagePathLabel("./example.txt"));
    }

    @Test
    void mimeLabelStillHandlesImageSubtypes() {
        assertEquals("HEIC", AttachmentMessages.unsupportedImageLabel("image/heic"));
        assertEquals("BMP", AttachmentMessages.unsupportedImageLabel("image/bmp"));
    }
}
