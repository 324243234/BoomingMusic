/*
 * Copyright (c) 2025 Christians Martínez Alvarado
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

package com.mardous.booming.ui.screen.library.folders

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Bundle
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
import coil.imageLoader
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
import com.mardous.booming.ui.ISongCallback
import com.mardous.booming.ui.adapters.song.SongAdapter
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.component.menu.onSongMenu
import com.mardous.booming.ui.component.menu.onSongsMenu
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
    
    // ================== ?? 沙盒穿透与 Inode 永生 ==================
    private fun getUriFromPath(context: Context, path: String): android.net.Uri? {
        try {
            val cursor = context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Audio.Media._ID),
                "${android.provider.MediaStore.Audio.Media.DATA} = ?",
                arrayOf(path), null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID))
                    return android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析媒体库 Uri 失败", e)
        }
        return null
    }

    private fun safeWriteMetadataInPlace(songFile: File, updateTag: (org.jaudiotagger.tag.Tag) -> Unit) {
        val tempFile = File(requireContext().cacheDir, "temp_meta_${System.currentTimeMillis()}.${songFile.extension}")
        try {
            TagOptionSingleton.getInstance().isAndroid = true
            songFile.copyTo(tempFile, overwrite = true)
            
            val f = AudioFileIO.read(tempFile)
            val tag = f.tagOrCreateAndSetDefault
            updateTag(tag)
            f.commit() 
            
            try {
                tempFile.inputStream().use { input ->
                    FileOutputStream(songFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "常规 FileOutputStream 被拦截，启动 ContentResolver 穿透注入...")
                val uri = getUriFromPath(requireContext(), songFile.absolutePath)
                if (uri != null) {
                    requireContext().contentResolver.openOutputStream(uri, "w")?.use { output ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    throw Exception("无法获取系统授权的 Uri，底层写入失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "安全覆写操作严重崩溃", e)
            throw e 
        } finally {
            if (tempFile.exists()) tempFile.delete() 
        }
        MediaScannerConnection.scanFile(requireContext(), arrayOf(songFile.absolutePath), null, null)
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
                        try {
                            val songFile = File(song.data)
                            if (songFile.parentFile != null && songFile.parentFile!!.exists()) {
                                File(songFile.parentFile, "${songFile.nameWithoutExtension}.lrc").writeText(result.lrcWithTrans)
                                safeWriteMetadataInPlace(songFile) { tag -> tag.setField(FieldKey.LYRICS, result.lrcWithTrans) }
                                lyricsRepository.clearMemoryCache()
                                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "LRC 获取成功！", Toast.LENGTH_SHORT).show() }
                            }
                        } catch (e: Exception) { Log.e(TAG, "写入失败", e) }
                    } else { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "未找到对应歌词", Toast.LENGTH_SHORT).show() } }
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
                        try {
                            safeWriteMetadataInPlace(File(song.data)) { tag ->
                                val artwork = AndroidArtwork().apply { binaryData = result.coverBytes; mimeType = "image/jpeg" }
                                tag.deleteArtworkField()
                                tag.setField(artwork)
                            }
                            
                            withContext(Dispatchers.Main) { 
                                Toast.makeText(requireContext(), "封面获取成功！", Toast.LENGTH_SHORT).show() 
                                
                                // ?? 强制清空 Coil 内存缓存，迫使读取新写入的封面
                                requireContext().imageLoader.memoryCache?.clear()
                                requireContext().imageLoader.diskCache?.clear()
                                
                                val index = songAdapter.dataSet.indexOfFirst { it.id == song.id }
                                if (index != -1) {
                                    songAdapter.notifyItemChanged(index)
                                }
                            }
                        } catch (e: Exception) { Log.e(TAG, "写入失败", e) }
                    } else { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "未找到对应封面", Toast.LENGTH_SHORT).show() } }
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
                        for (song in songs) {
                            val result = com.mardous.booming.data.local.lyrics.ttml.MetadataFetcher.fetchMetadata(song, needLrc = true, needCover = false)
                            if (!result.lrcWithTrans.isNullOrBlank()) {
                                try {
                                    val songFile = File(song.data)
                                    if (songFile.parentFile != null && songFile.parentFile!!.exists()) {
                                        File(songFile.parentFile, "${songFile.nameWithoutExtension}.lrc").writeText(result.lrcWithTrans)
                                        safeWriteMetadataInPlace(songFile) { tag -> tag.setField(FieldKey.LYRICS, result.lrcWithTrans) }
                                        successCount++
                                    }
                                } catch (e: Exception) { Log.e(TAG, "LRC 写入失败", e) }
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

            R.id.action_fetch_cover -> {
                if (songs.isNotEmpty()) {
                    val toast = Toast.makeText(requireContext(), "正在为 ${songs.size} 首歌获取高清封面...", Toast.LENGTH_LONG)
                    toast.show()
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        var successCount = 0
                        for (song in songs) {
                            val result = com.mardous.booming.data.local.lyrics.ttml.MetadataFetcher.fetchMetadata(song, needLrc = false, needCover = true)
                            if (result.coverBytes != null) {
                                try {
                                    safeWriteMetadataInPlace(File(song.data)) { tag ->
                                        val artwork = AndroidArtwork().apply { binaryData = result.coverBytes; mimeType = "image/jpeg" }
                                        tag.deleteArtworkField()
                                        tag.setField(artwork)
                                    }
                                    successCount++
                                } catch (e: Exception) { Log.e(TAG, "Cover 写入失败", e) }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            toast.cancel()
                            Toast.makeText(requireContext(), "静态封面批量获取完成: 成功 $successCount/${songs.size} 首", Toast.LENGTH_SHORT).show()
                            
                            // ?? 强制清空 Coil 内存缓存，迫使读取新写入的封面
                            requireContext().imageLoader.memoryCache?.clear()
                            requireContext().imageLoader.diskCache?.clear()
                            
                            songAdapter.notifyDataSetChanged()
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