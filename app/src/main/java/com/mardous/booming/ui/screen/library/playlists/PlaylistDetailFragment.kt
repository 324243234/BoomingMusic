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

package com.mardous.booming.ui.screen.library.playlists

import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialArcMotion
import com.google.android.material.transition.MaterialContainerTransform
import com.h6ah4i.android.widget.advrecyclerview.animator.RefactoredDefaultItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager
import com.h6ah4i.android.widget.advrecyclerview.utils.WrapperAdapterUtils
import com.mardous.booming.R
import com.mardous.booming.coil.playlistImage
import com.mardous.booming.data.local.room.PlaylistWithSongs
import com.mardous.booming.data.mapper.searchFilter
import com.mardous.booming.data.mapper.toSongEntity
import com.mardous.booming.data.mapper.toSongs
import com.mardous.booming.data.mapper.toSongsEntity
import com.mardous.booming.data.model.Song
import com.mardous.booming.databinding.FragmentPlaylistDetailBinding
import com.mardous.booming.extensions.applyHorizontalWindowInsets
import com.mardous.booming.extensions.isLandscape
import com.mardous.booming.extensions.isNullOrEmpty
import com.mardous.booming.extensions.materialSharedAxis
import com.mardous.booming.extensions.media.isFavorites
import com.mardous.booming.extensions.media.playlistInfo
import com.mardous.booming.extensions.navigation.searchArgs
import com.mardous.booming.extensions.resources.createFastScroller
import com.mardous.booming.extensions.resources.removeHorizontalMarginIfRequired
import com.mardous.booming.extensions.resources.surfaceColor
import com.mardous.booming.extensions.setSupportActionBar
import com.mardous.booming.extensions.showToast
import com.mardous.booming.core.model.shuffle.OpenShuffleMode
import com.mardous.booming.data.local.repository.LyricsRepository
import com.mardous.booming.ui.ISongCallback
import com.mardous.booming.ui.adapters.song.PlaylistSongAdapter
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.component.menu.onPlaylistMenu
import com.mardous.booming.ui.component.menu.onSongMenu
import com.mardous.booming.ui.component.menu.onSongsMenu
import com.mardous.booming.ui.dialogs.playlists.RemoveFromPlaylistDialog
import com.mardous.booming.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import java.io.File
import java.lang.StringBuilder

/**
 * @author Christians M. A. (mardous)
 */
