package com.starringbox.staragent.core.platform

/** 打开系统默认浏览器；移动端未注入时返回 false。 */
expect fun platformOpenUrl(url: String): Boolean
