package com.piko.app.platform

import android.content.Context
import com.piko.app.data.ReceiveMediaSaveLocation

private const val RECEIVE_PREFERENCES_NAME = "piko_receive_preferences"
private const val MEDIA_SAVE_LOCATION_KEY = "media_save_location"

class AndroidReceivePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        RECEIVE_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun loadMediaSaveLocation(): ReceiveMediaSaveLocation {
        return ReceiveMediaSaveLocation.fromStorageValue(
            preferences.getString(MEDIA_SAVE_LOCATION_KEY, null),
        )
    }

    fun saveMediaSaveLocation(location: ReceiveMediaSaveLocation) {
        preferences.edit()
            .putString(MEDIA_SAVE_LOCATION_KEY, location.storageValue)
            .apply()
    }
}
