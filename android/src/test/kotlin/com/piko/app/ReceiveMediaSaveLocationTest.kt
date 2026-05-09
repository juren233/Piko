package com.piko.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ReceiveMediaSaveLocationTest {
    @Test
    fun albumPreferenceSendsImagesAndVideosToAlbum() {
        assertEquals(
            ReceiveSaveDestination.Album,
            ReceiveMediaSaveLocation.Album.destinationFor("image/jpeg"),
        )
        assertEquals(
            ReceiveSaveDestination.Album,
            ReceiveMediaSaveLocation.Album.destinationFor("video/mp4"),
        )
    }

    @Test
    fun folderPreferenceKeepsMediaInFolder() {
        assertEquals(
            ReceiveSaveDestination.Folder,
            ReceiveMediaSaveLocation.Folder.destinationFor("image/png"),
        )
        assertEquals(
            ReceiveSaveDestination.Folder,
            ReceiveMediaSaveLocation.Folder.destinationFor("video/quicktime"),
        )
    }

    @Test
    fun nonMediaAlwaysStaysInFolder() {
        assertEquals(
            ReceiveSaveDestination.Folder,
            ReceiveMediaSaveLocation.Album.destinationFor("application/pdf"),
        )
        assertEquals(
            ReceiveSaveDestination.Folder,
            ReceiveMediaSaveLocation.Album.destinationFor(""),
        )
    }
}
