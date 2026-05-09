package com.piko.app

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.UUID

private const val DEVICE_NICKNAME_PREFS = "piko_device_nickname"
private const val KEY_TITLE = "title"
private const val KEY_CODE = "code"
private const val KEY_FINGERPRINT = "fingerprint"

data class DeviceNickname(
    val title: String,
    val code: String,
    val fingerprint: String,
) {
    val fullName: String
        get() = "$title@$code"
}

internal class DeviceNicknameRepository(
    private val storage: DeviceNicknameStorage,
    private val generator: DeviceNicknameGenerator = DeviceNicknameGenerator(),
) {
    fun loadOrCreate(): DeviceNickname {
        val existing = readExisting()
        if (existing != null) {
            return existing
        }
        val fingerprint = storage.getString(KEY_FINGERPRINT)?.takeIf { it.isNotBlank() }
        return generator.generate(fingerprint = fingerprint ?: generator.nextFingerprint()).also(::save)
    }

    fun regenerate(): DeviceNickname {
        val fingerprint = storage.getString(KEY_FINGERPRINT)?.takeIf { it.isNotBlank() } ?: generator.nextFingerprint()
        return generator.generate(fingerprint = fingerprint).also(::save)
    }

    private fun readExisting(): DeviceNickname? {
        val title = storage.getString(KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        val code = storage.getString(KEY_CODE)?.takeIf { it.matches(Regex("\\d{4}")) } ?: return null
        val fingerprint = storage.getString(KEY_FINGERPRINT)?.takeIf { it.isNotBlank() } ?: return null
        return DeviceNickname(title = title, code = code, fingerprint = fingerprint)
    }

    private fun save(nickname: DeviceNickname) {
        storage.putString(KEY_TITLE, nickname.title)
        storage.putString(KEY_CODE, nickname.code)
        storage.putString(KEY_FINGERPRINT, nickname.fingerprint)
    }
}

internal class DeviceNicknameGenerator(
    private val random: DeviceNicknameRandom = SecureDeviceNicknameRandom,
) {
    fun generate(fingerprint: String = nextFingerprint()): DeviceNickname {
        return DeviceNickname(
            title = "${adjectives[random.nextInt(adjectives.size)]}${nouns[random.nextInt(nouns.size)]}",
            code = random.nextInt(10_000).toString().padStart(4, '0'),
            fingerprint = fingerprint,
        )
    }

    fun nextFingerprint(): String = random.nextFingerprint()

    companion object {
        val combinationCount: Int
            get() = adjectives.size * nouns.size * 10_000

        private val adjectives = listOf(
            "赤色",
            "清亮",
            "轻快",
            "温柔",
            "安静",
            "明朗",
            "灵巧",
            "松弛",
            "锋利",
            "柔软",
            "澄澈",
            "灿烂",
            "沉稳",
            "敏捷",
            "悠然",
            "热烈",
            "青蓝",
            "晴朗",
            "微光",
            "薄荷",
            "银白",
            "琥珀",
            "翠绿",
            "深空",
            "流云",
            "暖阳",
            "星辉",
            "锦瑟",
            "远山",
            "新雪",
            "晨雾",
            "暮色",
            "海盐",
            "月白",
            "花火",
            "竹青",
            "霜蓝",
            "橙光",
            "静好",
            "飞扬",
        )

        private val nouns = listOf(
            "星河",
            "山谷",
            "竹影",
            "海湾",
            "云帆",
            "月台",
            "风铃",
            "灯塔",
            "溪流",
            "花园",
            "书页",
            "岛屿",
            "晨星",
            "松林",
            "港口",
            "旅人",
            "音符",
            "纸鹤",
            "晴空",
            "贝壳",
            "锦鲤",
            "银杏",
            "山岚",
            "雪线",
            "茶盏",
            "木舟",
            "麦田",
            "星尘",
            "雨巷",
            "南风",
            "北辰",
            "长桥",
            "清泉",
            "花径",
            "云雀",
            "青石",
            "灯火",
            "白鹭",
            "秋水",
            "春山",
        )
    }
}

internal interface DeviceNicknameRandom {
    fun nextInt(bound: Int): Int
    fun nextFingerprint(): String
}

private object SecureDeviceNicknameRandom : DeviceNicknameRandom {
    private val random = SecureRandom()

    override fun nextInt(bound: Int): Int = random.nextInt(bound)

    override fun nextFingerprint(): String = UUID.randomUUID().toString()
}

internal interface DeviceNicknameStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

internal class AndroidDeviceNicknameStorage(context: Context) : DeviceNicknameStorage {
    private val preferences: SharedPreferences = context.getSharedPreferences(DEVICE_NICKNAME_PREFS, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}
