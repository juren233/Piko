package com.piko.app

import com.piko.app.transport.P2PFailureReason
import com.piko.app.transport.P2PTransferDiagnostic
import com.piko.app.transport.StunProbeResult
import com.piko.app.transport.p2pDirectTransportAttemptPlan
import com.piko.app.transport.aggregateStunProbeResults
import com.piko.app.transport.computeStunErrorRate
import com.piko.app.transport.crossNetworkDiagnosis
import com.piko.app.transport.detectRemoteOnlyMdns
import com.piko.app.transport.detectSymmetricNatSuspect
import com.piko.app.transport.isGatheringIncomplete
import com.piko.app.transport.parseStunProbeResults
import com.piko.app.transport.prioritizeP2PIceCandidateForSignaling
import com.piko.app.transport.shouldContinueWaitingForIce
import com.piko.app.transport.toStunProbeResultOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class P2PFailureReasonTest {

    private fun baseDiagnostic(): P2PTransferDiagnostic =
        P2PTransferDiagnostic(
            offerSent = true,
            answerReceived = true,
            localIceCount = 5,
            remoteIceCount = 4,
            iceServerUrls = "stun:stun.l.google.com:19302,stun:stun.cloudflare.com:3478,stun:piko-ipv6.juren233.top:3478",
            localCandidateTypes = "host,srflx",
            remoteCandidateTypes = "host,srflx",
            localCandidateDetails = "type=host/proto=udp/addr=private-ipv4/port=10000;type=srflx/proto=udp/addr=public-ipv4/port=20000/raddr=private-ipv4/rport=10000",
            remoteCandidateDetails = "type=host/proto=udp/addr=private-ipv4/port=30000;type=srflx/proto=udp/addr=public-ipv4/port=40000/raddr=private-ipv4/rport=30000",
            iceConnectionState = "FAILED",
            iceGatheringState = "GATHERING",
            selectedCandidatePair = "none",
        )

    @Test
    fun detectsLocalNoCandidate() {
        val diag = baseDiagnostic().copy(localCandidateTypes = "none")
        assertEquals(P2PFailureReason.LOCAL_NO_CANDIDATE, crossNetworkDiagnosis(diag))
    }

    @Test
    fun noLocalAndRemoteCandidatesFailsBeforeFullRestartWindow() {
        val diag = baseDiagnostic().copy(
            localIceCount = 0,
            remoteIceCount = 0,
            localCandidateTypes = "none",
            remoteCandidateTypes = "none",
            selectedCandidatePair = "none",
            iceConnectionState = "FAILED",
            iceGatheringState = "COMPLETE",
            gatheringIncomplete = false,
        )

        assertEquals(P2PFailureReason.LOCAL_NO_CANDIDATE, crossNetworkDiagnosis(diag))
        assertFalse(shouldContinueWaitingForIce(diag))
    }

    @Test
    fun detectsRemoteNoCandidate() {
        val diag = baseDiagnostic().copy(remoteCandidateTypes = "none")
        assertEquals(P2PFailureReason.REMOTE_NO_CANDIDATE, crossNetworkDiagnosis(diag))
    }

    @Test
    fun detectsStunServersDegradedWhenHalfPlusError() {
        val diag = baseDiagnostic().copy(stunErrorRate = 0.5)
        assertEquals(P2PFailureReason.STUN_SERVERS_DEGRADED, crossNetworkDiagnosis(diag))
    }

    @Test
    fun detectsSymmetricNatSuspect() {
        val diag = baseDiagnostic().copy(symmetricNatSuspect = true)
        assertEquals(P2PFailureReason.SYMMETRIC_NAT, crossNetworkDiagnosis(diag))
    }

    @Test
    fun detectsRemoteOnlyMdns() {
        val diag = baseDiagnostic().copy(remoteOnlyMdns = true)
        assertEquals(P2PFailureReason.REMOTE_MDNS_ONLY, crossNetworkDiagnosis(diag))
    }

    @Test
    fun detectsConnectivityCheckFailedWhenGatheringIncompleteAndNoPair() {
        val diag = baseDiagnostic().copy(
            gatheringIncomplete = true,
            selectedCandidatePair = "none",
        )
        assertEquals(P2PFailureReason.CONNECTIVITY_CHECK_FAILED, crossNetworkDiagnosis(diag))
    }

    @Test
    fun detectsCgnatBothSides() {
        val diag = baseDiagnostic().copy(
            gatheringIncomplete = false,
            localCandidateDetails = "type=srflx/proto=udp/addr=cgnat-ipv4/port=11900",
            remoteCandidateDetails = "type=srflx/proto=udp/addr=cgnat-ipv4/port=22900",
        )
        assertEquals(P2PFailureReason.CGNAT_BOTH_SIDES, crossNetworkDiagnosis(diag))
    }

    @Test
    fun fallsThroughToNatIncompatible() {
        val diag = baseDiagnostic().copy(
            gatheringIncomplete = false,
            selectedCandidatePair = "local=srflx|remote=srflx",
            localCandidateDetails = "type=srflx/proto=udp/addr=public-ipv4/port=11900",
            remoteCandidateDetails = "type=srflx/proto=udp/addr=public-ipv4/port=22900",
        )
        assertEquals(P2PFailureReason.NAT_INCOMPATIBLE, crossNetworkDiagnosis(diag))
    }

    @Test
    fun stunRateThresholdNotMetFallsThrough() {
        val diag = baseDiagnostic().copy(
            stunErrorRate = 0.33,
            gatheringIncomplete = true,
            selectedCandidatePair = "none",
        )
        assertEquals(P2PFailureReason.CONNECTIVITY_CHECK_FAILED, crossNetworkDiagnosis(diag))
    }

    @Test
    fun reproUserLogScenarioYieldsStunDegraded() {
        // 重放 transfer-1778770375656 的诊断快照：4 个 STUN，2 个失效。
        val diag = baseDiagnostic().copy(
            iceServerUrls = "stun:piko-ipv6.juren233.top:3478,stun:stun.l.google.com:19302,stun:stun.cloudflare.com:3478,stun:stun.cloudflare.com:53",
            iceCandidateErrors = listOf(
                "url=stun:piko-ipv6.juren233.top:3478|address=0.0.0.x:41987|code=701|text=STUN host lookup received error.",
                "url=stun:piko-ipv6.juren233.top:3478|address=0.0.0.x:42481|code=701|text=STUN host lookup received error.",
                "url=stun:stun.cloudflare.com:53|address=0.0.0.x:42481|code=701|text=STUN binding request timed out.",
                "url=stun:stun.cloudflare.com:53|address=[0:0:0:x:x:x:x:x]:57407|code=701|text=STUN binding request timed out.",
            ).joinToString(";"),
            stunErrorRate = 0.5, // 2/4 STUN urls report errors.
        )
        assertEquals(P2PFailureReason.STUN_SERVERS_DEGRADED, crossNetworkDiagnosis(diag))
    }

    @Test
    fun computeStunErrorRate_handlesUserLog() {
        val urls = "stun:piko-ipv6.juren233.top:3478,stun:stun.l.google.com:19302,stun:stun.cloudflare.com:3478,stun:stun.cloudflare.com:53"
        val errors = listOf(
            "url=stun:piko-ipv6.juren233.top:3478|address=0.0.0.x:41987|code=701|text=STUN host lookup received error.",
            "url=stun:piko-ipv6.juren233.top:3478|address=0.0.0.x:42481|code=701|text=STUN host lookup received error.",
            "url=stun:stun.cloudflare.com:53|address=0.0.0.x:42481|code=701|text=STUN binding request timed out.",
            "url=stun:stun.cloudflare.com:53|address=[0:0:0:x:x:x:x:x]:57407|code=701|text=STUN binding request timed out.",
        ).joinToString(";")
        assertEquals(0.5, computeStunErrorRate(urls, errors), 1e-9)
    }

    @Test
    fun computeStunErrorRate_afterFix_belowThreshold() {
        // 修复后只剩 3 个 STUN；假设 piko-ipv6 仍对 IPv4 报错。
        val urls = "stun:stun.l.google.com:19302,stun:stun.cloudflare.com:3478,stun:piko-ipv6.juren233.top:3478"
        val errors = "url=stun:piko-ipv6.juren233.top:3478|address=0.0.0.x:41987|code=701|text=STUN host lookup received error."
        val rate = computeStunErrorRate(urls, errors)
        assertTrue(rate < 0.5, "expected stunErrorRate < 0.5 after fix, got $rate")
    }

    @Test
    fun computeStunErrorRate_returnsZeroWhenNoErrors() {
        assertEquals(0.0, computeStunErrorRate("stun:a:3478,stun:b:3478", "none"), 1e-9)
        assertEquals(0.0, computeStunErrorRate("stun:a:3478,stun:b:3478", ""), 1e-9)
    }

    @Test
    fun prioritizeP2PIceCandidateForSignaling_boostsPublicIpv6HostCandidate() {
        val candidate =
            "candidate:1 1 udp 1686052607 2409:8a20:1234:5678::1 54545 typ host generation 0"

        val prioritized = prioritizeP2PIceCandidateForSignaling(candidate)

        assertEquals(
            "candidate:1 1 udp 2130706431 2409:8a20:1234:5678::1 54545 typ host generation 0",
            prioritized,
        )
    }

    @Test
    fun prioritizeP2PIceCandidateForSignaling_keepsStunCandidateUnchanged() {
        val candidate =
            "candidate:2 1 udp 1686052607 203.0.113.10 62000 typ srflx raddr 192.168.1.10 rport 54545"

        assertEquals(candidate, prioritizeP2PIceCandidateForSignaling(candidate))
    }

    @Test
    fun p2pDirectTransportAttemptPlan_prefersQuicThenTcpBeforeWebRtcFallbackWhenNativeLinked() {
        val plan = p2pDirectTransportAttemptPlan(xquicAvailable = true)

        assertEquals(
            listOf("quic_ipv6_direct", "quic_udp_punch", "tcp_ipv6_direct", "webrtc_ipv6_host", "webrtc_stun"),
            plan.map { it.name },
        )
        assertEquals(5L, plan.first { it.name == "quic_ipv6_direct" }.timeoutSeconds)
        assertEquals(5L, plan.first { it.name == "quic_udp_punch" }.timeoutSeconds)
        assertEquals(5L, plan.first { it.name == "tcp_ipv6_direct" }.timeoutSeconds)
    }

    @Test
    fun p2pDirectTransportAttemptPlan_skipsQuicWhenNativeBridgeIsUnavailable() {
        val plan = p2pDirectTransportAttemptPlan(xquicAvailable = false)

        assertEquals(
            listOf("tcp_ipv6_direct", "webrtc_ipv6_host", "webrtc_stun"),
            plan.map { it.name },
        )
    }

    @Test
    fun isGatheringIncomplete_detectsNonCompleteStates() {
        assertTrue(isGatheringIncomplete("GATHERING"))
        assertTrue(isGatheringIncomplete("NEW"))
        assertTrue(isGatheringIncomplete("unknown"))
        assertFalse(isGatheringIncomplete("COMPLETE"))
        assertFalse(isGatheringIncomplete("complete"))
    }

    @Test
    fun detectSymmetricNatSuspect_userLogAndroidIsCone() {
        // Android 侧：rport=41987→port=11901, rport=42481→port=12004。每个内网端口仅一个外网端口 → 非对称。
        val details = listOf(
            "type=srflx/proto=udp/addr=public-ipv4/port=11901/raddr=private-ipv4/rport=41987",
            "type=srflx/proto=udp/addr=public-ipv4/port=12004/raddr=private-ipv4/rport=42481",
        ).joinToString(";")
        assertFalse(detectSymmetricNatSuspect(details))
    }

    @Test
    fun detectSymmetricNatSuspect_truePositive() {
        // 同一内网端口 rport=41987 被映射到两个不同的公网端口 → 对称 NAT 指纹。
        val details = listOf(
            "type=srflx/proto=udp/addr=public-ipv4/port=11901/raddr=private-ipv4/rport=41987",
            "type=srflx/proto=udp/addr=public-ipv4/port=11902/raddr=private-ipv4/rport=41987",
        ).joinToString(";")
        assertTrue(detectSymmetricNatSuspect(details))
    }

    @Test
    fun detectSymmetricNatSuspect_ignoresRportZero() {
        // iOS rport=0：无法判定，不应假阳性。
        val details = listOf(
            "type=srflx/proto=udp/addr=public-ipv4/port=51745/raddr=public-ipv4/rport=0",
            "type=srflx/proto=udp/addr=public-ipv4/port=54567/raddr=public-ipv4/rport=0",
        ).joinToString(";")
        assertFalse(detectSymmetricNatSuspect(details))
    }

    @Test
    fun detectSymmetricNatSuspect_handlesBlankInput() {
        assertFalse(detectSymmetricNatSuspect(""))
        assertFalse(detectSymmetricNatSuspect("none"))
    }

    @Test
    fun detectRemoteOnlyMdns_truePositive() {
        // 远端仅暴露一条 mdns host candidate。
        assertTrue(
            detectRemoteOnlyMdns(
                remoteTypes = "host",
                remoteDetails = "type=host/proto=udp/addr=mdns/port=57053",
            ),
        )
    }

    @Test
    fun detectRemoteOnlyMdns_falseWhenSrflxPresent() {
        // 用户日志：iOS 既有 mdns host 又有 srflx → 不是仅 mdns。
        assertFalse(
            detectRemoteOnlyMdns(
                remoteTypes = "host,srflx",
                remoteDetails = "type=host/proto=udp/addr=mdns/port=57053;type=srflx/proto=udp/addr=public-ipv4/port=51745",
            ),
        )
    }

    @Test
    fun detectRemoteOnlyMdns_falseWhenHostIsPrivate() {
        assertFalse(
            detectRemoteOnlyMdns(
                remoteTypes = "host",
                remoteDetails = "type=host/proto=udp/addr=private-ipv4/port=33000",
            ),
        )
    }

    @Test
    fun failureReasonsHaveDistinctTitles() {
        val titles = P2PFailureReason.values().map { it.title }
        assertEquals(titles.size, titles.toSet().size, "failure reason titles must be distinct")
    }

    // Multi-STUN probe result parsing and stability tests

    @Test
    fun toStunProbeResultOrNull_parsesSuccessRecord() {
        val record = "stun:stun.cloudflare.com:3478|true|203.0.113.10|51000||250"
        val result = record.toStunProbeResultOrNull()
        assertNotNull(result)
        assertEquals("stun:stun.cloudflare.com:3478", result.serverUrl)
        assertTrue(result.success)
        assertEquals("203.0.113.10", result.mappedHost)
        assertEquals(51000, result.mappedPort)
        assertEquals(null, result.error)
        assertEquals(250L, result.elapsedMs)
    }

    @Test
    fun toStunProbeResultOrNull_parsesFailureRecord() {
        val record = "stun:stun.l.google.com:19302|false|||timeout|700"
        val result = record.toStunProbeResultOrNull()
        assertNotNull(result)
        assertFalse(result.success)
        assertEquals(null, result.mappedHost)
        assertEquals("timeout", result.error)
    }

    @Test
    fun parseStunProbeResults_parsesMultipleRecords() {
        val raw = "stun:s1.com:3478|true|1.2.3.4|51000||200;stun:s2.com:3478|true|1.2.3.4|51000||210"
        val results = raw.parseStunProbeResults()
        assertEquals(2, results.size)
        assertTrue(results.all { it.success })
    }

    @Test
    fun parseStunProbeResults_returnsEmptyOnNullInput() {
        assertEquals(emptyList<StunProbeResult>(), null.parseStunProbeResults())
        assertEquals(emptyList<StunProbeResult>(), "".parseStunProbeResults())
    }

    @Test
    fun aggregateStunProbeResults_stableWhenSameAddressFromTwoStuns() {
        val results = listOf(
            StunProbeResult("stun:s1.com:3478", true, "203.0.113.10", 51000, null, 200L),
            StunProbeResult("stun:s2.com:3478", true, "203.0.113.10", 51000, null, 210L),
        )
        val agg = results.aggregateStunProbeResults()
        assertEquals("success", agg.udpProbeResult)
        assertEquals("stable", agg.mappingBehavior)
        assertEquals(2, agg.stunSuccessCount)
        assertEquals(0, agg.stunErrorCount)
        assertEquals(1, agg.candidates.size)
        assertTrue(agg.candidates.first().mappingStable)
    }

    @Test
    fun aggregateStunProbeResults_portDependentWhenSameHostDifferentPort() {
        val results = listOf(
            StunProbeResult("stun:s1.com:3478", true, "203.0.113.10", 51000, null, 200L),
            StunProbeResult("stun:s2.com:3478", true, "203.0.113.10", 51001, null, 210L),
        )
        val agg = results.aggregateStunProbeResults()
        assertEquals("port_dependent", agg.mappingBehavior)
        assertEquals(2, agg.candidates.size)
    }

    @Test
    fun aggregateStunProbeResults_addressAndPortDependentWhenDifferentHosts() {
        val results = listOf(
            StunProbeResult("stun:s1.com:3478", true, "1.2.3.4", 51000, null, 200L),
            StunProbeResult("stun:s2.com:3478", true, "5.6.7.8", 51001, null, 210L),
        )
        val agg = results.aggregateStunProbeResults()
        assertEquals("address_and_port_dependent", agg.mappingBehavior)
    }

    @Test
    fun aggregateStunProbeResults_failedWhenAllProbesFail() {
        val results = listOf(
            StunProbeResult("stun:s1.com:3478", false, null, 0, "timeout", 700L),
            StunProbeResult("stun:s2.com:3478", false, null, 0, "timeout", 700L),
        )
        val agg = results.aggregateStunProbeResults()
        assertEquals("failed", agg.udpProbeResult)
        assertEquals("unknown", agg.mappingBehavior)
        assertEquals(0, agg.stunSuccessCount)
        assertEquals(2, agg.stunErrorCount)
        assertTrue(agg.candidates.isEmpty())
    }

    @Test
    fun aggregateStunProbeResults_unknownBehaviorWithSingleSuccessProbe() {
        val results = listOf(
            StunProbeResult("stun:s1.com:3478", true, "203.0.113.10", 51000, null, 200L),
        )
        val agg = results.aggregateStunProbeResults()
        assertEquals("success", agg.udpProbeResult)
        assertEquals("unknown", agg.mappingBehavior)
        assertFalse(agg.candidates.first().mappingStable, "single-probe candidate must stay unknown, not stable")
    }

    @Test
    fun aggregateStunProbeResults_stableCandidateHasHigherPriorityThanUnknownAndUnstable() {
        val stable = listOf(
            StunProbeResult("stun:s1.com:3478", true, "1.2.3.4", 51000, null, 200L),
            StunProbeResult("stun:s2.com:3478", true, "1.2.3.4", 51000, null, 210L),
        ).aggregateStunProbeResults()
        val unknown = listOf(
            StunProbeResult("stun:s1.com:3478", true, "1.2.3.4", 51000, null, 200L),
        ).aggregateStunProbeResults()
        val unstable = listOf(
            StunProbeResult("stun:s1.com:3478", true, "1.2.3.4", 51000, null, 200L),
            StunProbeResult("stun:s2.com:3478", true, "1.2.3.4", 51001, null, 210L),
        ).aggregateStunProbeResults()
        assertTrue(
            stable.candidates.first().priority > unknown.candidates.first().priority,
            "stable candidate priority must be higher than unknown",
        )
        assertTrue(
            unknown.candidates.first().priority > unstable.candidates.first().priority,
            "unknown candidate priority must be higher than unstable",
        )
    }

    @Test
    fun aggregateStunProbeResults_multipleTargetsYieldMultipleCandidatesForUnstable() {
        val results = listOf(
            StunProbeResult("stun:s1.com:3478", true, "1.2.3.4", 51000, null, 200L),
            StunProbeResult("stun:s2.com:3478", true, "1.2.3.4", 51001, null, 210L),
            StunProbeResult("stun:s3.com:3478", false, null, 0, "timeout", 700L),
        )
        val agg = results.aggregateStunProbeResults()
        assertEquals(2, agg.stunSuccessCount)
        assertEquals(1, agg.stunErrorCount)
        assertEquals(2, agg.candidates.size, "each unique address:port yields one candidate")
    }
}
