package com.ubermicrostudios.messagesenhanced.push

import com.ubermicrostudios.messagesenhanced.push.ReplyPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyPolicyTest {
    @Test fun okIsSent() = assertEquals(Outcome.SENT, ReplyPolicy.decide(200, true, "SUCCESS", 0))
    @Test fun inFlightDuplicateIsSent() = assertEquals(Outcome.SENT, ReplyPolicy.decide(409, false, "SENDING", 1))
    @Test fun ioErrorReusesKey() = assertEquals(Outcome.RETRY_SAME_KEY, ReplyPolicy.decide(null, null, null, 0))
    @Test fun serverFailureNewKey() = assertEquals(Outcome.RETRY_NEW_KEY, ReplyPolicy.decide(409, false, "FAILED", 0))
    @Test fun okButUnsuccessfulNewKey() = assertEquals(Outcome.RETRY_NEW_KEY, ReplyPolicy.decide(200, false, "FAILED", 0))
    @Test fun authGivesUp() = assertEquals(Outcome.GIVE_UP, ReplyPolicy.decide(401, false, "", 0))
    @Test fun lastAttemptGivesUp() =
        assertEquals(Outcome.GIVE_UP, ReplyPolicy.decide(null, null, null, ReplyPolicy.MAX_ATTEMPTS - 1))
    @Test fun keys() {
        assertEquals("k", ReplyPolicy.keyFor("k", 0))
        assertEquals("k-r2", ReplyPolicy.keyFor("k", 2))
    }
    @Test fun parse() = assertEquals("SENDING", ReplyPolicy.parseStatus("{\"success\":false,\"status\":\"SENDING\"}"))
}
