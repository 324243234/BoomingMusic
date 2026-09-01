/*
 * Copyright (c) 2024 Christians Martínez Alvarado
 */

package com.mardous.booming.ui.screen.library.home

import com.mardous.booming.data.repository.Repository
import org.koin.android.ext.android.inject
import kotlinx.coroutines.flow.firstOrNull
import com.mardous.booming.data.local.room.PlaylistEntity
import com.mardous.booming.data.local.room.SongEntity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mardous.booming.R
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.Artist
import com.mardous.booming.data.model.ContentType
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.Suggestion
import com.mardous.booming.databinding.FragmentHomeBinding
import com.mardous.booming.extensions.dp
import com.mardous.booming.extensions.isNullOrEmpty
import com.mardous.booming.extensions.navigation.albumDetailArgs
import com.mardous.booming.extensions.navigation.artistDetailArgs
import com.mardous.booming.extensions.navigation.asFragmentExtras
import com.mardous.booming.extensions.navigation.detailArgs
import com.mardous.booming.extensions.navigation.playlistDetailArgs
import com.mardous.booming.extensions.resources.addPaddingRelative
import com.mardous.booming.extensions.resources.destroyOnDetach
import com.mardous.booming.extensions.resources.primaryColor
import com.mardous.booming.extensions.resources.setupStatusBarForeground
import com.mardous.booming.extensions.setSupportActionBar
import com.mardous.booming.extensions.toHtml
import com.mardous.booming.extensions.topLevelTransition
import com.mardous.booming.ui.IAlbumCallback
import com.mardous.booming.ui.IArtistCallback
import com.mardous.booming.ui.IHomeCallback
import com.mardous.booming.ui.IScrollHelper
import com.mardous.booming.ui.ISongCallback
import com.mardous.booming.ui.adapters.HomeAdapter
import com.mardous.booming.ui.adapters.album.AlbumAdapter
import com.mardous.booming.ui.adapters.artist.ArtistAdapter
import com.mardous.booming.ui.adapters.song.SongAdapter
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.component.menu.onAlbumMenu
import com.mardous.booming.ui.component.menu.onAlbumsMenu
import com.mardous.booming.ui.component.menu.onArtistMenu
import com.mardous.booming.ui.component.menu.onArtistsMenu
import com.mardous.booming.ui.component.menu.onSongMenu
import com.mardous.booming.ui.component.menu.onSongsMenu
import com.mardous.booming.ui.screen.library.ReloadType

