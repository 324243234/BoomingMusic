package com.mardous.booming.ui.screen.library.radios

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.mardous.booming.R
import com.mardous.booming.data.local.room.PlaylistWithSongs
import com.mardous.booming.data.repository.Repository
import com.mardous.booming.extensions.navigation.playlistDetailArgs
import com.mardous.booming.ui.IPlaylistCallback
import com.mardous.booming.ui.adapters.PlaylistAdapter
import com.mardous.booming.ui.component.base.AbsRecyclerViewCustomGridSizeFragment
import com.mardous.booming.ui.screen.library.ReloadType
import com.mardous.booming.util.RadioBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import java.io.FileOutputStream

class RadioListFragment : AbsRecyclerViewCustomGridSizeFragment<PlaylistAdapter, GridLayoutManager>(), IPlaylistCallback {

    // 注入 Repository 以便导入电台数据
    private val repository: Repository by inject()

    // 电台不需要全局随机播放，隐藏悬浮按钮
    override val isShuffleVisible: Boolean = false 
    override val titleRes: Int = R.string.radios_label
    
    override val emptyMessageRes: Int
        get() = R.string.no_device_playlists

    override val maxGridSize: Int
        get() = if (isLandscape) resources.getInteger(R.integer.max_playlist_columns_land)
        else resources.getInteger(R.integer.max_playlist_columns)

    override val itemLayoutRes: Int
        get() = if (isGridMode) R.layout.item_playlist else R.layout.item_list

    // 🌟 注册系统文件选择器（用于导入 .m3u 文件）
    private val importM3uLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // 将 Uri 内容复制到临时 Cache 文件，交给 RadioBackupManager 处理
                    val tempFile = File(requireContext().cacheDir, "imported_radio.m3u")
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    RadioBackupManager.importRadioFromM3u(requireContext(), repository, tempFile)
                    tempFile.delete() // 导入完毕后清除临时文件
                    
                    withContext(Dispatchers.Main) {
                        libraryViewModel.forceReload(ReloadType.Playlists)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "导入解析失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 🌟 监听 ViewModel 中获取电台的数据流
        libraryViewModel.getRadioPlaylists().observe(viewLifecycleOwner) { radios ->
            adapter?.dataSet = radios
        }
    }

    override fun onResume() {
        super.onResume()
        libraryViewModel.forceReload(ReloadType.Playlists)
    }

    override fun createLayoutManager(): GridLayoutManager {
        return GridLayoutManager(requireContext(), gridSize)
    }

    override fun createAdapter(): PlaylistAdapter {
        notifyLayoutResChanged(itemLayoutRes)
        val dataSet = adapter?.dataSet ?: ArrayList()
        return PlaylistAdapter(mainActivity, dataSet, itemLayoutRes, this)
    }

    // 🌟 生成顶部菜单
    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateMenu(menu, inflater)
        menu.removeItem(R.id.action_view_type) // 移除多余的视图切换
        
        // 添加电台专属菜单
        menu.add(0, 9001, 0, "导入 M3U 电台源").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 9002, 0, "备份全部电台").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
    }

    // 🌟 处理顶部菜单点击
    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            9001 -> {
                // 触发文件选择器，过滤出所有文件（系统会允许选中 m3u/txt）
                importM3uLauncher.launch("*/*") 
                return true
            }
            9002 -> {
                // 调用备份管理器
                val currentRadios = adapter?.dataSet
                if (!currentRadios.isNullOrEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        RadioBackupManager.exportAllRadios(requireContext(), currentRadios)
                    }
                } else {
                    Toast.makeText(requireContext(), "当前没有可导出的电台", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        return super.onMenuItemSelected(item)
    }

    // 🌟 处理电台分类点击：复用播放列表详情页，传 ID 跳转过去
    override fun playlistClick(playlist: PlaylistWithSongs) {
        findNavController().navigate(
            R.id.nav_playlist_detail, 
            playlistDetailArgs(playlist.playlistEntity.playListId)
        )
    }

    override fun playlistMenuItemClick(playlist: PlaylistWithSongs, menuItem: MenuItem): Boolean = false
    
    override fun playlistsMenuItemClick(playlists: List<PlaylistWithSongs>, menuItem: MenuItem) {}
    
    override fun onMediaContentChanged() { 
        libraryViewModel.forceReload(ReloadType.Playlists) 
    }
    
    override fun onFavoriteContentChanged() { 
        libraryViewModel.forceReload(ReloadType.Playlists) 
    }
}