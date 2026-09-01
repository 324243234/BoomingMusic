/*
 * Copyright (c) 2024 Christians Martínez Alvarado
 */

package com.mardous.booming.data.local.room

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM PlaylistEntity WHERE playlist_name = :name")
    fun playlist(name: String): List<PlaylistEntity>

    // 🌟 核心隐身魔法 1：过滤掉日推临时歌单
    @Query("SELECT * FROM PlaylistEntity WHERE playlist_name != '网易云今日推荐'")
    suspend fun playlists(): List<PlaylistEntity>

    @Insert
    suspend fun createPlaylist(playlistEntity: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("UPDATE PlaylistEntity SET playlist_name = :name WHERE playlist_id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String)

    // 🌟 核心隐身魔法 2：过滤掉日推临时歌单
    @Transaction
    @Query("SELECT * FROM PlaylistEntity WHERE playlist_name != '网易云今日推荐'")
    suspend fun playlistsWithSongs(): List<PlaylistWithSongs>

    @Transaction
    @Query("SELECT * FROM PlaylistEntity WHERE playlist_id = :playlistId")
    fun playlistWithSongsObservable(playlistId: Long): LiveData<PlaylistWithSongs?>

    @Transaction
    @Query("SELECT * FROM PlaylistEntity WHERE playlist_id = :playlistId")
    fun playlistWithSongs(playlistId: Long): PlaylistWithSongs?

    @Transaction
    @Query("SELECT * FROM PlaylistEntity WHERE playlist_name LIKE :playlistName AND playlist_name != '网易云今日推荐'")
    fun searchPlaylists(playlistName: String): List<PlaylistWithSongs>

    @Transaction
    @Query("SELECT * FROM SongEntity WHERE playlist_creator_id = :playlistId AND title LIKE :songName")
    fun searchSongs(playlistId: Long, songName: String): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongsToPlaylist(songEntities: List<SongEntity>)

    @Query("SELECT * FROM SongEntity WHERE playlist_creator_id = :playlistId AND id = :songId LIMIT 1")
    suspend fun findSongInPlaylist(playlistId: Long, songId: Long): SongEntity?

    @Query("SELECT * FROM SongEntity WHERE playlist_creator_id = :playlistId AND id IN(:songIds)")
    suspend fun findSongsInPlaylist(playlistId: Long, songIds: List<Long>): List<SongEntity>

    @Query("SELECT * FROM SongEntity WHERE playlist_creator_id = :playlistId ORDER BY song_key asc")
    fun songsFromPlaylistObservable(playlistId: Long): LiveData<List<SongEntity>>

    @Query("SELECT * FROM SongEntity WHERE playlist_creator_id = :playlistId ORDER BY song_key asc")
    suspend fun songsFromPlaylist(playlistId: Long): List<SongEntity>

    @Transaction
    suspend fun removeSongsAndDeletePlaylists(playlistIds: List<Long>) {
        deleteAllSongsFromPlaylists(playlistIds)
        deletePlaylists(playlistIds)
    }

    @Query("DELETE FROM PlaylistEntity WHERE playlist_id IN (:playlistIds)")
    suspend fun deletePlaylists(playlistIds: List<Long>)

    @Query("DELETE FROM SongEntity WHERE playlist_creator_id = :playlistId AND id IN(:songIds)")
    suspend fun deleteSongsFromPlaylist(playlistId: Long, songIds: List<Long>)

    @Delete
    suspend fun deleteSongsFromPlaylists(songs: List<SongEntity>)

    @Query("DELETE FROM SongEntity WHERE playlist_creator_id IN(:playlistIds)")
    suspend fun deleteAllSongsFromPlaylists(playlistIds: List<Long>)

    @Query("DELETE FROM SongEntity WHERE id IN (:songIds)")
    suspend fun deleteSongsFromAllPlaylists(songIds: List<Long>)

    @RewriteQueriesToDropUnusedColumns
    @Query("""
    SELECT * FROM SongEntity,
    (SELECT playlist_id FROM PlaylistEntity WHERE playlist_name = :playlistName LIMIT 1) AS playlist
    WHERE playlist_creator_id = playlist.playlist_id""")
    fun favoritesSongsFlow(playlistName: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM SongEntity WHERE playlist_creator_id = :playlistId")
    fun favoritesSongs(playlistId: Long): List<SongEntity>

    @Query("SELECT EXISTS(SELECT * FROM PlaylistEntity WHERE playlist_id = :playlistId)")
    fun checkPlaylistExists(playlistId: Long): LiveData<Boolean>

    @Query("SELECT EXISTS(SELECT * FROM SongEntity WHERE id = :songId AND playlist_creator_id = :playlistId)")
    fun checkSongExistInPlaylist(playlistId: Long, songId: Long): Boolean

    @Query("SELECT * FROM SongEntity WHERE id IN (:songIds)")
    suspend fun findSongsByIds(songIds: List<Long>): List<SongEntity>

    @Update
    suspend fun updateSongs(songs: List<SongEntity>)
}