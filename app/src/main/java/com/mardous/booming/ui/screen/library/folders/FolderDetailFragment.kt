/*
 * Copyright (c) 2025 Christians Martínez Alvarado
 */

package com.mardous.booming.ui.screen.library.folders

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import coil3.SingletonImageLoader
import com.mardous.booming.R
import com.mardous.booming.core.sort.SongSortMode
import com.mardous.booming.data.mapper.searchFilter
import com.mardous.booming.data.model.Folder
import com.mardous.booming.data.model.Song
import com.mardous.booming.databinding.FragmentDetailListBinding
import com.mardous.booming.extensions.applyHorizontalWindowInsets
import com.mardous.booming.extensions.materialSharedAxis
import com.mardous.booming.extensions.media.songCountStr
import com.mardous.booming.extensions.media.songsDurationStr
import com.mardous.booming.extensions.navigation.searchArgs
import com.mardous.booming.extensions.setSupportActionBar
import com.mardous.booming.extensions.utilities.buildInfoString
import com.mardous.booming.core.model.shuffle.OpenShuffleMode
import com.mardous.booming.data.repository.LyricsRepository
import com.mardous.booming.data.repository.Repository
import com.mardous.booming.ui.ISongCallback
import com.mardous.booming.ui.adapters.song.SongAdapter
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.component.menu.onSongMenu
import com.mardous.booming.ui.component.menu.onSongsMenu
import com.mardous.booming.ui.screen.library.ReloadType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import java.io.File
import java.io.FileOutputStream

class FolderDetailFragment : AbsMainActivityFragment(R.layout.fragment_detail_list), ISongCallback {

    private val arguments by navArgs<FolderDetailFragmentArgs>()
    private val detailViewModel: FolderDetailViewModel by viewModel {
        parametersOf(arguments.extraFolderPath)
    }

    private var _binding: FragmentDetailListBinding? = null
    private val binding get() = _binding!!

    // 🌟 注入官方的 Repository，用来通知数据库刷新！
    private val repository: Repository by inject()
    private val lyricsRepository: LyricsRepository by inject()

    private lateinit var songAdapter: SongAdapter

    private val folder: Folder
        get() = detailViewModel.getFolder().value ?: Folder.empty

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDetailListBinding.bind(view)
        materialSharedAxis(view)
        setSupportActionBar(binding.toolbar)

