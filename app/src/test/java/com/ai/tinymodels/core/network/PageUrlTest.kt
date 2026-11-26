package com.ai.tinymodels.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageUrlTest {

    @Test fun `accepts public http and https urls`() {
        assertTrue(PageUrl.isFetchable("https://example.com/a"))
        assertTrue(PageUrl.isFetchable("http://93.184.216.34/page"))
    }

    @Test fun `rejects non-http schemes and malformed urls`() {
        assertFalse(PageUrl.isFetchable("file:///etc/passwd"))
        assertFalse(PageUrl.isFetchable("javascript:alert(1)"))
        assertFalse(PageUrl.isFetchable("data:text/html,hi"))
        assertFalse(PageUrl.isFetchable(""))
        assertFalse(PageUrl.isFetchable("not a url"))
    }

    @Test fun `rejects loopback and local hosts`() {
        assertFalse(PageUrl.isFetchable("http://localhost:8080/"))
        assertFalse(PageUrl.isFetchable("http://127.0.0.1/"))
        assertFalse(PageUrl.isFetchable("http://[::1]/"))
        assertFalse(PageUrl.isFetchable("http://printer.local/status"))
    }

    @Test fun `rejects private ipv4 ranges`() {
        assertFalse(PageUrl.isFetchable("http://10.0.0.5/"))
        assertFalse(PageUrl.isFetchable("http://192.168.1.1/"))
        assertFalse(PageUrl.isFetchable("http://172.16.0.1/"))
        assertFalse(PageUrl.isFetchable("http://172.31.255.255/"))
        assertTrue(PageUrl.isFetchable("http://172.32.0.1/"))
    }

    @Test fun `rejects urls with embedded credentials`() {
        assertFalse(PageUrl.isFetchable("https://user:pass@example.com/"))
    }
}
