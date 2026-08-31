package com.mardous.booming.ui.screen

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.app.SearchManager
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.mardous.booming.R
import com.mardous.booming.core.model.CategoryInfo
import com.mardous.booming.core.model.MediaEvent
import com.mardous.booming.core.model.shuffle.OpenShuffleMode
import com.mardous.booming.data.model.ContentType
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.extensions.currentFragment
import com.mardous.booming.extensions.navigation.detailArgs
import com.mardous.booming.extensions.navigation.isValidCategory
import com.mardous.booming.extensions.showToast
import com.mardous.booming.extensions.utilities.toEnum
import com.mardous.booming.extensions.whichFragment
import com.mardous.booming.playback.Playback
import com.mardous.booming.playback.library.MediaIDs
import com.mardous.booming.playback.library.SearchQueryProvider
import com.mardous.booming.ui.IScrollHelper
import com.mardous.booming.ui.component.base.AbsSlidingMusicPanelActivity
import com.mardous.booming.ui.screen.library.search.SearchFragment
import com.mardous.booming.ui.screen.update.UpdateDialog
import com.mardous.booming.ui.screen.update.UpdateSearchResult
import com.mardous.booming.ui.screen.update.UpdateViewModel
import com.mardous.booming.util.Preferences
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * @author Christians M. A. (mardous)
 */
class MainActivity : AbsSlidingMusicPanelActivity(), MediaController.Listener {

    private val updateViewModel: UpdateViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = null

        updateTabs()
        setupNavigationController()

        // 🌟 本地修改：监听 Mini 播放器高度，动态垫高导航栏，防止被遮挡 🌟
        // 🌟 本地修复：区分横竖屏（平板与手机）模式，动态应用 Padding 🌟
        libraryViewModel.getMiniPlayerMargin().observe(this) { margin ->
            val bottomMargin = margin.getWithSpace()
            // 判断当前是否为横屏/平板模式
            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            
            if (isLandscape) {
                // 平板/横屏模式（侧边栏）：底部垫高，防止侧边栏底部的图标被 Mini 播放条遮挡
                navigationView.setPadding(
                    navigationView.paddingLeft, 
                    navigationView.paddingTop, 
                    navigationView.paddingRight, 
                    bottomMargin
                )
            } else {
                // 竖屏模式（底部导航栏）：不能加 bottom padding，否则底部导航栏拉高会反向遮盖 Mini 播放条
                navigationView.setPadding(
                    navigationView.paddingLeft, 
                    navigationView.paddingTop, 
                    navigationView.paddingRight, 
                    0
                )
            }
        }

        val shortcutManager = getSystemService<ShortcutManager>()
        shortcutManager?.removeDynamicShortcuts(OLD_SHORTCUT_IDS)

        prepareUpdateViewModel()
		
