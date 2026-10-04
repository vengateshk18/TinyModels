package com.example.tinymodels.core.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryUtilsTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    @Test
    fun `small file fits on small device`() {
        // 500MB file on 4GB RAM: 500MB * 1.25 = 625MB + 400MB floor < 4GB.
        assertTrue(MemoryUtils.canLoadModelOfSize(500L * mb, 4L * gb))
    }

    @Test
    fun `large file does not fit on small device`() {
        // 3GB file on 4GB RAM: 3GB * 1.25 = 3.75GB + 400MB floor > 4GB.
        assertFalse(MemoryUtils.canLoadModelOfSize(3L * gb, 4L * gb))
    }

    @Test
    fun `large file fits on large device`() {
        // 3GB file on 8GB RAM: 3.75GB + 400MB < 8GB.
        assertTrue(MemoryUtils.canLoadModelOfSize(3L * gb, 8L * gb))
    }

    @Test
    fun `boundary - exactly at the limit passes`() {
        // required + floor == total → still loadable (>= comparison).
        val total = 4L * gb
        val fileSize = ((total - 400L * mb) / 1.25).toLong()
        assertTrue(MemoryUtils.canLoadModelOfSize(fileSize, total))
    }

    @Test
    fun `boundary - just over the limit fails`() {
        val total = 4L * gb
        // +1MB overshoot (a +1 byte bump is lost in the ×1.25 rounding).
        val fileSize = ((total - 400L * mb) / 1.25).toLong() + mb
        assertFalse(MemoryUtils.canLoadModelOfSize(fileSize, total))
    }

    @Test
    fun `unknown size is assumed loadable`() {
        assertTrue(MemoryUtils.canLoadModelOfSize(0L, 1L * gb))
        assertTrue(MemoryUtils.canLoadModelOfSize(-1L, 1L * gb))
    }
}
