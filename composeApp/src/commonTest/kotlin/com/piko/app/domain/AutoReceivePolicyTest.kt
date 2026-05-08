package com.piko.app.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoReceivePolicyTest {
    private val policy = AutoReceivePolicy()

    @Test
    fun allowsTrustedDevicesFromSameAccount() {
        val result = policy.canAutoReceive(
            relationship = TransferRelationship.SameAccount,
            receiverTrustLevel = DeviceTrustLevel.Trusted,
            senderRevoked = false,
            receiverHasReceiveDirectory = true,
            hasEnoughStorage = true,
        )

        assertTrue(result)
    }

    @Test
    fun blocksFriendTransfersEvenWhenReceiverIsTrusted() {
        val result = policy.canAutoReceive(
            relationship = TransferRelationship.Friend,
            receiverTrustLevel = DeviceTrustLevel.Trusted,
            senderRevoked = false,
            receiverHasReceiveDirectory = true,
            hasEnoughStorage = true,
        )

        assertFalse(result)
    }

    @Test
    fun blocksRemovedSenderDevice() {
        val result = policy.canAutoReceive(
            relationship = TransferRelationship.SameAccount,
            receiverTrustLevel = DeviceTrustLevel.Trusted,
            senderRevoked = true,
            receiverHasReceiveDirectory = true,
            hasEnoughStorage = true,
        )

        assertFalse(result)
    }
}