class HomeFragment : AbsMainActivityFragment(R.layout.fragment_home),
    View.OnClickListener, ISongCallback, IAlbumCallback, IArtistCallback, IHomeCallback, IScrollHelper {

    private var _binding: HomeBinding? = null
    private val binding get() = _binding!!
    private val repository: Repository by inject()

    private var homeAdapter: HomeAdapter? = null

    private val currentContent: SuggestedResult
        get() = libraryViewModel.getSuggestions().value ?: SuggestedResult.Idle

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val homeBinding = FragmentHomeBinding.bind(view)
        _binding = HomeBinding(homeBinding)
        binding.appBarLayout.setupStatusBarForeground()
        setSupportActionBar(binding.toolbar)
        topLevelTransition(view)

        setupTitle()
        setupListeners()
        checkForMargins()

        homeAdapter = HomeAdapter(arrayListOf(), this).also {
            it.registerAdapterDataObserver(adapterDataObserver)
        }
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = homeAdapter
            addPaddingRelative(bottom = 8.dp(resources))
            destroyOnDetach()
        }
        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            binding.recyclerView.updatePadding(
                bottom = it.getWithSpace(16.dp(resources), includeInsets = false)
            )
        }
        libraryViewModel.getSuggestions().apply {
            observe(viewLifecycleOwner) { result ->
                if (result.isLoading && homeAdapter.isNullOrEmpty) {
                    binding.progressIndicator.show()
                } else {
                    binding.progressIndicator.hide()
                }
                homeAdapter?.dataSet = result.data
            }
        }.also { liveData ->
            if (liveData.value == SuggestedResult.Idle) {
                libraryViewModel.forceReload(ReloadType.Suggestions)
            }
        }
        applyWindowInsetsFromView(view)
    }

    private val adapterDataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() { checkIsEmpty() }
    }

    private fun setupTitle() {
        binding.appBarLayout.toolbar.setNavigationOnClickListener {
            findNavController().navigate(R.id.nav_search)
        }
        val hexColor = String.format("#%06X", 0xFFFFFF and primaryColor())
        val appName = "Booming <font color=$hexColor>Music</font>".toHtml()
        binding.appBarLayout.title = appName
    }

    private fun setupListeners() {
        binding.myTopTracks.setOnClickListener(this)
        binding.lastAdded.setOnClickListener(this)
        binding.history.setOnClickListener(this)
        binding.shuffleButton.setOnClickListener(this)
        binding.root.findViewById<View>(R.id.dailyRecommendCard)?.setOnClickListener {
            handleDailyRecommendClick()
        }
    }

    private fun checkIsEmpty() {
        binding.empty.isVisible = !currentContent.isLoading && homeAdapter.isNullOrEmpty
    }

    private fun checkForMargins() {
        checkForMargins(binding.recyclerView)
    }

    override fun onClick(view: View) {
        when (view) {
            binding.myTopTracks -> findNavController().navigate(R.id.nav_detail_list, detailArgs(ContentType.TopTracks))
            binding.lastAdded -> findNavController().navigate(R.id.nav_detail_list, detailArgs(ContentType.RecentSongs))
            binding.history -> findNavController().navigate(R.id.nav_detail_list, detailArgs(ContentType.History))
            binding.shuffleButton -> libraryViewModel.allSongs().observe(viewLifecycleOwner) { playerViewModel.openAndShuffleQueue(it) }
        }
    }

    private fun handleDailyRecommendClick() {
        val appContext = requireContext().applicationContext
        val prefs = appContext.getSharedPreferences("netease_api_prefs", android.content.Context.MODE_PRIVATE)
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val lastDate = prefs.getString("last_daily_date", "")
        val plName = "网易云今日推荐"

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val existingPlaylists: List<PlaylistEntity> = repository.checkPlaylistExists(plName)
                var playlistId: Long? = existingPlaylists.firstOrNull()?.playListId

                if (playlistId != null && lastDate == todayStr) {
                    withContext(Dispatchers.Main) {
                        findNavController().navigate(R.id.nav_playlist_detail, com.mardous.booming.extensions.navigation.playlistDetailArgs(playlistId!!))
                    }
                    return@launch
                }

                if (existingPlaylists.isNotEmpty() && lastDate != todayStr) {
                    runCatching { repository.deletePlaylists(existingPlaylists) }
                    playlistId = null
                }

                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(appContext, "正在生成今日推荐歌单...", android.widget.Toast.LENGTH_SHORT).show()
                }

                val dailyJsonList = com.mardous.booming.data.network.NeteaseDailyApi.fetchDailyRecommend(appContext)
                if (dailyJsonList.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(appContext, "今日推荐为空，请检查网络或Cookie", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                playlistId = repository.createPlaylist(PlaylistEntity(playlistName = plName))
                val songEntities = mutableListOf<SongEntity>()
                var idOffset = 0L

                for (item in dailyJsonList) {
                    val songId = item.optLong("id", 0L)
                    if (songId == 0L) continue

                    val artistsArr = item.optJSONArray("ar") ?: item.optJSONArray("artists")
                    val artistName = if (artistsArr != null && artistsArr.length() > 0) {
                        (0 until artistsArr.length()).joinToString("/") { artistsArr.getJSONObject(it).optString("name") }
                    } else "未知歌手"

                    val albumName = item.optJSONObject("al")?.optString("name") ?: item.optJSONObject("album")?.optString("name") ?: "未知专辑"
                    val durationMs = item.optLong("dt", item.optLong("duration", 200000L)) 
                    
                    // 🌟 不再缓存动态 CDN，只保存静态 ID 代理链接，留给播放器即时解析
                    //val playUrl = "https://music.163.com/song/media/outer/url?id=$songId.mp3"
					val playUrl = "netease://$songId"

                    songEntities.add(
                        SongEntity(
                            id = (System.currentTimeMillis() * 1000) + idOffset++,
                            title = item.optString("name", "未知歌曲"),
                            artistName = artistName,
                            albumName = albumName,
                            duration = durationMs, 
                            data = playUrl,
                            playlistCreatorId = playlistId!!,
                            trackNumber = 0, year = 0, size = songId, 
                            dateAdded = System.currentTimeMillis(), dateModified = System.currentTimeMillis(),
                            albumId = -1L, artistId = -1L, albumArtist = artistName, genreName = "Netease"
                        )
                    )
                }

                repository.insertSongsInPlaylist(songEntities)
                prefs.edit().putString("last_daily_date", todayStr).apply()

                withContext(Dispatchers.Main) {
                    findNavController().navigate(R.id.nav_playlist_detail, com.mardous.booming.extensions.navigation.playlistDetailArgs(playlistId!!))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(appContext, "加载失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() { super.onResume(); checkForMargins() }
    override fun onPause() { super.onPause(); binding.recyclerView.stopScroll() }
    override fun onDestroyView() {
        super.onDestroyView()
        homeAdapter?.unregisterAdapterDataObserver(adapterDataObserver)
        binding.recyclerView.adapter = null
        binding.recyclerView.layoutManager = null
        homeAdapter = null; _binding = null
    }

    override fun onMediaContentChanged() { libraryViewModel.forceReload(ReloadType.Suggestions) }
    override fun onFavoriteContentChanged() { libraryViewModel.forceReload(ReloadType.Suggestions) }

   @Suppress("UNCHECKED_CAST")
    override fun createSuggestionAdapter(suggestion: Suggestion): RecyclerView.Adapter<*> {
        return when (suggestion.type) {
            ContentType.TopArtists, ContentType.RecentArtists -> ArtistAdapter(mainActivity, suggestion.items as List<Artist>, R.layout.item_artist, callback = this)
            ContentType.TopAlbums, ContentType.RecentAlbums -> AlbumAdapter(mainActivity, suggestion.items as List<Album>, R.layout.item_album_gradient, callback = this)
            ContentType.Favorites, ContentType.NotRecentlyPlayed -> SongAdapter(mainActivity, suggestion.items as List<Song>, R.layout.item_image, callback = this)
            else -> throw IllegalArgumentException("Unexpected suggestion type")
        }
    }

    override fun suggestionClick(suggestion: Suggestion) {
        when (suggestion.type) {
            ContentType.Favorites -> libraryViewModel.favoritePlaylist().observe(viewLifecycleOwner) { findNavController().navigate(R.id.nav_playlist_detail, playlistDetailArgs(it.playListId)) }
            else -> findNavController().navigate(R.id.nav_detail_list, detailArgs(suggestion.type))
        }
    }

    override fun songMenuItemClick(song: Song, menuItem: MenuItem, sharedElements: Array<Pair<View, String>>?): Boolean = song.onSongMenu(this, menuItem)
    override fun songsMenuItemClick(songs: List<Song>, menuItem: MenuItem) { songs.onSongsMenu(this, menuItem) }
    override fun albumClick(album: Album, sharedElements: Array<Pair<View, String>>?) { findNavController().navigate(R.id.nav_album_detail, albumDetailArgs(album.id), null, sharedElements.asFragmentExtras()) }
    override fun albumMenuItemClick(album: Album, menuItem: MenuItem, sharedElements: Array<Pair<View, String>>?): Boolean = album.onAlbumMenu(this, menuItem)
    override fun albumsMenuItemClick(albums: List<Album>, menuItem: MenuItem) { albums.onAlbumsMenu(this, menuItem) }
    override fun artistClick(artist: Artist, sharedElements: Array<Pair<View, String>>?) { findNavController().navigate(R.id.nav_artist_detail, artistDetailArgs(artist), null, sharedElements.asFragmentExtras()) }
    override fun artistMenuItemClick(artist: Artist, menuItem: MenuItem, sharedElements: Array<Pair<View, String>>?): Boolean = artist.onArtistMenu(this, menuItem)
    override fun artistsMenuItemClick(artists: List<Artist>, menuItem: MenuItem) { artists.onArtistsMenu(this, menuItem) }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_library, menu)
        menu.removeItem(R.id.action_scan)
        menu.removeItem(R.id.action_equalizer)
        menu.removeItem(R.id.action_grid_size)
        menu.removeItem(R.id.action_view_type)
        menu.removeItem(R.id.action_sort_order)
        menu.findItem(R.id.action_settings).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.action_settings) { findNavController().navigate(R.id.nav_settings); return true }
        return false
    }

    override fun scrollToTop() { binding.container.scrollTo(0, 0); binding.appBarLayout.setExpanded(true) }
}