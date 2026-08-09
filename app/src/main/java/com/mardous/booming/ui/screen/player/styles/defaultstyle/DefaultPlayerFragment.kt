package com.mardous.booming.ui.screen.player.styles.defaultstyle

import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mardous.booming.R
import com.mardous.booming.core.model.action.NowPlayingAction
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.core.model.player.PlayerColorSchemeMode
import com.mardous.booming.core.model.player.PlayerTintTarget
import com.mardous.booming.core.model.player.surfaceTintTarget
import com.mardous.booming.core.model.player.tintTarget
import com.mardous.booming.core.model.theme.NowPlayingScreen
import com.mardous.booming.data.local.repository.LyricsRepository
import com.mardous.booming.databinding.FragmentDefaultPlayerBinding
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.media.albumArtistName
import com.mardous.booming.extensions.media.displayArtistName
import com.mardous.booming.extensions.resources.applyColor
import com.mardous.booming.extensions.resources.withAlpha
import com.mardous.booming.extensions.whichFragment
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.AbsPlayerFragment
import com.mardous.booming.ui.component.views.MusicSlider
import com.mardous.booming.ui.screen.lyrics.LyricsViewModel
import com.mardous.booming.ui.screen.player.PlayerGesturesController.GestureType
import com.mardous.booming.util.DISPLAY_NEXT_SONG
import com.mardous.booming.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.io.File