class PlaylistDetailFragment : AbsMainActivityFragment(R.layout.fragment_playlist_detail),
    ISongCallback {

    private val arguments by navArgs<PlaylistDetailFragmentArgs>()
    private val detailViewModel by viewModel<PlaylistDetailViewModel> {
        parametersOf(arguments.playlistId)
    }
    
    // 🌟 注入歌词仓库，用于刷新 TTML 缓存
    private val lyricsRepository: LyricsRepository by inject()

    private var _binding: FragmentPlaylistDetailBinding? = null
    private val binding get() = _binding!!

    private var playlist: PlaylistWithSongs = PlaylistWithSongs.Empty

    private var playlistSongAdapter: PlaylistSongAdapter? = null
    private var wrappedAdapter: RecyclerView.Adapter<*>? = null
    private var recyclerViewDragDropManager: RecyclerViewDragDropManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedElementEnterTransition = MaterialContainerTransform(requireContext(), true).apply {
            drawingViewId = R.id.fragment_container
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(surfaceColor())
            setPathMotion(MaterialArcMotion())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlaylistDetailBinding.bind(view)

        setupButtons()
        setupRecyclerView()

        materialSharedAxis(view)
        view.applyHorizontalWindowInsets()

        binding.header.image.removeHorizontalMarginIfRequired()

        setSupportActionBar(binding.toolbar)

        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            binding.recyclerView.updatePadding(bottom = it.getWithSpace())
        }

        detailViewModel.getPlaylist().observe(viewLifecycleOwner) { playlistWithSongs ->
            playlist = playlistWithSongs
            binding.header.title.text = playlist.playlistEntity.playlistName
            val description = playlist.playlistEntity.description
            if (!description.isNullOrEmpty()) {
                binding.header.description.text = description
                binding.header.description.isVisible = true
            } else {
                binding.header.description.text = null
                binding.header.description.isGone = true
            }
            binding.collapsingAppBarLayout.title = playlist.playlistEntity.playlistName
            binding.header.subtitle.text = playlist.songs.toSongs().playlistInfo(requireContext())
            binding.header.image.playlistImage(playlist)
        }
        
        detailViewModel.getSongs().observe(viewLifecycleOwner) { songsEntity ->
            binding.progressIndicator.hide()
            val newSongs = songsEntity.toSongs()
            playlistSongAdapter?.dataSet = newSongs
            
            // 🌟 核心机制：只要数据库歌曲列表变动（进入、添加、删除、存库刷新），立刻自动覆写 M3U 文件
            val playlistName = playlist.playlistEntity.playlistName
            if (playlistName.isNotEmpty() && newSongs.isNotEmpty()) {
                syncPlaylistToLocalM3u(playlistName, newSongs, isManualExport = false)
            }
        }
        
        detailViewModel.playlistExists().observe(viewLifecycleOwner) {
            if (!it) {
                findNavController().navigateUp()
            }
        }
    }

    private fun checkIsEmpty() {
        binding.empty.isVisible = playlistSongAdapter?.isNullOrEmpty == true
    }

    private fun setupButtons() {
        binding.header.playAction.setOnClickListener {
            playlistSongAdapter?.dataSet?.let {
                playerViewModel.openQueue(it, shuffleMode = OpenShuffleMode.Off)
            }
        }
        binding.header.shuffleAction.setOnClickListener {
            playlistSongAdapter?.dataSet?.let {
                playerViewModel.openAndShuffleQueue(it)
            }
        }
        binding.header.searchAction?.setOnClickListener {
            findNavController().navigate(
                R.id.nav_search,
                searchArgs(playlist.playlistEntity.searchFilter(requireContext()))
            )
        }
    }

    private fun setupRecyclerView() {
        playlistSongAdapter = PlaylistSongAdapter(
            activity = mainActivity,
            dataSet = emptyList(),
            itemLayoutRes = R.layout.item_list,
            isLockDrag = Preferences.lockedPlaylists,
            callback = this
        )
        
        recyclerViewDragDropManager = RecyclerViewDragDropManager().also { dragDropManager ->
            // 🌟 监听长按拖拽结束：松手瞬间将排序更新进数据库
            dragDropManager.setOnItemDragEventListener(object : RecyclerViewDragDropManager.OnItemDragEventListener {
                override fun onItemDragStarted(position: Int) {}
                override fun onItemDragPositionChanged(fromPosition: Int, toPosition: Int) {}
                override fun onItemDragFinished(fromPosition: Int, toPosition: Int, result: Boolean) {
                    if (fromPosition != toPosition) {
                        // 保存入库后会触发 getSongs().observe，从而自动执行上面的 syncPlaylistToLocalM3u 覆盖本地文件
                        playlistSongAdapter?.saveSongs(playlist.playlistEntity)
                    }
                }
                override fun onItemDragMoveDistanceUpdated(offsetX: Int, offsetY: Int) {}
            })
            
            wrappedAdapter = dragDropManager.createWrappedAdapter(playlistSongAdapter!!)
        }
        
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = wrappedAdapter
        binding.recyclerView.itemAnimator = RefactoredDefaultItemAnimator()
        binding.recyclerView.createFastScroller()
        recyclerViewDragDropManager?.attachRecyclerView(binding.recyclerView)
        playlistSongAdapter!!.registerAdapterDataObserver(adapterDataObserver)
    }

    private val adapterDataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            checkIsEmpty()
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_playlist_detail, menu)
    }

    override fun onPrepareMenu(menu: Menu) {
        playlist.let {
            if (it.playlistEntity.isFavorites(requireContext())) {
                menu.removeItem(R.id.action_delete_playlist)
            }
        }
        if (playlistSongAdapter?.isLockDrag == true) {
            menu.findItem(R.id.action_lock)
                ?.setIcon(R.drawable.ic_lock_24dp)
        } else {
            menu.findItem(R.id.action_lock)
                ?.setIcon(R.drawable.ic_lock_open_24dp)
        }
        if (!isLandscape()) {
            menu.removeItem(R.id.action_search)
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        // 🌟 1. 拦截“导出播放列表”事件，强行覆盖本地 M3U 文件，不新建！
        if (menuItem.itemId == R.id.action_export_playlist) {
            val currentSongs = playlistSongAdapter?.dataSet
            val playlistName = playlist.playlistEntity.playlistName
            if (!currentSongs.isNullOrEmpty() && playlistName.isNotEmpty()) {
                syncPlaylistToLocalM3u(playlistName, currentSongs, isManualExport = true)
            } else {
                Toast.makeText(requireContext(), "播放列表为空或名称无效，无法导出", Toast.LENGTH_SHORT).show()
            }
            return true
        }

        // 2. 获取当前适配器中的歌曲列表
        val currentSongs = playlistSongAdapter?.dataSet
        
        // 3. 拦截排序菜单，进行稳妥的内存重排
        if (!currentSongs.isNullOrEmpty()) {
            val sortedList = when (menuItem.itemId) {
                R.id.action_sort_by_title_asc -> currentSongs.sortedBy { it.title }
                R.id.action_sort_by_title_desc -> currentSongs.sortedByDescending { it.title }
                R.id.action_sort_by_artist_asc -> currentSongs.sortedBy { it.artistName }
                R.id.action_sort_by_artist_desc -> currentSongs.sortedByDescending { it.artistName }
                R.id.action_sort_by_album_asc -> currentSongs.sortedBy { it.albumName }
                R.id.action_sort_by_album_desc -> currentSongs.sortedByDescending { it.albumName }
                R.id.action_sort_by_duration_asc -> currentSongs.sortedBy { it.duration }
                R.id.action_sort_by_duration_desc -> currentSongs.sortedByDescending { it.duration }
                R.id.action_sort_by_date_asc -> currentSongs.sortedBy { it.dateAdded }
                R.id.action_sort_by_date_desc -> currentSongs.sortedByDescending { it.dateAdded }
                else -> null
            }

            if (sortedList != null) {
                recyclerViewDragDropManager?.cancelDrag()
                playlistSongAdapter?.dataSet = sortedList
                binding.recyclerView.adapter?.notifyDataSetChanged()
                binding.recyclerView.scrollToPosition(0)
                
                // 🌟 菜单点击排序后，立刻写盘覆盖。同样会触发自动同步覆盖 M3U。
                playlistSongAdapter?.saveSongs(playlist.playlistEntity)
                
                return true
            }
        }

        // 4. 其他常规菜单项逻辑
        return when (menuItem.itemId) {
            android.R.id.home -> {
                findNavController().navigateUp()
                true
            }
            R.id.action_search -> {
                findNavController().navigate(
                    R.id.nav_search,
                    searchArgs(playlist.playlistEntity.searchFilter(requireContext()))
                )
                true
            }
            R.id.action_lock -> {
                val lockedPlaylists = !Preferences.lockedPlaylists
                Preferences.lockedPlaylists = lockedPlaylists
                if (lockedPlaylists) {
                    menuItem.setIcon(R.drawable.ic_lock_24dp)
                    showToast(R.string.playlist_locked)
                } else {
                    menuItem.setIcon(R.drawable.ic_lock_open_24dp)
                    showToast(R.string.playlist_unlocked)
                }
                playlistSongAdapter?.setLockDrag(lockedPlaylists)
                true
            }
            else -> playlist.onPlaylistMenu(this, menuItem)
        }
    }

    override fun songMenuItemClick(
        song: Song,
        menuItem: MenuItem,
        sharedElements: Array<Pair<View, String>>?
    ): Boolean {
        return when (menuItem.itemId) {
            R.id.action_remove_from_playlist -> {
                // 删除会通过 Dialog 直接执行数据库删除动作，删除后会自动触发 getSongs().observe() 自动覆写 M3U
                RemoveFromPlaylistDialog.create(song.toSongEntity(playlist.playlistEntity.playListId))
                    .show(childFragmentManager, "REMOVE_FROM_PLAYLIST")
                true
            }
            R.id.action_fetch_ttml -> {
                val toast = Toast.makeText(requireContext(), "正在获取: ${song.title} 的TTML...", Toast.LENGTH_LONG)
                toast.show()

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val ttmlContent = com.mardous.booming.data.local.lyrics.ttml.TtmlFetcher.fetchTtmlForSong(song)
                    
                    withContext(Dispatchers.Main) {
                        toast.cancel()
                        if (!ttmlContent.isNullOrBlank()) {
                            try {
                                val songFile = File(song.data)
                                val parentDir = songFile.parentFile
                                if (parentDir != null && parentDir.exists()) {
                                    File(parentDir, "${songFile.nameWithoutExtension}.ttml").writeText(ttmlContent)
                                    Toast.makeText(requireContext(), "TTML 获取成功！", Toast.LENGTH_SHORT).show()
                                    lyricsRepository.clearMemoryCache()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(requireContext(), "保存失败：请检查读写权限", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(requireContext(), "获取失败：全网未找到该歌曲的逐字歌词", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                true
            }
            else -> song.onSongMenu(this, menuItem)
        }
    }

    override fun songsMenuItemClick(songs: List<Song>, menuItem: MenuItem) {
        when (menuItem.itemId) {
            R.id.action_remove_from_playlist -> {
                RemoveFromPlaylistDialog.create(songs.toSongsEntity(playlist.playlistEntity))
                    .show(childFragmentManager, "REMOVE_FROM_PLAYLIST")
            }
            R.id.action_fetch_ttml -> {
                if (songs.isNotEmpty()) {
                    val toast = Toast.makeText(requireContext(), "正在后台为 ${songs.size} 首歌曲获取 TTML...", Toast.LENGTH_LONG)
                    toast.show()

                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        var successCount = 0
                        for (song in songs) {
                            val ttmlContent = com.mardous.booming.data.local.lyrics.ttml.TtmlFetcher.fetchTtmlForSong(song)
                            if (!ttmlContent.isNullOrBlank()) {
                                try {
                                    val songFile = File(song.data)
                                    val parentDir = songFile.parentFile
                                    if (parentDir != null && parentDir.exists()) {
                                        File(parentDir, "${songFile.nameWithoutExtension}.ttml").writeText(ttmlContent)
                                        successCount++
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                        
                        withContext(Dispatchers.Main) {
                            toast.cancel()
                            lyricsRepository.clearMemoryCache()
                            Toast.makeText(requireContext(), "批量获取完成: 成功 $successCount/${songs.size} 首", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            else -> songs.onSongsMenu(this, menuItem)
        }
    }
    
    /**
     * 🌟 强制物理覆盖同步 M3U 方法 
     * 会在手机的 Music/Playlists 文件夹下生成与歌单同名且完全覆盖的 .m3u 文件。
     */
    private fun syncPlaylistToLocalM3u(playlistName: String, songs: List<Song>, isManualExport: Boolean) {
        // 防止意外创建非法文件
        if (playlistName.isBlank() || songs.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 构建标准 M3U 内容
                val m3uContent = StringBuilder()
                m3uContent.append("#EXTM3U\n")
                for (song in songs) {
                    val durationSec = song.duration / 1000
                    m3uContent.append("#EXTINF:$durationSec,${song.artistName} - ${song.title}\n")
                    m3uContent.append("${song.data}\n") // song.data 是音频物理路径
                }

                // 2. 锁定文件保存目录为 Android 标准音乐文件夹下的 Playlists 文件夹
                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val playlistDir = File(musicDir, "Playlists")
                if (!playlistDir.exists()) {
                    playlistDir.mkdirs() // 如果不存在则自动创建
                }

                // 3. 强制覆盖写入（writeText 默认会抹除同名文件内容重新写）
                // 剔除文件名中可能的非法字符
                val safeFileName = playlistName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val m3uFile = File(playlistDir, "$safeFileName.m3u")
                m3uFile.writeText(m3uContent.toString())

                // 如果是用户点击了右上角的“导出播放列表”，弹出明确的路径提示
                if (isManualExport) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(), 
                            "播放列表已覆盖导出至:\n${m3uFile.absolutePath}", 
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (isManualExport) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onPause() {
        recyclerViewDragDropManager?.cancelDrag()
        playlistSongAdapter?.saveSongs(playlist.playlistEntity)
        super.onPause()
    }

    override fun onDestroyView() {
        playlistSongAdapter?.unregisterAdapterDataObserver(adapterDataObserver)

        recyclerViewDragDropManager?.release()
        recyclerViewDragDropManager = null

        binding.recyclerView.itemAnimator = null
        binding.recyclerView.adapter = null

        WrapperAdapterUtils.releaseAll(wrappedAdapter)
        wrappedAdapter = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "PlaylistDetail"
    }
}