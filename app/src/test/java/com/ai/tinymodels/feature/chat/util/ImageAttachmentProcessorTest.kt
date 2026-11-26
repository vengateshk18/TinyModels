package com.ai.tinymodels.feature.chat.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageAttachmentProcessorTest {

    @Test fun `exif rotation degrees map correctly`() {
        assertEquals(0, ImageAttachmentProcessor.exifRotationDegrees(1))
        assertEquals(90, ImageAttachmentProcessor.exifRotationDegrees(6))
        assertEquals(180, ImageAttachmentProcessor.exifRotationDegrees(3))
        assertEquals(270, ImageAttachmentProcessor.exifRotationDegrees(8))
        assertEquals(0, ImageAttachmentProcessor.exifRotationDegrees(0))
        assertEquals(0, ImageAttachmentProcessor.exifRotationDegrees(999))
    }

    @Test fun `target size downscales long edge preserving aspect`() {
        assertEquals(1000 to 500, ImageAttachmentProcessor.targetSize(2000, 1000, rotationDegrees = 0, maxEdge = 1000))
        assertEquals(500 to 250, ImageAttachmentProcessor.targetSize(2000, 1000, rotationDegrees = 0, maxEdge = 500))
        assertEquals(2000 to 1000, ImageAttachmentProcessor.targetSize(2000, 1000, rotationDegrees = 0, maxEdge = 4000))
    }

    @Test fun `target size accounts for rotation swapping dimensions`() {
        // 2000w x 1000h rotated 90 becomes 1000w x 2000h, then downscaled to maxEdge 1000 -> 500x1000
        assertEquals(500 to 1000, ImageAttachmentProcessor.targetSize(2000, 1000, rotationDegrees = 90, maxEdge = 1000))
        assertEquals(1000 to 500, ImageAttachmentProcessor.targetSize(2000, 1000, rotationDegrees = 180, maxEdge = 1000))
    }

    @Test fun `sample size halves until within limit`() {
        assertEquals(1, ImageAttachmentProcessor.calculateInSampleSize(1000, 500, maxEdge = 1568))
        assertEquals(2, ImageAttachmentProcessor.calculateInSampleSize(2000, 1000, maxEdge = 1568))
        assertEquals(4, ImageAttachmentProcessor.calculateInSampleSize(4000, 3000, maxEdge = 1568))
    }
}
