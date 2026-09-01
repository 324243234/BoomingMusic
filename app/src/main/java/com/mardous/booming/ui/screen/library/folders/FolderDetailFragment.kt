/*
 * Copyright (c) 2025 Christians Mart铆nez Alvarado
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
import androidx.recyclerview.widget.RecyclerView
import coil3.SingletonImageLoader
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
import kotlinx.coroutines.delay
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

    private val repository: Repository by inject()
    private val lyricsRepository: LyricsRepository by inject()

    private lateinit var songAdapter: SongAdapter
    
    // 馃専 鐢ㄤ簬璁板綍褰撳墠鎾斁姝屾洸鏄惁鍦ㄥ垪琛ㄤ腑锛岃緟鍔╂粦鍔ㄦ樉闅愬垽鏂?
    private var isCurrentSongInList = false

    private val folder: Folder
        get() = detailViewModel.getFolder().value ?: Folder.empty

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDetailListBinding.bind(view)
        materialSharedAxis(view)
        setSupportActionBar(binding.toolbar)

        view.applyHorizontalWindowInsets()

        // 馃専 鍔ㄦ€侀€傞厤 Mini 鎾斁鍣ㄩ珮搴︿笌 FAB 浣嶇疆
        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            val bottomOffset = it.getWithSpace()
            binding.recyclerView.updatePadding(bottom = bottomOffset)
            
            val fab = view.findViewById<FloatingActionButton>(R.id.fabLocateSong)
            if (fab != null) {
                val lp = fab.layoutParams as android.view.ViewGroup.MarginLayoutParams
                val baseMargin = (16 * resources.displayMetrics.density).toInt()
                lp.bottomMargin = baseMargin + bottomOffset
                fab.layoutParams = lp
            }
        }

        setupButtons()
        setupRecyclerView()
        
        detailViewModel.getFolder().observe(viewLifecycleOwner) {
            binding.collapsingAppBarLayout.title = it.fileName
            binding.title.text = it.fileName
            songs(it.songs)
        }

        // 馃専 淇鐐癸細鐩存帴浣跨敤宸插鍖呯殑 lifecycleScope.launch锛屽畬缇庡疄鏃朵睛鍚綋鍓嶆鍦ㄦ挱鏀剧殑姝屾洸
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.currentSongFlow.collect { currentSong ->
                checkCurrentSongInFolder(currentSong)
            }
        }
    }

    private fun checkCurrentSongInFolder(currentSong: Song?) {
        val fabLocateSong = view?.findViewById<FloatingActionButton>(R.id.fabLocateSong) ?: return
        val currentList = songAdapter.dataSet

        if (currentSong == null || currentSong.id == 0L || currentList.isEmpty()) {
            isCurrentSongInList = false
            fabLocateSong.hide()
            return
        }

        val index = currentList.indexOfFirst { it.id == currentSong.id }

        if (index != -1) {
            isCurrentSongInList = true
            fabLocateSong.show()
            fabLocateSong.setOnClickListener {
                binding.recyclerView.scrollToPosition(index)
            }
        } else {
            isCurrentSongInList = false
            fabLocateSong.hide()
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
            Log.e(TAG, "瑙ｆ瀽濯掍綋搴?Uri 澶辫触", e)
        }
        return null
    }

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
                    FileOutputStream(songFile).use { output -> 
                        input.copyTo(output)
                        output.fd.sync() 
                    }
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
                songFile.setLastModified(System.currentTimeMillis())
                val uri = getUriFromPath(requireContext(), songFile.absolutePath)
                if (uri != null) {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    }
                    requireContext().contentResolver.update(uri, values, null, null)
                }
                MediaScannerConnection.scanFile(requireContext(), arrayOf(songFile.absolutePath), null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "鍐欏叆宕╂簝", e)
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
            
            // 馃専 婊氬姩鐩戝惉锛氬悜涓嬫粦鍔ㄩ殣钘忥紝鍚戜笂婊戝姩鏄剧ず
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val fab = view?.findViewById<FloatingActionButton>(R.id.fabLocateSong) ?: return
                    if (dy > 0 && fab.isShown) {
                        fab.hide() // 绯荤粺鍘熺敓鐨勬敹缂╂秷澶卞姩鐢?
                    } else if (dy < 0 && isCurrentSongInList && !fab.isShown) {
                        fab.show() // 绯荤粺鍘熺敓鐨勫脊鐜板姩鐢?
                    }
                }
            })
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
        
        // 馃専 杞藉叆鏁版嵁鍚庣珛鍒绘牎楠屽畾浣嶆寜閽殑鐘舵€?
        checkCurrentSongInFolder(playerViewModel.currentSongFlow.value)
    }

    override fun songMenuItemClick(
        song: Song,
        menuItem: MenuItem,
        sharedElements: Array<Pair<View, String>>?
    ): Boolean {
        return when (menuItem.itemId) {
            R.id.action_fetch_ttml -> {
                val toast = Toast.makeText(requireContext(), "姝ｅ湪鑾峰彇: ${song.title} 鐨凾TML...", Toast.LENGTH_LONG)
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
                                    Toast.makeText(requireContext(), "TTML 鑾峰彇鎴愬姛锛?, Toast.LENGTH_SHORT).show()
                                    lyricsRepository.clearMemoryCache()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(requireContext(), "淇濆瓨澶辫触锛氳妫€鏌ヨ鍐欐潈闄?, Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(requireContext(), "鏈壘鍒拌姝屾洸鐨勯€愬瓧姝岃瘝", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                true
            }

            R.id.action_fetch_lrc -> {
                val toast = Toast.makeText(requireContext(), "姝ｅ湪鑾峰彇 LRC: ${song.title}...", Toast.LENGTH_LONG)
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
                            
                            try { repository.updatePlaylistsContainingIds(listOf(song.id)) } catch (e: Exception) {}
                            lyricsRepository.clearMemoryCache()
                            withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "LRC 鑾峰彇鎴愬姛锛?, Toast.LENGTH_SHORT).show() }
                        }
                    } else { 
                        withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "鏈壘鍒板搴旀瓕璇?, Toast.LENGTH_SHORT).show() } 
                    }
                }
                true
            }

            R.id.action_fetch_cover -> {
                val toast = Toast.makeText(requireContext(), "姝ｅ湪鑾峰彇灏侀潰: ${song.title}...", Toast.LENGTH_LONG)
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
                            try { repository.updatePlaylistsContainingIds(listOf(song.id)) } catch (e: Exception) {}
                            
                            // 馃挜 缁堟瀬闃茬珵浜夌瓑寰咃細纭繚搴曞眰 IO 鎿嶄綔 100% 缁撴潫涓旀墍鏈夋棫鍥捐鍙栦换鍔¤秴鏃?
                            delay(500)
                            
                            try {
                                val imageLoader = SingletonImageLoader.get(requireContext())
                                imageLoader.memoryCache?.clear()
                                imageLoader.diskCache?.clear()
                            } catch (e: Exception) {}
                            
                            libraryViewModel.forceReload(ReloadType.Songs)
                            libraryViewModel.forceReload(ReloadType.Playlists)
                            
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "灏侀潰鑾峰彇鎴愬姛锛?, Toast.LENGTH_SHORT).show()
                                detailViewModel.loadDetail()
                                songAdapter.notifyDataSetChanged()
                            }
                        }
                    } else { 
                        withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "鏈壘鍒板搴斿皝闈?, Toast.LENGTH_SHORT).show() } 
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
                    val toast = Toast.makeText(requireContext(), "姝ｅ湪鍚庡彴涓?${songs.size} 棣栨瓕鏇茶幏鍙?TTML...", Toast.LENGTH_LONG)
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
                            Toast.makeText(requireContext(), "鎵归噺鑾峰彇 TTML 瀹屾垚: 鎴愬姛 $successCount/${songs.size} 棣?, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            R.id.action_fetch_lrc -> {
                if (songs.isNotEmpty()) {
                    val toast = Toast.makeText(requireContext(), "姝ｅ湪涓?${songs.size} 棣栨瓕鑾峰彇 LRC 姝岃瘝...", Toast.LENGTH_LONG)
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
                            Toast.makeText(requireContext(), "LRC 鎵归噺鑾峰彇瀹屾垚: 鎴愬姛 $successCount/${songs.size} 棣?, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            R.id.action_fetch_cover -> {
                if (songs.isNotEmpty()) {
                    val toast = Toast.makeText(requireContext(), "姝ｅ湪涓?${songs.size} 棣栨瓕鑾峰彇楂樻竻灏侀潰...", Toast.LENGTH_LONG)
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
                            try { repository.updatePlaylistsContainingIds(successIds) } catch (e: Exception) {}
                            
                            delay(600)
                            
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
                            Toast.makeText(requireContext(), "闈欐€佸皝闈㈡壒閲忚幏鍙栧畬鎴? 鎴愬姛 $successCount/${songs.size} 棣?, Toast.LENGTH_SHORT).show()
                            if (successIds.isNotEmpty()) {
                                detailViewModel.loadDetail()
                                songAdapter.notifyDataSetChanged()
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
                // 既然 DownloadSheetFragment 内部已经统一接管了路径，这里直接无参调用即可
           com.mardous.booming.ui.dialogs.DownloadSheetFragment().show(childFragmentManager, "DL")
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