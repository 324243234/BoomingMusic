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

import android.util.Log
import android.content.Context
import android.graphics.Color
import android.media.MediaScannerConnection
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
import com.mardous.booming.data.repository.LyricsRepository
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
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
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
            // 🌟 彻底移除了这里的自动导出代码，不再浪费任何计算资源
        }
        
        detailViewModel.playlistExists().observe(viewLifecycleOwner) {
            if (!it) {
                findNavController().navigateUp()
            }
        }
    }

    // ================== 🌟 核心引擎：本地置顶特权系统 ==================

    private fun getPinnedSongIds(): Set<String> {
        val prefs = requireContext().getSharedPreferences("playlist_pins", Context.MODE_PRIVATE)
        return prefs.getStringSet("pinned_${playlist.playlistEntity.playListId}", emptySet()) ?: emptySet()
    }

    private fun setPinnedSongIds(ids: Set<String>) {
        val prefs = requireContext().getSharedPreferences("playlist_pins", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("pinned_${playlist.playlistEntity.playListId}", ids).apply()
    }

    private fun pinSongsToTop(songsToPin: List<Song>) {
        val currentSongs = playlistSongAdapter?.dataSet?.toMutableList() ?: return
        val currentPinnedIds = getPinnedSongIds().toMutableSet()
        
        val newlyPinnedSongs = songsToPin.filter { it.id.toString() !in currentPinnedIds }
        if (newlyPinnedSongs.isEmpty()) {
            Toast.makeText(requireContext(), "选中的歌曲已在置顶列中", Toast.LENGTH_SHORT).show()
            return
        }
        
        newlyPinnedSongs.forEach { song ->
            currentPinnedIds.add(song.id.toString())
            currentSongs.remove(song)
        }
        
        currentSongs.addAll(0, newlyPinnedSongs)
        setPinnedSongIds(currentPinnedIds)
        
        recyclerViewDragDropManager?.cancelDrag()
        playlistSongAdapter?.dataSet = currentSongs
        binding.recyclerView.adapter?.notifyDataSetChanged()
        binding.recyclerView.scrollToPosition(0)
        
        // 仅保存在应用内数据库，不写物理文件
        playlistSongAdapter?.saveSongs(playlist.playlistEntity)
        Toast.makeText(requireContext(), "已置顶 ${newlyPinnedSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
    }

    private fun unpinSongs(songsToUnpin: List<Song>) {
        val currentSongs = playlistSongAdapter?.dataSet?.toMutableList() ?: return
        val currentPinnedIds = getPinnedSongIds().toMutableSet()
        
        val actualUnpinned = mutableListOf<Song>()
        songsToUnpin.forEach { song ->
            if (currentPinnedIds.remove(song.id.toString())) {
                actualUnpinned.add(song)
                currentSongs.remove(song)
            }
        }
        
        if (actualUnpinned.isNotEmpty()) {
            setPinnedSongIds(currentPinnedIds)
            val remainingPinnedCount = currentSongs.count { it.id.toString() in currentPinnedIds }
            currentSongs.addAll(remainingPinnedCount, actualUnpinned)
            
            recyclerViewDragDropManager?.cancelDrag()
            playlistSongAdapter?.dataSet = currentSongs
            binding.recyclerView.adapter?.notifyDataSetChanged()
            
            // 仅保存在应用内数据库，不写物理文件
            playlistSongAdapter?.saveSongs(playlist.playlistEntity)
            Toast.makeText(requireContext(), "已取消置顶", Toast.LENGTH_SHORT).show()
        }
    }

    // ==============================================================

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
            dragDropManager.setOnItemDragEventListener(object : RecyclerViewDragDropManager.OnItemDragEventListener {
                override fun onItemDragStarted(position: Int) {}
                override fun onItemDragPositionChanged(fromPosition: Int, toPosition: Int) {}
                override fun onItemDragFinished(fromPosition: Int, toPosition: Int, result: Boolean) {
                    if (fromPosition != toPosition) {
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
        // 🌟 唯一触发 M3U 导出的入口（手动导出）
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

        val currentSongs = playlistSongAdapter?.dataSet
        if (!currentSongs.isNullOrEmpty()) {
            val pinnedIds = getPinnedSongIds()
            val pinnedSongs = currentSongs.filter { it.id.toString() in pinnedIds }
            val unpinnedSongs = currentSongs.filter { it.id.toString() !in pinnedIds }
            
            val sortedUnpinnedList = when (menuItem.itemId) {
                R.id.action_sort_by_title_asc -> unpinnedSongs.sortedBy { it.title }
                R.id.action_sort_by_title_desc -> unpinnedSongs.sortedByDescending { it.title }
                R.id.action_sort_by_artist_asc -> unpinnedSongs.sortedBy { it.artistName }
                R.id.action_sort_by_artist_desc -> unpinnedSongs.sortedByDescending { it.artistName }
                R.id.action_sort_by_album_asc -> unpinnedSongs.sortedBy { it.albumName }
                R.id.action_sort_by_album_desc -> unpinnedSongs.sortedByDescending { it.albumName }
                R.id.action_sort_by_duration_asc -> unpinnedSongs.sortedBy { it.duration }
                R.id.action_sort_by_duration_desc -> unpinnedSongs.sortedByDescending { it.duration }
                R.id.action_sort_by_date_asc -> unpinnedSongs.sortedBy { it.dateAdded }
                R.id.action_sort_by_date_desc -> unpinnedSongs.sortedByDescending { it.dateAdded }
                else -> null
            }

            if (sortedUnpinnedList != null) {
                recyclerViewDragDropManager?.cancelDrag()
                val finalSortedList = pinnedSongs + sortedUnpinnedList
                
                playlistSongAdapter?.dataSet = finalSortedList
                binding.recyclerView.adapter?.notifyDataSetChanged()
                binding.recyclerView.scrollToPosition(0)
                
                // 仅保存在应用内数据库
                playlistSongAdapter?.saveSongs(playlist.playlistEntity)
                return true
            }
        }

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
            R.id.action_pin_to_top -> {
                pinSongsToTop(listOf(song))
                true
            }
            R.id.action_unpin -> {
                unpinSongs(listOf(song))
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
			
			
			// 🌟 1. 批量获取 LRC
            R.id.action_fetch_lrc -> {
                if (songs.isNotEmpty()) {
                    val toast = Toast.makeText(requireContext(), "正在为 ${songs.size} 首歌获取 LRC 歌词...", Toast.LENGTH_LONG)
                    toast.show()

                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        var successCount = 0
                        for (song in songs) {
                            val result = com.mardous.booming.data.local.lyrics.ttml.MetadataFetcher.fetchMetadata(song, needLrc = true, needCover = false)
                            if (!result.lrcWithTrans.isNullOrBlank()) {
                                try {
                                    val songFile = File(song.data)
                                    val parentDir = songFile.parentFile
                                    if (parentDir != null && parentDir.exists()) {
                                        // 1. 写入同名 .lrc 文件
                                        File(parentDir, "${songFile.nameWithoutExtension}.lrc").writeText(result.lrcWithTrans)
                                        
                                        // 2. 物理写入音频文件内嵌标签 (Jaudiotagger)
                                        val f = AudioFileIO.read(songFile)
                                        val tag = f.tagOrCreateAndSetDefault
                                        tag.setField(FieldKey.LYRICS, result.lrcWithTrans)
                                        f.commit()
                                        
                                        successCount++
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "LRC 写入失败: ${song.title}", e)
                                }
                            }
                        }
                        
                        withContext(Dispatchers.Main) {
                            toast.cancel()
                            lyricsRepository.clearMemoryCache()
                            Toast.makeText(requireContext(), "LRC 批量获取完成: 成功 $successCount/${songs.size} 首", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            // 🌟 2. 批量获取静态封面
            R.id.action_fetch_cover -> {
                if (songs.isNotEmpty()) {
                    val toast = Toast.makeText(requireContext(), "正在为 ${songs.size} 首歌获取高清静态封面...", Toast.LENGTH_LONG)
                    toast.show()

                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        var successCount = 0
                        for (song in songs) {
                            val result = com.mardous.booming.data.local.lyrics.ttml.MetadataFetcher.fetchMetadata(song, needLrc = false, needCover = true)
                            if (result.coverBytes != null) {
                                try {
                                    val songFile = File(song.data)
                                    // 物理写入音频文件内嵌封面 (Jaudiotagger)
                                    val f = AudioFileIO.read(songFile)
                                    val tag = f.tagOrCreateAndSetDefault
                                    
                                    val artwork = ArtworkFactory.getNew()
                                    artwork.binaryData = result.coverBytes
                                    artwork.mimeType = "image/jpeg"
                                    
                                    tag.deleteArtworkField()
                                    tag.setField(artwork)
                                    f.commit()
                                    
                                    successCount++
                                } catch (e: Exception) {
                                    Log.e(TAG, "Cover 写入失败: ${song.title}", e)
                                }
                            }
                        }
                        
                        withContext(Dispatchers.Main) {
                            toast.cancel()
                            // 提示更新完成（注意：封面可能需要清除 Glide/Coil 图片缓存才能立马在 UI 显示新图）
                            Toast.makeText(requireContext(), "静态封面批量获取完成: 成功 $successCount/${songs.size} 首", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
			
			
			
            R.id.action_pin_to_top -> {
                if (songs.isNotEmpty()) pinSongsToTop(songs)
            }
            R.id.action_unpin -> {
                if (songs.isNotEmpty()) unpinSongs(songs)
            }
            else -> songs.onSongsMenu(this, menuItem)
        }
    }
    
    /**
     * 🌟 强制物理覆盖同步 M3U 方法
     * 只在手动点击导出时触发，彻底修复导出“幽灵缓存” Bug，完全隔绝性能浪费
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun syncPlaylistToLocalM3u(playlistName: String, songs: List<Song>, isManualExport: Boolean) {
        if (playlistName.isBlank() || songs.isEmpty()) return

        val appContext = requireContext().applicationContext
        val safeSongs = songs.toList() // 深拷贝防并发崩溃

        // 🌟 使用 GlobalScope 保证哪怕刚点完保存就退出页面，后台写盘也绝不中断
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val m3uContent = StringBuilder()
                // 强制使用 \r\n 标准换行，确保车机、三方播放器 100% 识别不截断
                m3uContent.append("#EXTM3U\r\n")
                for (song in safeSongs) {
                    val durationSec = song.duration / 1000
                    m3uContent.append("#EXTINF:$durationSec,${song.artistName} - ${song.title}\r\n")
                    m3uContent.append("${song.data}\r\n") 
                }

                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val playlistDir = File(musicDir, "Playlists")
                if (!playlistDir.exists()) {
                    playlistDir.mkdirs() 
                }

                val safeFileName = playlistName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val m3uFile = File(playlistDir, "$safeFileName.m3u")
                
                // 🌟 最强物理截断复写引擎：杜绝 M3U 文件幽灵缓存和文件末尾残留乱码
                java.io.FileOutputStream(m3uFile, false).use { fos ->
                    fos.write(m3uContent.toString().toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync() // 绝对强迫 Linux 内核将数据砸向物理闪存
                }

                // 强制要求 Android MediaStore 马上扫描此文件，粉碎导入时读出旧数据的 Bug
                MediaScannerConnection.scanFile(
                    appContext,
                    arrayOf(m3uFile.absolutePath),
                    arrayOf("audio/mpegurl", "audio/x-mpegurl"),
                    null
                )

                if (isManualExport) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext, 
                            "播放列表已物理覆盖至:\n${m3uFile.absolutePath}", 
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (isManualExport) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onPause() {
        recyclerViewDragDropManager?.cancelDrag()
        playlistSongAdapter?.saveSongs(playlist.playlistEntity)
        // 彻底移除了这里的自动兜底覆盖，不再浪费任何性能！
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