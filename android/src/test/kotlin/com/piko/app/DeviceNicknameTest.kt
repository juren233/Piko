package com.piko.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceNicknameTest {
    @Test
    fun generatorCreatesAdjectiveNounTitleAndFourDigitCode() {
        val generator = DeviceNicknameGenerator(FixedNicknameRandom(ints = listOf(0, 0, 7), fingerprints = listOf("fp-1")))

        val nickname = generator.generate(fingerprint = "fp-1")

        assertEquals("赤色星河", nickname.title)
        assertEquals("0007", nickname.code)
        assertEquals("赤色星河@0007", nickname.fullName)
        assertEquals("fp-1", nickname.fingerprint)
    }

    @Test
    fun wordPoolHasEnoughCombinations() {
        assertTrue(DeviceNicknameGenerator.combinationCount >= 40 * 40 * 10_000)
    }

    @Test
    fun repositoryPersistsFirstGeneratedNickname() {
        val storage = MemoryNicknameStorage()
        val repository = DeviceNicknameRepository(
            storage = storage,
            generator = DeviceNicknameGenerator(FixedNicknameRandom(ints = listOf(1, 2, 345), fingerprints = listOf("fp-2"))),
        )

        val first = repository.loadOrCreate()
        val second = repository.loadOrCreate()

        assertEquals(first, second)
        assertEquals("fp-2", second.fingerprint)
        assertEquals("0345", second.code)
    }

    @Test
    fun resetChangesNicknameButKeepsFingerprint() {
        val storage = MemoryNicknameStorage()
        val repository = DeviceNicknameRepository(
            storage = storage,
            generator = DeviceNicknameGenerator(
                FixedNicknameRandom(
                    ints = listOf(1, 2, 345, 3, 4, 6789),
                    fingerprints = listOf("stable-fp"),
                ),
            ),
        )
        val first = repository.loadOrCreate()

        val reset = repository.regenerate()

        assertNotEquals(first.title, reset.title)
        assertNotEquals(first.code, reset.code)
        assertEquals(first.fingerprint, reset.fingerprint)
        assertEquals(reset, repository.loadOrCreate())
    }

    private class MemoryNicknameStorage : DeviceNicknameStorage {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private class FixedNicknameRandom(
        ints: List<Int>,
        private val fingerprints: List<String>,
    ) : DeviceNicknameRandom {
        private val ints = ArrayDeque(ints)
        private var fingerprintIndex = 0

        override fun nextInt(bound: Int): Int {
            return ints.removeFirst().floorMod(bound)
        }

        override fun nextFingerprint(): String {
            return fingerprints[fingerprintIndex++]
        }
    }
}

private fun Int.floorMod(bound: Int): Int = ((this % bound) + bound) % bound