		// 👇================ 🌟 新增：Render 静默唤醒预热 ================👇
        // 提前 30 秒无感唤醒后台 API，确保你点开播放界面时，动态封面能 3 秒内极速出图！
        lifecycleScope.launch(Dispatchers.IO) {
            com.mardous.booming.data.network.NeteaseDailyApi.wakeUpAndRefresh(applicationContext)
        }
    }

    override fun onConnected(controller: MediaController) {
        super.onConnected(controller)
        intent?.let { handlePlaybackIntent(it, true) }
    }

    @OptIn(UnstableApi::class)
    override fun onCustomCommand(
        controller: MediaController,
        command: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        val sessionResult = when (command.customAction) {
            Playback.EVENT_MEDIA_CONTENT_CHANGED -> {
                playerViewModel.submitEvent(MediaEvent.MediaContentChanged)
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            Playback.EVENT_FAVORITE_CONTENT_CHANGED -> {
                playerViewModel.submitEvent(MediaEvent.FavoriteContentChanged)
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            Playback.EVENT_PLAYBACK_STARTED -> {
                playerViewModel.submitEvent(MediaEvent.PlaybackStarted)
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            Playback.EVENT_PLAYBACK_RESTORED -> {
                playerViewModel.submitEvent(MediaEvent.PlaybackRestored)
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            else -> SessionResult(SessionError.ERROR_NOT_SUPPORTED)
        }
        return Futures.immediateFuture(sessionResult)
    }

    fun scanAllPaths() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_media)
            .setMessage(R.string.scan_media_message)
            .setPositiveButton(R.string.scan_media_positive) { _, _ ->
                libraryViewModel.scanAllPaths(this).observe(this) {
                    // TODO show detailed info about scanned songs
                    showToast(R.string.scan_finished)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupNavigationController() {
        val navController = whichFragment<NavHostFragment>(R.id.fragment_container).navController
        val navInflater = navController.navInflater
        val navGraph = navInflater.inflate(R.navigation.graph_main)

        val categoryInfo: CategoryInfo = Preferences.libraryCategories.first { it.visible }
        if (categoryInfo.visible) {
            val lastPage = Preferences.lastPage
            if (!navGraph.isValidCategory(lastPage)) {
                Preferences.lastPage = categoryInfo.category.id
                navGraph.setStartDestination(categoryInfo.category.id)
            } else {
                navGraph.setStartDestination(
                    if (Preferences.isRememberLastPage) {
                        lastPage.let {
                            if (it == 0) {
                                categoryInfo.category.id
                            } else {
                                it
                            }
                        }
                    } else categoryInfo.category.id
                )
            }
        }

        navController.graph = navGraph
        navigationView.setupWithNavController(navController)
        // Scroll Fragment to top
        navigationView.setOnItemReselectedListener {
            currentFragment(R.id.fragment_container).apply {
                if (this is IScrollHelper) {
                    scrollToTop()
                }
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == navGraph.startDestinationId) {
                currentFragment(R.id.fragment_container)?.enterTransition = null
            }
            if (destination.navigatorName == "dialog") {
                return@addOnDestinationChangedListener
            }
            when (destination.id) {
                R.id.nav_home,
                R.id.nav_songs,
                R.id.nav_albums,
                R.id.nav_artists,
                R.id.nav_folders,
                R.id.nav_playlists,
                R.id.nav_genres,
				R.id.nav_radios,
                R.id.nav_years -> {
                    // Save the last tab
                    if (Preferences.isRememberLastPage) {
                        saveTab(destination.id)
                    }
                    // Show Bottom Navigation Bar
                    setBottomNavVisibility(visible = true, animate = true)
                }

                R.id.nav_queue,
                R.id.nav_lyrics_editor,
                R.id.nav_play_info,
                R.id.nav_about -> {
                    setBottomNavVisibility(visible = false, hideBottomSheet = true)
                }

                else -> setBottomNavVisibility(visible = false, animate = true) // Hide Bottom Navigation Bar
            }
        }
    }

    private fun saveTab(id: Int) {
        if (Preferences.libraryCategories.firstOrNull { it.category.id == id }?.visible == true) {
            Preferences.lastPage = id
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePlaybackIntent(intent, false)
    }

    @Suppress("DEPRECATION")
    private fun handlePlaybackIntent(intent: Intent, canRestorePlayback: Boolean) {
        when (intent.action) {
            // 🌟 作者新增：外部通过 Intent 直接跳转至特定的分类/列表内容页
            ACTION_SHOW_CONTENT -> {
                intent.getStringExtra(EXTRA_CONTENT_TYPE)?.toEnum<ContentType>()?.let { type ->
                    whichFragment<NavHostFragment>(R.id.fragment_container).navController
                        .navigate(R.id.nav_detail_list, detailArgs(type))
                }
                setIntent(Intent())
            }

            // 🌟 本地修改：新增拦截小爱同学 Activity 级别语音搜索广播
            android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val query = intent.getStringExtra(android.app.SearchManager.QUERY) ?: ""
                if (query.isNotBlank()) {
                    // 拦截成功！小爱唤起了 App。
                    showToast("语音唤醒搜索: $query")
                    // TODO: 在这里通知你的 libraryViewModel 或 playerViewModel 去执行搜索并播放
                    // libraryViewModel.searchAndPlay(query)
                }
                setIntent(Intent())
            }

            APP_SHORTCUT_LAST_ADDED -> {
                playerViewModel.playMediaId(MediaIDs.LAST_ADDED)
                ShortcutManagerCompat.reportShortcutUsed(this, "last_added")
                setIntent(Intent())
            }
            APP_SHORTCUT_TOP_TRACKS -> {
                playerViewModel.playMediaId(MediaIDs.TOP_TRACKS)
                ShortcutManagerCompat.reportShortcutUsed(this, "top_tracks")
                setIntent(Intent())
            }
            APP_SHORTCUT_SHUFFLE -> {
                playerViewModel.playMediaId(MediaIDs.SONGS, true)
                ShortcutManagerCompat.reportShortcutUsed(this, "shuffle_all")
                setIntent(Intent())
            }
            APP_SHORTCUT_FAVORITES -> {
                playerViewModel.playMediaId(MediaIDs.FAVORITES, true)
                ShortcutManagerCompat.reportShortcutUsed(this, "favorites")
                setIntent(Intent())
            }

            Intent.ACTION_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY)
                whichFragment<NavHostFragment>(R.id.fragment_container).navController
                    .navigate(
                        R.id.nav_search,
                        Bundle().apply { putString(SearchFragment.QUERY, query) }
                    )
                setIntent(Intent())
            }

            MediaStore.INTENT_ACTION_MEDIA_SEARCH,
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                SearchQueryProvider.handleSearchIntent(intent) { mainQuery, subQueries ->
                    if (mainQuery == null) {
                        showToast(R.string.invalid_search_params)
                    } else {
                        if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
                            playerViewModel.playMediaItem(
                                MediaItem.Builder()
                                    .setRequestMetadata(
                                        MediaItem.RequestMetadata.Builder()
                                            .setSearchQuery(mainQuery)
                                            .setExtras(subQueries)
                                            .build()
                                    )
                                    .build()
                            )
                        } else {
                            whichFragment<NavHostFragment>(R.id.fragment_container).navController
                                .navigate(
                                    R.id.nav_search,
                                    Bundle().apply { putString(SearchFragment.QUERY, mainQuery) }
                                )
                        }
                    }
                }
            }

            else -> {
                libraryViewModel.handleIntent(intent).observe(this) { result ->
                    if (result.handled) {
                        if (result.songs.isNotEmpty()) {
                            playerViewModel.openQueue(
                                queue = result.songs,
                                position = result.position,
                                shuffleMode = OpenShuffleMode.Off
                            )
                        }
                        setIntent(Intent())
                    } else if (canRestorePlayback) {
                        playerViewModel.restorePlayback()
                    }
                    if (result.failed) {
                        showToast(R.string.unplayable_file)
                    }
                }
            }
        }
    }

    private fun prepareUpdateViewModel() {
        updateViewModel.run {
            updateEventObservable.observe(this@MainActivity) { event ->
                event.getContentIfNotConsumed()?.let { result ->
                    when (result.state) {
                        UpdateSearchResult.State.Completed -> {
                            val release = result.data ?: return@let
                            if (result.wasFromUser || release.isDownloadable(this@MainActivity)) {
                                val existingDialog = supportFragmentManager.findFragmentByTag("UPDATE_FOUND")
                                if (existingDialog == null) {
                                    UpdateDialog().show(supportFragmentManager, "UPDATE_FOUND")
                                }
                            }
                        }
                        UpdateSearchResult.State.Failed -> {
                            if (result.wasFromUser) {
                                showToast(R.string.could_not_check_for_updates)
                            }
                        }
                        else -> {}
                    }
                }
            }
            updateEvent?.peekContent().let { updateState ->
                if (updateState == null || updateState.state == UpdateSearchResult.State.Idle) {
                    if (NetworkFeature.Updater.isAvailable) {
                        searchForUpdate(false)
                    }
                }
            }
        }
    }

	override fun onDestroy() {
        super.onDestroy()
        // 🌟 退出时执行强制清理，打扫网易云在线播放与下载产生的缓存碎片
        com.mardous.booming.util.NeteaseCacheSweeper.cleanUp(applicationContext)
    }
    companion object {
        // 🌟 作者新增：页面内容跳转所使用的系统静态标识
        const val ACTION_SHOW_CONTENT = "com.mardous.booming.action.SHOW_CONTENT"
        const val EXTRA_CONTENT_TYPE = "com.mardous.booming.extra.CONTENT_TYPE"

        private const val APP_SHORTCUT_LAST_ADDED = "com.mardous.booming.shortcut.LAST_ADDED"
        private const val APP_SHORTCUT_TOP_TRACKS = "com.mardous.booming.shortcut.TOP_TRACKS"
        private const val APP_SHORTCUT_SHUFFLE = "com.mardous.booming.shortcut.SHUFFLE"
        private const val APP_SHORTCUT_FAVORITES = "com.mardous.booming.shortcut.FAVORITES"

        private val OLD_SHORTCUT_IDS = listOf(
            "com.mardous.booming.appshortcuts.id.last_added",
            "com.mardous.booming.appshortcuts.id.top_tracks",
            "com.mardous.booming.appshortcuts.id.shuffle_all",
        )
    }
}