        view.applyHorizontalWindowInsets()

        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            binding.recyclerView.updatePadding(bottom = it.getWithSpace())
        }

        setupButtons()
        setupRecyclerView()
        detailViewModel.getFolder().observe(viewLifecycleOwner) {
            binding.collapsingAppBarLayout.title = it.fileName
            binding.title.text = it.fileName
            songs(it.songs)
        }
    }

    private fun getUriFromPath(context: Context, path: String): android.net.Uri? {
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(path), null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    return android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析媒体库 Uri 失败", e)
        }
        return null
    }

    // 🔥 完美复刻：只负责底层物理写入和更新系统时间戳，返回成功状态
    private suspend fun safeWriteMetadataInPlace(songFile: File, updateTag: (org.jaudiotagger.tag.Tag) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val tempFile = File(requireContext().cacheDir, "temp_meta_${System.currentTimeMillis()}.${songFile.extension}")
        var success = false
        try {
            TagOptionSingleton.getInstance().isAndroid = true
            songFile.copyTo(tempFile, overwrite = true)
            
            val f = AudioFileIO.read(tempFile)
            val tag = f.tagOrCreateAndSetDefault
            updateTag(tag)
            f.commit() 
            
            try {
                tempFile.inputStream().use { input ->
                    FileOutputStream(songFile).use { output -> input.copyTo(output) }
                }
                success = true
            } catch (e: Exception) {
                val uri = getUriFromPath(requireContext(), songFile.absolutePath)
                if (uri != null) {
                    requireContext().contentResolver.openOutputStream(uri, "w")?.use { output ->
                        tempFile.inputStream().use { input -> input.copyTo(output) }
                    }
                    success = true
                }
            }

            if (success) {
                // 更新底层物理时间和 MediaStore，强行打破缓存一致性
                songFile.setLastModified(System.currentTimeMillis())
                val uri = getUriFromPath(requireContext(), songFile.absolutePath)
                if (uri != null) {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    }
                    requireContext().contentResolver.update(uri, values, null, null)
                    requireContext().contentResolver.notifyChange(uri, null)
                }
                MediaScannerConnection.scanFile(requireContext(), arrayOf(songFile.absolutePath), null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入崩溃", e)
        } finally {
            if (tempFile.exists()) tempFile.delete() 
        }
        return@withContext success
    }

    private fun setupButtons() {
        binding.playAction.setOnClickListener {
            playerViewModel.openQueue(songAdapter.dataSet, shuffleMode = OpenShuffleMode.Off)
        }
        binding.shuffleAction.setOnClickListener {
            playerViewModel.openAndShuffleQueue(songAdapter.dataSet)
        }
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            activity = requireActivity(),
            dataSet = ArrayList(),
            itemLayoutRes = R.layout.item_list,
            sortMode = SongSortMode.FolderSongs,
            callback = this
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = songAdapter
        }
    }

    fun songs(songs: List<Song>) {
        if (songs.isEmpty()) {
            findNavController().popBackStack()
            return
        }
        binding.progressIndicator.hide()
        binding.subtitle.text =
            buildInfoString(songs.songCountStr(requireContext()), songs.songsDurationStr())
        songAdapter.dataSet = songs
    }

    override fun songMenuItemClick(
        song: Song,
        menuItem: MenuItem,
        sharedElements: Array<Pair<View, String>>?
    ): Boolean {
        return when (menuItem.itemId) {
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
                            Toast.makeText(requireContext(), "未找到该歌曲的逐字歌词", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                true
            }

            R.id.action_fetch_lrc -> {
                val toast = Toast.makeText(requireContext(), "正在获取 LRC: ${song.title}...", Toast.LENGTH_LONG)
                toast.show()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val result = com.mardous.booming.data.local.lyrics.ttml.MetadataFetcher.fetchMetadata(song, needLrc = true, needCover = false)
                    withContext(Dispatchers.Main) { toast.cancel() }
                    if (!result.lrcWithTrans.isNullOrBlank()) {
                        val success = safeWriteMetadataInPlace(File(song.data)) { tag ->
                            tag.setField(FieldKey.LYRICS, result.lrcWithTrans)
                        }
                        if (success) {
                            try {
                                val songFile = File(song.data)
                                val parentDir = songFile.parentFile
                                if (parentDir != null && parentDir.exists()) {
                                    File(parentDir, "${songFile.nameWithoutExtension}.lrc").writeText(result.lrcWithTrans)
                                }
                            } catch (e: Exception) {}
                            
                            // 通知数据库变更
                            try { repository.updatePlaylistsContainingIds(listOf(song.id)) } catch (e: Exception) {}
                            lyricsRepository.clearMemoryCache()
                            withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "LRC 获取成功！", Toast.LENGTH_SHORT).show() }
                        }
                    } else { 
                        withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "未找到对应歌词", Toast.LENGTH_SHORT).show() } 
                    }
                }
                true
            }

            R.id.action_fetch_cover -> {
                val toast = Toast.makeText(requireContext(), "正在获取封面: ${song.title}...", Toast.LENGTH_LONG)
                toast.show()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val result = com.mardous.booming.data.local.lyrics.ttml.MetadataFetcher.fetchMetadata(song, needLrc = false, needCover = true)
                    withContext(Dispatchers.Main) { toast.cancel() }
                    
                    if (result.coverBytes != null) {
                        val success = safeWriteMetadataInPlace(File(song.data)) { tag ->
                            val artwork = AndroidArtwork().apply { binaryData = result.coverBytes; mimeType = "image/jpeg" }
                            tag.deleteArtworkField()
                            tag.setField(artwork)
                        }
                        
                        if (success) {
                            // 🌟 1. 彻底复刻官方核心逻辑：通知数据库刷新！
                            try { repository.updatePlaylistsContainingIds(listOf(song.id)) } catch (e: Exception) {}
                            
                            // 🌟 2. 官方级清缓存
                            try {
                                val imageLoader = SingletonImageLoader.get(requireContext())
                                imageLoader.memoryCache?.clear()
                                imageLoader.diskCache?.clear()
                            } catch (e: Exception) {}
                            
                            // 🌟 3. 全局强刷：通知全局库和底层播放器更新
                            libraryViewModel.forceReload(ReloadType.Songs)
                            libraryViewModel.forceReload(ReloadType.Playlists)
                            
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "封面获取成功！", Toast.LENGTH_SHORT).show()
                                detailViewModel.loadDetail() // 触发当前文件夹列表重新拉取数据库
                            }
                        }
                    } else { 
                        withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "未找到对应封面", Toast.LENGTH_SHORT).show() } 
                    }
                }
                true
            }

            else -> song.onSongMenu(this, menuItem)
        }
    }

    override fun songsMenuItemClick(songs: List<Song>, menuItem: MenuItem) {
        when (menuItem.itemId) {
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
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            toast.cancel()
                            lyricsRepository.clearMemoryCache()
                            Toast.makeText(requireContext(), "批量获取 TTML 完成: 成功 $successCount/${songs.size} 首", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            R.id.action_fetch_lrc -> {
                if (songs.isNotEmpty()) {
                    val toast = Toast.makeText(requireContext(), "正在为 ${songs.size} 首歌获取 LRC 歌词...", Toast.LENGTH_LONG)
                    toast.show()
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        var successCount = 0
                        val successIds = mutableListOf<Long>()
                        for (song in songs) {
                            val result = com.mardous.booming.data.local.lyrics.ttml.MetadataFetcher.fetchMetadata(song, needLrc = true, needCover = false)
                            if (!result.lrcWithTrans.isNullOrBlank()) {
                                val success = safeWriteMetadataInPlace(File(song.data)) { tag ->
                                    tag.setField(FieldKey.LYRICS, result.lrcWithTrans)
                                }
                                if (success) {
                                    try {
                                        val songFile = File(song.data)
                                        val parentDir = songFile.parentFile
                                        if (parentDir != null && parentDir.exists()) {
                                            File(parentDir, "${songFile.nameWithoutExtension}.lrc").writeText(result.lrcWithTrans)
                                        }
                                    } catch (e: Exception) {}
                                    successIds.add(song.id)
                                    successCount++
                                }
                            }
                        }
                        if (successIds.isNotEmpty()) {
                            try { repository.updatePlaylistsContainingIds(successIds) } catch (e: Exception) {}
                            libraryViewModel.forceReload(ReloadType.Songs)
                        }
                        withContext(Dispatchers.Main) {
                            toast.cancel()
                            lyricsRepository.clearMemoryCache()
                            Toast.makeText(requireContext(), "LRC 批量获取完成: 成功 $successCount/${songs.size} 首", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            R.id.action_fetch_cover -> {
                if (songs.isNotEmpty()) {
                    val toast = Toast.makeText(requireContext(), "正在为 ${songs.size} 首歌获取高清封面...", Toast.LENGTH_LONG)
                    toast.show()
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        var successCount = 0
                        val successIds = mutableListOf<Long>()
                        for (song in songs) {
                            val result = com.mardous.booming.data.local.lyrics.ttml.MetadataFetcher.fetchMetadata(song, needLrc = false, needCover = true)
                            if (result.coverBytes != null) {
                                val success = safeWriteMetadataInPlace(File(song.data)) { tag ->
                                    val artwork = AndroidArtwork().apply { binaryData = result.coverBytes; mimeType = "image/jpeg" }
                                    tag.deleteArtworkField()
                                    tag.setField(artwork)
                                }
                                if (success) {
                                    successIds.add(song.id)
                                    successCount++
                                }
                            }
                        }
                        if (successIds.isNotEmpty()) {
                            // 🌟 批量同步 App 数据库
                            try { repository.updatePlaylistsContainingIds(successIds) } catch (e: Exception) {}
                            
                            try {
                                val imageLoader = SingletonImageLoader.get(requireContext())
                                imageLoader.memoryCache?.clear()
                                imageLoader.diskCache?.clear()
                            } catch (e: Exception) {}
                            
                            libraryViewModel.forceReload(ReloadType.Songs)
                            libraryViewModel.forceReload(ReloadType.Playlists)
                        }
                        
                        withContext(Dispatchers.Main) {
                            toast.cancel()
                            Toast.makeText(requireContext(), "静态封面批量获取完成: 成功 $successCount/${songs.size} 首", Toast.LENGTH_SHORT).show()
                            if (successIds.isNotEmpty()) {
                                detailViewModel.loadDetail()
                            }
                        }
                    }
                }
            }

            else -> {
                songs.onSongsMenu(this, menuItem)
            }
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_folder_detail, menu)
        SongSortMode.FolderSongs.createMenu(menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return when {
            SongSortMode.FolderSongs.sortItemSelected(item) -> {
                detailViewModel.loadDetail()
                true
            }
            
            item.itemId == R.id.action_download_music -> {
                val targetDir = File(arguments.extraFolderPath)
                com.mardous.booming.ui.dialogs.DownloadSheetFragment(targetDir).show(childFragmentManager, "DL")
                true
            }

            item.itemId == R.id.action_search -> {
                findNavController().navigate(
                    R.id.nav_search,
                    searchArgs(folder.searchFilter(requireContext()))
                )
                true
            }

            item.itemId == android.R.id.home -> {
                findNavController().navigateUp()
                true
            }

            else -> songAdapter.dataSet.onSongsMenu(this, item)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "FolderDetailFragment"
    }
}