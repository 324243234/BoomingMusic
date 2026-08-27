package com.mardous.booming.ui.screen.library.radios


import androidx.core.content.edit
import com.mardous.booming.core.model.GridViewType
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

    // 🌟 注册系统文件选择器并动态重命名
    private val importM3uLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // 1. 获取选中的真实文件名
                    var fileName = "自定义导入电台"
                    requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) fileName = cursor.getString(nameIndex).substringBeforeLast(".")
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        // 2. 弹出对话框，允许用户修改分类名称
                        val input = android.widget.EditText(requireContext()).apply {
                            setText(fileName)
                            setSingleLine()
                        }
                        val layout = android.widget.LinearLayout(requireContext()).apply {
                            setPadding(60, 20, 60, 0)
                            addView(input)
                        }

                        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle("设置电台分类名称")
                            .setView(layout)
                            .setPositiveButton("导入") { _, _ ->
                                val finalName = input.text.toString().trim().ifEmpty { fileName }
                                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        // 3. 将文件缓存为用户定义的名字
                                        val tempFile = File(requireContext().cacheDir, "${finalName}.m3u")
                                        requireContext().contentResolver.openInputStream(uri)?.use { input ->
                                            FileOutputStream(tempFile).use { it.write(input.readBytes()) }
                                        }
                                        
                                        RadioBackupManager.importRadioFromM3u(requireContext(), repository, tempFile)
                                        tempFile.delete() // 导入完毕后清除临时文件
                                        
                                        withContext(Dispatchers.Main) {
                                            libraryViewModel.forceReload(ReloadType.Playlists)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "导入失败", Toast.LENGTH_SHORT).show() }
                                    }
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                } catch (e: Exception) { }
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
		menu.add(0, 9003, 0, "📁 新建电台分类").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 9001, 0, "导入 M3U 电台源").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 9002, 0, "备份全部电台").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
    }

    // 🌟 处理顶部菜单点击
    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
		    9003 -> {
                // 弹出输入框，新建一个空的电台播放列表
                val editText = android.widget.EditText(requireContext()).apply {
                    hint = "例如: 交通广播"
                    setSingleLine()
                }
                val layout = android.widget.LinearLayout(requireContext()).apply {
                    setPadding(60, 20, 60, 0)
                    addView(editText)
                }

                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("新建电台分类")
                    .setView(layout)
                    .setPositiveButton("创建") { _, _ ->
                        val name = editText.text.toString().trim()
                        if (name.isNotEmpty()) {
                            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                // 🌟 必须加上 [Radio] 前缀进行底层物理隔离
                                val playlistEntity = com.mardous.booming.data.local.room.PlaylistEntity(
                                    playlistName = "[Radio]$name"
                                )
                                repository.createPlaylist(playlistEntity)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    libraryViewModel.forceReload(ReloadType.Playlists)
                                }
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return true
            }
		
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
	
	// 🌟 补全基类要求的网格布局抽象方法
    override fun getSavedViewType(): GridViewType = GridViewType.Normal
    override fun saveViewType(viewType: GridViewType) {}
    override fun getSavedGridSize(): Int = sharedPreferences.getInt("radios_grid_size", defaultGridSize)
    override fun saveGridSize(newGridSize: Int) { sharedPreferences.edit { putInt("radios_grid_size", newGridSize) } }
    override fun onGridSizeChanged(isLand: Boolean, gridColumns: Int) {
        layoutManager?.spanCount = gridColumns
        adapter?.notifyDataSetChanged()
    }
}