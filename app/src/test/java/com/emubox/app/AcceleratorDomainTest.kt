package com.emubox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 远程加速节点域名的校验规则（纯函数，不需要 Android 环境）。
 *
 * 这些域名会被拼进 "https://${domain}/$原始下载URL"，所以必须拦住
 * 带路径、带端口、带协议、带点段跳转的畸形值。
 */
class AcceleratorDomainTest {

    @Test
    fun `正常域名通过并规范化为小写`() {
        assertEquals("proxy.example.com", Accelerator.isValidProxyDomain("proxy.example.com"))
        assertEquals("fastly.jsdelivr.net", Accelerator.isValidProxyDomain("  Fastly.JSDelivr.NET  "))
        assertEquals("gh-proxy-1.example.cn", Accelerator.isValidProxyDomain("gh-proxy-1.example.cn"))
    }

    @Test
    fun `带路径或端口的一律拒绝`() {
        assertNull(Accelerator.isValidProxyDomain("evil.com/../other"))
        assertNull(Accelerator.isValidProxyDomain("evil.com:8080"))
        assertNull(Accelerator.isValidProxyDomain("https://evil.com"))
        assertNull(Accelerator.isValidProxyDomain("evil.com?x=1"))
    }

    @Test
    fun `畸形结构一律拒绝`() {
        assertNull(Accelerator.isValidProxyDomain(""))
        assertNull(Accelerator.isValidProxyDomain("nodot"))
        assertNull(Accelerator.isValidProxyDomain(".example.com"))
        assertNull(Accelerator.isValidProxyDomain("example.com."))
        assertNull(Accelerator.isValidProxyDomain("-bad.com"))
        assertNull(Accelerator.isValidProxyDomain("a..b"))
    }
}
