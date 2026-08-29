package com.mardous.booming.ui.screen.library.radios



import com.mardous.booming.ui.component.menu.onPlaylistMenu
import com.mardous.booming.ui.component.menu.onPlaylistsMenu
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
	private var currentSearchQuery: String = ""
    override val titleRes: Int = R.string.radios_label
    
    override val emptyMessageRes: Int
        get() = R.string.no_device_playlists

    override val maxGridSize: Int
        get() = if (isLandscape) resources.getInteger(R.integer.max_playlist_columns_land)
        else resources.getInteger(R.integer.max_playlist_columns)

    override val itemLayoutRes: Int
        get() = if (isGridMode) R.layout.item_playlist else R.layout.item_list
		
	private var originalRadios: List<PlaylistWithSongs> = emptyList()

    // 🌟 升级为 GetMultipleContents 支持批量多选导入
    private val importM3uLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val toast = Toast.makeText(requireContext(), "正在批量导入 ${uris.size} 个电台源...", Toast.LENGTH_LONG)
            toast.show()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                var successCount = 0
                for (uri in uris) {
                    try {
                        var fileName = "电台源_${System.currentTimeMillis()}"
                        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIndex >= 0) fileName = cursor.getString(nameIndex).substringBeforeLast(".")
                            }
                        }
                        val tempFile = File(requireContext().cacheDir, "${fileName}.m3u")
                        requireContext().contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile).use { it.write(input.readBytes()) }
                        }
                        com.mardous.booming.util.RadioBackupManager.importRadioFromM3u(requireContext(), repository, tempFile)
                        tempFile.delete()
                        successCount++
                    } catch (e: Exception) { }
                }
                withContext(Dispatchers.Main) {
                    toast.cancel()
                    Toast.makeText(requireContext(), "成功批量导入 $successCount 个电台分类！", Toast.LENGTH_SHORT).show()
                    libraryViewModel.forceReload(com.mardous.booming.ui.screen.library.ReloadType.Playlists)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        libraryViewModel.getRadioPlaylists().observe(viewLifecycleOwner) { radios ->
            val sortedRadios = radios.sortedByDescending { 
                it.playlistEntity.playlistName.contains("我的电台") || it.playlistEntity.playlistName.contains("收藏")
            }
            originalRadios = sortedRadios
            
            // 🌟 修复跳回全量列表的隐患：刷新时强制维持搜索词
            adapter?.dataSet = if (currentSearchQuery.isNotEmpty()) {
                sortedRadios.filter { it.playlistEntity.playlistName.lowercase().contains(currentSearchQuery) }
            } else {
                sortedRadios
            }
            adapter?.notifyDataSetChanged()
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
		
		// 🌟 1. 注入原生搜索框
        val searchItem = menu.add(0, 9005, 0, "搜索电台").apply {
            setIcon(R.drawable.ic_search_24dp)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        }
        val searchView = androidx.appcompat.widget.SearchView(requireContext()).apply {
            queryHint = "搜索电台名称..."
            setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = true
                override fun onQueryTextChange(newText: String?): Boolean {
                    currentSearchQuery = newText?.trim()?.lowercase() ?: ""
                    adapter?.dataSet = if (currentSearchQuery.isEmpty()) {
                        originalRadios
                    } else {
                        originalRadios.filter { 
                            it.playlistEntity.playlistName.lowercase().contains(currentSearchQuery) 
                        }
                    }
                    adapter?.notifyDataSetChanged()
                    return true
                }
            })
        }
        searchItem.actionView = searchView
        
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

    override fun playlistMenuItemClick(playlist: PlaylistWithSongs, menuItem: MenuItem): Boolean {
        // 🌟 致命架构隐患阻断：封杀重命名功能，强保 [Radio] 隔离标识不被用户意外删掉
        if (menuItem.itemId == R.id.action_rename_playlist) {
            Toast.makeText(requireContext(), "为保证数据库物理隔离，电台分类不支持修改名称，请新建并导入。", Toast.LENGTH_LONG).show()
            return true
        }
        // 放行“删除”、“播放”等安全指令
        return playlist.onPlaylistMenu(this, menuItem)
    }
    override fun playlistsMenuItemClick(playlists: List<PlaylistWithSongs>, menuItem: MenuItem) {
        // 🌟 激活长按多选后的批量删除与操作
        playlists.onPlaylistsMenu(this, menuItem)
    }
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