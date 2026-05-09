package com.piko.app

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSendHttpRouteTest {
    @Test
    fun parsesPathAndQueryFromUploadRequestLine() {
        val route = LocalSendHttpRoute.parse(
            "POST /api/localsend/v2/upload?sessionId=session-1&fileId=file-a&token=token-a HTTP/1.1",
        )

        assertEquals("POST", route.method)
        assertEquals("/api/localsend/v2/upload", route.path)
        assertEquals("session-1", route.query.getValue("sessionId"))
        assertEquals("file-a", route.query.getValue("fileId"))
        assertEquals("token-a", route.query.getValue("token"))
    }

    @Test
    fun decodesEscapedQueryValues() {
        val route = LocalSendHttpRoute.parse(
            "POST /api/localsend/v2/cancel?sessionId=session%20one HTTP/1.1",
        )

        assertEquals("session one", route.query.getValue("sessionId"))
    }
}