class DefaultPlayerFragment : AbsPlayerFragment(R.layout.fragment_default_player),
    SharedPreferences.OnSharedPreferenceChangeListener {
    
    private val sharedPreferences: SharedPreferences by inject()
    private val lyricsViewModel: LyricsViewModel by activityViewModel()
    
    private val repository: com.mardous.booming.data.local.repository.Repository by inject()
    private val lyricsRepository: LyricsRepository by inject()

    private var _binding: FragmentDefaultPlayerBinding? = null
    private val binding get() = _binding!!

    private var lastProcessedSongId: Long = -1L
    private var isDraggingInlineSlider = false
    
    private lateinit var controlsFragment: DefaultPlayerControlsFragment

    // 🌟 核心引擎：动态封面播放器
    private var canvasExoPlayer: ExoPlayer? = null

    // 🌟 优化：使用 lazy 懒加载。仅在第一次横屏用到时才去向系统索要服务，之后永久复用，零开销！
    private val powerManager by lazy { requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val batteryManager by lazy { requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager }

    // 🌟 核心探针：温控与电量检测 (极致版)
    private fun isDeviceStressed(): Boolean {
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (batteryLevel <= 30) return true
        if (powerManager.isPowerSaveMode) return true
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                return true
            }
        }
        return false
    }

    override val playerControlsFragment: AbsPlayerControlsFragment
        get() = controlsFragment

    override val colorSchemeMode: PlayerColorSchemeMode
        get() = Preferences.getNowPlayingColorSchemeMode(NowPlayingScreen.Default)

    override val playerToolbar: Toolbar
        get() = binding.toolbar

    override val blurView: ImageView
        get() = binding.blur

    private var primaryControlColor: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDefaultPlayerBinding.bind(view)
        
        setupToolbar()
        inflateMenuInView(playerToolbar)
        
        ViewCompat.setOnApplyWindowInsetsListener(view) { v: View, insets: WindowInsetsCompat ->
            val systemBars = insets.getInsets(Type.systemBars())
            v.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            val displayCutout = insets.getInsets(Type.displayCutout())
            v.updatePadding(left = displayCutout.left, right = displayCutout.right)
            WindowInsetsCompat.CONSUMED
        }
        Preferences.registerOnSharedPreferenceChangeListener(this)

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        
        // 🌟 极致优化点：只有横屏才会去分配解码器运存！竖屏时解码器为 null，零内存消耗！
        if (isLandscape) {
            canvasExoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f // 强制静音，绝不干扰音频流
            }
            view.findViewById<PlayerView>(R.id.canvasPlayerView)?.player = canvasExoPlayer
        }

        binding.leftFavoriteButton?.setOnClickListener {
            val isFav = it.tag as? Boolean ?: false
            updateFavoriteIcon(!isFav)
            try {
                val intent = android.content.Intent(requireContext(), Class.forName("com.mardous.booming.playback.PlaybackService")).apply {
                    action = "com.mardous.booming.action.ACTION_TOGGLE_FAVORITE"
                }
                requireContext().startService(intent)
            } catch (e: Exception) { e.printStackTrace() }
        }
        
        binding.leftNextButton?.setOnClickListener {
            playerViewModel.seekToNext()
        }

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch {
                playerViewModel.currentSongFlow.collect { song ->
                    if (song != null && song.id != lastProcessedSongId) {
                        kotlinx.coroutines.delay(80)
                        
                        binding.leftSongTitleText?.text = song.title
                        binding.leftSongTitleText?.let { setMarquee(it, marquee = true) }

                        val artist = if (Preferences.preferAlbumArtistName) {
                            song.albumArtistName().displayArtistName()
                        } else {
                            song.displayArtistName()
                        }
                        binding.leftSongArtistText?.text = "- $artist"

                        launch(Dispatchers.IO) {
                            val isFav = repository.isSongFavorite(song.id)
                            withContext(Dispatchers.Main) {
                                updateFavoriteIcon(isFav)
                            }
                        }

                        // 🌟 横屏专属：动态封面静默获取与智能播放逻辑
                        if (isLandscape) {
                            val isVideoEnabled = sharedPreferences.getBoolean("pref_enable_video_cover", true)
                            val isStressed = isDeviceStressed()
                            
                            val playerView = view.findViewById<PlayerView>(R.id.canvasPlayerView)
                            val staticCoverFragment = view.findViewById<View>(R.id.playerAlbumCoverFragment)

                            if (isVideoEnabled && !isStressed) {
                                launch(Dispatchers.IO) {
                                    val videoUri = com.mardous.booming.data.local.lyrics.ttml.AnimatedCanvasFetcher.fetchCanvasUri(song)
                                    withContext(Dispatchers.Main) {
                                        val recheckEnabled = sharedPreferences.getBoolean("pref_enable_video_cover", true)
                                        if (!videoUri.isNullOrBlank() && recheckEnabled && !isDeviceStressed()) {
                                            canvasExoPlayer?.setMediaItem(MediaItem.fromUri(videoUri))
                                            canvasExoPlayer?.prepare()
                                            canvasExoPlayer?.play()
                                            
                                            playerView?.isVisible = true
                                            staticCoverFragment?.isVisible = false
                                        } else {
                                            canvasExoPlayer?.stop()
                                            playerView?.isVisible = false
                                            staticCoverFragment?.isVisible = true
                                        }
                                    }
                                }
                            } else {
                                canvasExoPlayer?.stop()
                                playerView?.isVisible = false
                                staticCoverFragment?.isVisible = true
                            }
                        }

                        lastProcessedSongId = song.id
                    }
                }
            }

            launch {
                playerViewModel.mediaEvent.collect { event ->
                    if (event == com.mardous.booming.core.model.MediaEvent.FavoriteContentChanged) {
                        val currentSong = playerViewModel.currentSongFlow.value
                        if (currentSong != null && currentSong.id != 0L) {
                            launch(Dispatchers.IO) {
                                val isFav = repository.isSongFavorite(currentSong.id)
                                withContext(Dispatchers.Main) {
                                    updateFavoriteIcon(isFav)
                                }
                            }
                        }
                    }
                }
            }
        }

        binding.inlineProgressSlider?.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false 
        }

        binding.inlineProgressSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDraggingInlineSlider = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { progress ->
                    playerViewModel.seekTo(progress.toLong())
                }
                seekBar?.postDelayed({
                    isDraggingInlineSlider = false
                }, 500)
            }
        })

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            val mainSlider = view.findViewById<MusicSlider>(R.id.progressSlider)
            val rightCurrTime = view.findViewById<TextView>(R.id.songCurrentProgress)
            val rightTotTime = view.findViewById<TextView>(R.id.songTotalTime)
            val leftCurrTime = view.findViewById<TextView>(R.id.leftCurrentTime)
            val leftTotTime = view.findViewById<TextView>(R.id.leftTotalTime)

            var lastAppliedColor = android.graphics.Color.TRANSPARENT

            playerViewModel.progressFlow
                .sample(60L) 
                .collect { progress ->
                    if (!isDraggingInlineSlider) {
                        binding.inlineProgressSlider?.let { slider ->
                            val currentProgress = progress.toInt()

                            mainSlider?.let { main ->
                                val max = main.valueTo.toInt()
                                if (slider.max != max) {
                                    slider.max = max
                                    leftTotTime?.text = rightTotTime?.text
                                }
                            
                                val mainColor = main.currentColor
                                if (mainColor != android.graphics.Color.TRANSPARENT && mainColor != lastAppliedColor) {
                                    lastAppliedColor = mainColor 
                                    slider.applyColor(mainColor)
                                    val timeColor = mainColor.withAlpha(0.6f)
                                    leftCurrTime?.applyColor(timeColor)
                                    leftTotTime?.applyColor(timeColor)
                                }
                            }
                            slider.progress = currentProgress

                            rightCurrTime?.text?.let { rightText ->
                                if (leftCurrTime != null && leftCurrTime.text != rightText) {
                                    leftCurrTime.text = rightText
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        binding.leftFavoriteButton?.apply {
            tag = isFavorite
            setImageResource(if (isFavorite) R.drawable.ic_favorite_24dp else R.drawable.ic_favorite_outline_24dp)
        }
    }

    override fun gestureDetected(gestureType: GestureType): Boolean {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            when (gestureType) {
                is GestureType.Tap -> {
                    handleCoverClick()
                    return true
                }
                is GestureType.DoubleTap -> {
                    when (gestureType.type) {
                        GestureType.DoubleTap.TYPE_LEFT_EDGE -> {
                            playerViewModel.seekToPrevious()
                            return true
                        }
                        GestureType.DoubleTap.TYPE_RIGHT_EDGE -> {
                            playerViewModel.seekToNext()
                            return true
                        }
                        else -> {}
                    }
                }
                else -> {}
            }
        }
        return super.gestureDetected(gestureType)
    }

    private fun handleCoverClick() {
        val isLyricsCurrentlyVisible = binding.rightLyricsFragment?.isInvisible == false
        val willShowLyrics = !isLyricsCurrentlyVisible
        
        binding.rightLyricsFragment?.isInvisible = !willShowLyrics
        binding.playbackControlsFragment?.isInvisible = willShowLyrics
        binding.toolbar.isInvisible = willShowLyrics
    }

    private fun setupToolbar() {
        playerToolbar.setNavigationOnClickListener {
            onQuickActionEvent(NowPlayingAction.SoundSettings)
        }
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldPrimaryControlColor = primaryControlColor
        primaryControlColor = scheme.onSurfaceColor

        val targets = mutableListOf(
            binding.root.surfaceTintTarget(scheme.surfaceColor),
            binding.toolbar.tintTarget(oldPrimaryControlColor, scheme.onSurfaceColor)
        )
        
        binding.leftSongTitleText?.let { titleText ->
            targets.add(titleText.tintTarget(titleText.currentTextColor, scheme.onSurfaceColor))
        }
        binding.leftSongArtistText?.let { artistText ->
            val secondaryColor = scheme.onSurfaceColor.withAlpha(0.7f) 
            targets.add(artistText.tintTarget(artistText.currentTextColor, secondaryColor))
        }
        binding.leftFavoriteButton?.let { favBtn ->
            targets.add(favBtn.tintTarget(favBtn.imageTintList?.defaultColor ?: scheme.onSurfaceColor, scheme.onSurfaceColor))
        }
        binding.leftNextButton?.let { nextBtn ->
            targets.add(nextBtn.tintTarget(nextBtn.imageTintList?.defaultColor ?: scheme.onSurfaceColor, scheme.onSurfaceColor))
        }

        targets.addAll(playerControlsFragment.getTintTargets(scheme))
        return targets
    }
    
    private fun toggleLyricsFormat() {
        val currentFormat = sharedPreferences.getString("preferred_lyrics_file_format", "ttml") ?: "ttml"
        val isCurrentlyTtml = currentFormat.equals("ttml", ignoreCase = true) || currentFormat == "0"
        val newFormat = if (isCurrentlyTtml) "lrc" else "ttml"
        
        sharedPreferences.edit(commit = true) {
            putString("preferred_lyrics_file_format", newFormat)
        }
        
        val toastText = if (isCurrentlyTtml) "已切换为 LRC 滚动歌词" else "已切换为 TTML 逐字歌词"
        context?.let { Toast.makeText(it, toastText, Toast.LENGTH_SHORT).show() }
        playerToolbar.menu?.let { updateMenuTitle(it) }
        
        lyricsRepository.clearMemoryCache()
        playerViewModel.currentSongFlow.value?.let { currentSong ->
            lyricsViewModel.updateSong(currentSong)
        }
    }
    
    private fun updateMenuTitle(menu: Menu) {
        val currentFormat = sharedPreferences.getString("preferred_lyrics_file_format", "ttml") ?: "ttml"
        val isCurrentlyTtml = currentFormat.equals("ttml", ignoreCase = true) || currentFormat == "0"
        val toggleItem = menu.findItem(R.id.action_toggle_lyrics_format)
        
        if (isCurrentlyTtml) {
            toggleItem?.title = "当前: TTML逐字"
            toggleItem?.setIcon(R.drawable.ic_lyrics_24dp)
        } else {
            toggleItem?.title = "当前: LRC滚动"
            toggleItem?.setIcon(R.drawable.ic_lyrics_outline_24dp)
        }
    }

    // 🌟 增强功能：忽略大小写 + 暴力清空防死锁的本地物理删除链路
    private fun deleteAssociatedLyricsFiles(song: com.mardous.booming.data.model.Song, onlyTtml: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val songFile = File(song.data)
                val parentDir = songFile.parentFile ?: return@launch
                
                val possibleNamesLower = listOf(
                    songFile.nameWithoutExtension.lowercase(),
                    "${song.artistName} - ${song.title}".lowercase(),
                    "${song.title} - ${song.artistName}".lowercase()
                ).filter { it.isNotBlank() }
                
                var deletedTtml = false
                var deletedLrc = false
                
                val filesInDir = parentDir.listFiles() ?: emptyArray()
                
                for (file in filesInDir) {
                    if (!file.isFile) continue
                    
                    val ext = file.extension.lowercase()
                    if (ext != "ttml" && ext != "lrc") continue
                    
                    val fileNameLower = file.nameWithoutExtension.lowercase()
                    
                    if (possibleNamesLower.contains(fileNameLower)) {
                        if (ext == "ttml") {
                            runCatching { file.writeText("") } // 暴力写空破除系统占用锁
                            if (file.delete() || !file.exists()) {
                                deletedTtml = true
                            } else if (file.length() == 0L) {
                                deletedTtml = true 
                            }
                        }
                        
                        if (!onlyTtml && ext == "lrc") {
                            runCatching { file.writeText("") }
                            if (file.delete() || !file.exists()) {
                                deletedLrc = true
                            } else if (file.length() == 0L) {
                                deletedLrc = true
                            }
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    if (onlyTtml) {
                        val msg = if (deletedTtml) "TTML 歌词文件已彻底删除" else "未找到匹配的本地 TTML 文件"
                        context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
                        
                        if (deletedTtml) {
                            lyricsRepository.clearMemoryCache()
                            lyricsViewModel.updateSong(song)
                        }
                    } else {
                        if (deletedTtml || deletedLrc) {
                            lyricsRepository.clearMemoryCache()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    context?.let { Toast.makeText(it, "删除失败，存储读写异常", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    override fun onMenuInflated(menu: Menu) {
        super.onMenuInflated(menu)
        menu.removeItem(R.id.action_sound_settings)
        menu.setShowAsAction(R.id.action_show_lyrics)
        
        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE

        // 孤立绑定：格式切换按钮
        val toggleItem = menu.findItem(R.id.action_toggle_lyrics_format)
        toggleItem?.setOnMenuItemClickListener {
            toggleLyricsFormat()
            true
        }

        // 🌟 视频封面开关控制逻辑
        val videoToggleItem = menu.findItem(R.id.action_toggle_video_cover)
        if (isLandscapeOrTablet) {
            videoToggleItem?.isVisible = true
            val isVideoEnabled = sharedPreferences.getBoolean("pref_enable_video_cover", true)
            videoToggleItem?.title = if (isVideoEnabled) "视频封面: 开启" else "视频封面: 关闭"
            
            videoToggleItem?.setOnMenuItemClickListener {
                val newState = !sharedPreferences.getBoolean("pref_enable_video_cover", true)
                sharedPreferences.edit(commit = true) { putBoolean("pref_enable_video_cover", newState) }
                videoToggleItem.title = if (newState) "视频封面: 开启" else "视频封面: 关闭"
                
                playerViewModel.currentSongFlow.value?.let { lyricsViewModel.updateSong(it) }
                true
            }
        } else {
            videoToggleItem?.isVisible = false
        }

        // 获取 TTML 按钮逻辑
        menu.findItem(R.id.action_fetch_ttml)?.setOnMenuItemClickListener {
            playerViewModel.currentSongFlow.value?.let { currentSong ->
                val toast = Toast.makeText(context, "正在检索并获取逐字 TTML...", Toast.LENGTH_LONG)
                toast.show()

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val ttmlContent = com.mardous.booming.data.local.lyrics.ttml.TtmlFetcher.fetchTtmlForSong(currentSong)
                    
                    withContext(Dispatchers.Main) {
                        toast.cancel()
                        if (!ttmlContent.isNullOrBlank()) {
                            try {
                                val songFile = File(currentSong.data)
                                val parentDir = songFile.parentFile
                                if (parentDir != null && parentDir.exists()) {
                                    val ttmlFile = File(parentDir, "${songFile.nameWithoutExtension}.ttml")
                                    ttmlFile.writeText(ttmlContent)
                                    
                                    Toast.makeText(context, "获取成功！已保存为 TTML", Toast.LENGTH_SHORT).show()
                                    lyricsRepository.clearMemoryCache()
                                    lyricsViewModel.updateSong(currentSong)
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "保存文件失败，请检查读写权限", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "获取失败：全网未找到该歌曲的逐字歌词", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            true 
        }
        
        // 删除 TTML 按钮
        menu.findItem(R.id.action_delete_ttml)?.setOnMenuItemClickListener {
            playerViewModel.currentSongFlow.value?.let { currentSong ->
                deleteAssociatedLyricsFiles(currentSong, onlyTtml = true)
            }
            true 
        }

        // 完美挂钩系统原有的“删除歌曲”按钮
        menu.findItem(R.id.action_delete_from_device)?.setOnMenuItemClickListener {
            playerViewModel.currentSongFlow.value?.let { currentSong ->
                deleteAssociatedLyricsFiles(currentSong, onlyTtml = false)
            }
            false 
        }

        if (isLandscapeOrTablet) {
            menu.findItem(R.id.action_favorite)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.findItem(R.id.action_go_to_album)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.findItem(R.id.action_go_to_artist)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.findItem(R.id.action_equalizer)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            
            menu.findItem(R.id.action_show_lyrics)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            toggleItem?.apply {
                isVisible = true
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        } else {
            menu.findItem(R.id.action_favorite)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.findItem(R.id.action_go_to_album)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.findItem(R.id.action_go_to_artist)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            
            toggleItem?.isVisible = false
            menu.findItem(R.id.action_show_lyrics)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        
        updateMenuTitle(menu)
        setupQueueMenuItem(menu)
    }

    override fun onCreateChildFragments() {
        super.onCreateChildFragments()
        controlsFragment = whichFragment(R.id.playbackControlsFragment)
    }

    private fun setupQueueMenuItem(menu: Menu = playerToolbar.menu) {
        menu.findItem(R.id.action_playing_queue)?.let {
            it.isVisible = !Preferences.isShowNextSong
            it.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == DISPLAY_NEXT_SONG) {
            setupQueueMenuItem()
        }
    }

    // ==========================================
    // 🌟 生命线的最后护盾：防止后台漏电
    // ==========================================
    override fun onResume() {
        super.onResume()
        if (!isDeviceStressed()) canvasExoPlayer?.play()
    }

    override fun onPause() {
        super.onPause()
        canvasExoPlayer?.pause()
    }

    override fun onDestroyView() {
        Preferences.unregisterOnSharedPreferenceChangeListener(this)
        canvasExoPlayer?.release()
        canvasExoPlayer = null
        super.onDestroyView()
        _binding = null
    }
}