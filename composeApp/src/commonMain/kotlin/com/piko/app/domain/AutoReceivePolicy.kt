package com.piko.app.domain

class AutoReceivePolicy {
    fun canAutoReceive(
        relationship: TransferRelationship,
        receiverTrustLevel: DeviceTrustLevel,
        senderRevoked: Boolean,
        receiverHasReceiveDirectory: Boolean,
        hasEnoughStorage: Boolean,
    ): Boolean {
        return relationship == TransferRelationship.SameAccount &&
            receiverTrustLevel == DeviceTrustLevel.Trusted &&
            !senderRevoked &&
            receiverHasReceiveDirectory &&
            hasEnoughStorage
    }
}

