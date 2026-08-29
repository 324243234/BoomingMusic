/*
 * Copyright (c) 2024 Christians Martínez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming.ui.screen.library.home

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

/**
 * @author Christians M. A. (mardous)
 */
class HomeFragment : AbsMainActivityFragment(R.layout.fragment_home),
    View.OnClickListener,
    ISongCallback,
    IAlbumCallback,
    IArtistCallback,
    IHomeCallback,
    IScrollHelper {

    private var _binding: HomeBinding? = null
    private val binding get() = _binding!!

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
        override fun onChanged() {
            checkIsEmpty()
        }
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
		view?.findViewById<View>(R.id.dailyRecommendCard)?.setOnClickListener(this)
    }

    private fun checkIsEmpty() {
        binding.empty.isVisible = !currentContent.isLoading && homeAdapter.isNullOrEmpty
    }

    private fun checkForMargins() {
        checkForMargins(binding.recyclerView)
    }

    override fun onClick(view: View) {
        when (view) {
		
		R.id.dailyRecommendCard -> {
    val appContext = requireContext().applicationContext
    val toast = android.widget.Toast.makeText(appContext, "正在获取今日专属推荐...", android.widget.Toast.LENGTH_SHORT)
    toast.show()

    viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val dailyJsonList = com.mardous.booming.data.network.NeteaseDailyApi.fetchDailyRecommend()
        val dailySongs = mutableListOf<com.mardous.booming.data.model.Song>()

        var idOffset = 0L
        for (item in dailyJsonList) {
            val songId = item.optLong("id", 0L)
            if (songId == 0L) continue
            
            // 组装歌手名
            val artistsArr = item.optJSONArray("ar")
            val artistName = if (artistsArr != null && artistsArr.length() > 0) {
                (0 until artistsArr.length()).joinToString("/") { artistsArr.getJSONObject(it).optString("name") }
            } else "未知歌手"

            val albumName = item.optJSONObject("al")?.optString("name") ?: "未知专辑"
            val picUrl = item.optJSONObject("al")?.optString("picUrl") ?: ""
            // 默认网易云播放直链拼接规则
            val playUrl = "https://music.163.com/song/media/outer/url?id=$songId.mp3"

            dailySongs.add(
                com.mardous.booming.data.model.Song(
                    // 🌟 使用极大正数偏移时间戳，绝对避免与本地 Room 数据库冲突
                    id = (System.currentTimeMillis() * 1000) + idOffset++, 
                    title = item.optString("name", "未知歌曲"),
                    artistName = artistName,
                    albumName = albumName,
                    duration = item.optLong("dt", 0L), // 毫秒
                    data = playUrl, 
                    albumId = -1L, artistId = -1L, albumArtist = artistName,
                    genreName = "Netease" // ⚠️ 致命标签：用于触发后续的下载拦截器
					size = songId // 🌟 核心补丁：把真实的网易云 ID 伪装在文件大小属性里
                )
            )
        }

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            toast.cancel()
            if (dailySongs.isNotEmpty()) {
                // 送入播放器，由于含 HTTP 直链，底层 ExoPlayer 及车机会将其视为网络流正常播放
                playerViewModel.openQueue(dailySongs, position = 0, startPlaying = true)
            } else {
                android.widget.Toast.makeText(appContext, "今日推荐为空，请检查网络", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
		
            binding.myTopTracks -> {
                findNavController().navigate(R.id.nav_detail_list, detailArgs(ContentType.TopTracks))
            }

            binding.lastAdded -> {
                findNavController().navigate(R.id.nav_detail_list, detailArgs(ContentType.RecentSongs))
            }

            binding.history -> {
                findNavController().navigate(R.id.nav_detail_list, detailArgs(ContentType.History))
            }

            binding.shuffleButton -> {
                libraryViewModel.allSongs().observe(viewLifecycleOwner) {
                    playerViewModel.openAndShuffleQueue(it)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkForMargins()
    }

    override fun onPause() {
        super.onPause()
        binding.recyclerView.stopScroll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        homeAdapter?.unregisterAdapterDataObserver(adapterDataObserver)
        binding.recyclerView.adapter = null
        binding.recyclerView.layoutManager = null
        homeAdapter = null
        _binding = null
    }

    override fun onMediaContentChanged() {
        libraryViewModel.forceReload(ReloadType.Suggestions)
    }

    override fun onFavoriteContentChanged() {
        libraryViewModel.forceReload(ReloadType.Suggestions)
    }

    @Suppress("UNCHECKED_CAST")
    override fun createSuggestionAdapter(suggestion: Suggestion): RecyclerView.Adapter<*> {
        return when (suggestion.type) {
            ContentType.TopArtists,
            ContentType.RecentArtists -> ArtistAdapter(
                activity = mainActivity,
                dataSet = (suggestion.items as List<Artist>),
                itemLayoutRes = R.layout.item_artist,
                callback = this
            )

            ContentType.TopAlbums,
            ContentType.RecentAlbums -> AlbumAdapter(
                activity = mainActivity,
                dataSet = (suggestion.items as List<Album>),
                itemLayoutRes = R.layout.item_album_gradient,
                callback = this
            )

            ContentType.Favorites,
            ContentType.NotRecentlyPlayed -> SongAdapter(
                activity = mainActivity,
                dataSet = (suggestion.items as List<Song>),
                itemLayoutRes = R.layout.item_image,
                callback = this
            )

            else -> throw IllegalArgumentException("Unexpected suggestion type: ${suggestion.type}")
        }
    }

    override fun suggestionClick(suggestion: Suggestion) {
        when (suggestion.type) {
            ContentType.Favorites -> {
                libraryViewModel.favoritePlaylist().observe(viewLifecycleOwner) {
                    findNavController().navigate(R.id.nav_playlist_detail, playlistDetailArgs(it.playListId))
                }
            }

            else -> {
                findNavController().navigate(R.id.nav_detail_list, detailArgs(suggestion.type))
            }
        }
    }

    override fun songMenuItemClick(
        song: Song,
        menuItem: MenuItem,
        sharedElements: Array<Pair<View, String>>?
    ): Boolean = song.onSongMenu(this, menuItem)

    override fun songsMenuItemClick(songs: List<Song>, menuItem: MenuItem) {
        songs.onSongsMenu(this, menuItem)
    }

    override fun albumClick(album: Album, sharedElements: Array<Pair<View, String>>?) {
        findNavController().navigate(
            R.id.nav_album_detail,
            albumDetailArgs(album.id),
            null,
            sharedElements.asFragmentExtras()
        )
    }

    override fun albumMenuItemClick(
        album: Album,
        menuItem: MenuItem,
        sharedElements: Array<Pair<View, String>>?
    ): Boolean = album.onAlbumMenu(this, menuItem)

    override fun albumsMenuItemClick(albums: List<Album>, menuItem: MenuItem) {
        albums.onAlbumsMenu(this, menuItem)
    }

    override fun artistClick(artist: Artist, sharedElements: Array<Pair<View, String>>?) {
        findNavController().navigate(
            R.id.nav_artist_detail,
            artistDetailArgs(artist),
            null,
            sharedElements.asFragmentExtras()
        )
    }

    override fun artistMenuItemClick(
        artist: Artist,
        menuItem: MenuItem,
        sharedElements: Array<Pair<View, String>>?
    ): Boolean = artist.onArtistMenu(this, menuItem)

    override fun artistsMenuItemClick(artists: List<Artist>, menuItem: MenuItem) {
        artists.onArtistsMenu(this, menuItem)
    }

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
        if (menuItem.itemId == R.id.action_settings) {
            findNavController().navigate(R.id.nav_settings)
            return true
        }
        return false
    }

    override fun scrollToTop() {
        binding.container.scrollTo(0, 0)
        binding.appBarLayout.setExpanded(true)
    }
}