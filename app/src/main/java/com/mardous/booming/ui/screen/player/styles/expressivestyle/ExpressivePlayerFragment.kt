<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?colorSurface"
    android:clickable="true"
    android:focusable="true">

    <ImageView
        android:id="@+id/blur"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:foreground="@drawable/radial_gradient"
        android:importantForAccessibility="no"
        android:scaleType="centerCrop"
        android:visibility="gone"
        tools:src="@tools:sample/backgrounds/scenic"/>

    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/playerToolbar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:layout_constraintTop_toTopOf="parent"
        app:navigationIcon="@drawable/ic_keyboard_arrow_down_24dp" />

    <!-- 完美的 50% 中轴线 -->
    <androidx.constraintlayout.widget.Guideline
        android:id="@+id/center_guideline"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        app:layout_constraintGuide_percent="0.5" />

    <!-- ================= 左侧区域 ================= -->
    <androidx.constraintlayout.widget.ConstraintLayout
        android:id="@+id/startContent"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintTop_toBottomOf="@+id/playerToolbar"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toStartOf="@+id/center_guideline">

        <!-- 底部信息栏（歌名 + 收藏按钮）保持不变 -->
        <androidx.constraintlayout.widget.ConstraintLayout
            android:id="@+id/leftBottomBar"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="24dp"
            android:layout_marginEnd="24dp"
            android:layout_marginBottom="24dp"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent">

            <LinearLayout
                android:id="@+id/titleContainer"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:layout_marginEnd="8dp"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintEnd_toStartOf="@+id/favoriteButton"
                app:layout_constraintTop_toTopOf="parent"
                app:layout_constraintBottom_toBottomOf="parent">

                <com.google.android.material.textview.MaterialTextView
                    android:id="@+id/title"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:freezesText="true"
                    android:singleLine="true"
                    android:ellipsize="end"
                    android:textAppearance="?textAppearanceBodyMedium"
                    android:textStyle="bold" />

                <com.google.android.material.textview.MaterialTextView
                    android:id="@+id/text"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:freezesText="true"
                    android:singleLine="true"
                    android:ellipsize="end"
                    android:textAppearance="?textAppearanceBodySmall"
                    android:textColor="?colorOnSurfaceVariant" />

                <com.google.android.material.textview.MaterialTextView
                    android:id="@+id/songInfo"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:freezesText="true"
                    android:singleLine="true"
                    android:ellipsize="end"
                    android:textAppearance="?textAppearanceBodySmall"
                    android:textColor="?colorOnSurfaceVariant" />
            </LinearLayout>

            <com.google.android.material.button.MaterialButton
                android:id="@+id/favoriteButton"
                style="?materialIconButtonFilledTonalStyle"
                android:layout_width="44dp"
                android:layout_height="44dp"
                android:insetTop="2dp"
                android:insetBottom="2dp"
                android:insetRight="2dp"
                android:insetLeft="2dp"
                app:iconSize="20dp"
                app:icon="@drawable/ic_favorite_outline_24dp"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintTop_toTopOf="parent"
                app:layout_constraintBottom_toBottomOf="parent"/>
        </androidx.constraintlayout.widget.ConstraintLayout>

        <!-- 【重点修复 1】：封面区域释放宽度限制，强制撑满上方空间 -->
        <androidx.fragment.app.FragmentContainerView
            android:id="@+id/playerAlbumCoverFragment"
            android:name="com.mardous.booming.ui.screen.player.cover.CoverPagerFragment"
            android:layout_width="0dp"
            android:layout_height="0dp"
            android:layout_margin="16dp"
            app:layout_constraintDimensionRatio="1:1"
            app:layout_constrainedHeight="true"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintBottom_toTopOf="@+id/leftBottomBar"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintVertical_bias="0.2"
            tools:layout="@layout/fragment_album_cover_m3" />

        <!-- 透明玻璃拦截层 -->
        <View
            android:id="@+id/coverClickOverlay"
            android:layout_width="0dp"
            android:layout_height="0dp"
            android:layout_margin="16dp"
            android:clickable="true"
            android:focusable="true"
            android:elevation="2dp"
            android:background="?selectableItemBackground"
            app:layout_constraintDimensionRatio="1:1"
            app:layout_constrainedHeight="true"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintBottom_toTopOf="@+id/leftBottomBar"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintVertical_bias="0.2" />

    </androidx.constraintlayout.widget.ConstraintLayout>

    <!-- ================= 右侧区域 ================= -->
    <com.google.android.material.button.MaterialButtonGroup
        android:id="@+id/actionGroup"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginBottom="16dp"
        android:gravity="center"
        android:spacing="0dp"
        app:overflowMode="none"
        app:innerCornerSize="8dp"
        app:layout_constraintStart_toEndOf="@+id/center_guideline"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toBottomOf="parent">

        <com.google.android.material.button.MaterialButton android:id="@+id/showLyricsButton" style="?materialIconButtonFilledTonalStyle" android:layout_width="44dp" android:layout_height="44dp" android:insetTop="2dp" android:insetBottom="2dp" android:insetRight="2dp" android:insetLeft="2dp" app:iconSize="20dp" app:icon="@drawable/ic_lyrics_outline_24dp" />
        <com.google.android.material.button.MaterialButton android:id="@+id/repeatButton" style="?materialIconButtonFilledTonalStyle" android:layout_width="44dp" android:layout_height="44dp" android:insetTop="2dp" android:insetBottom="2dp" android:insetRight="2dp" android:insetLeft="2dp" app:iconSize="20dp" app:icon="@drawable/ic_repeat_24dp" />
        <com.google.android.material.button.MaterialButton android:id="@+id/shuffleButton" style="?materialIconButtonFilledTonalStyle" android:layout_width="44dp" android:layout_height="44dp" android:insetTop="2dp" android:insetBottom="2dp" android:insetRight="2dp" android:insetLeft="2dp" app:iconSize="20dp" app:icon="@drawable/ic_shuffle_24dp" />
        <com.google.android.material.button.MaterialButton android:id="@+id/openQueueButton" style="?materialIconButtonFilledTonalStyle" android:layout_width="44dp" android:layout_height="44dp" android:insetTop="2dp" android:insetBottom="2dp" android:insetRight="2dp" android:insetLeft="2dp" app:iconSize="20dp" app:icon="@drawable/ic_queue_music_24dp" />
        <com.google.android.material.button.MaterialButton android:id="@+id/moreButton" style="?materialIconButtonFilledTonalStyle" android:layout_width="44dp" android:layout_height="44dp" android:insetTop="2dp" android:insetBottom="2dp" android:insetRight="2dp" android:insetLeft="2dp" app:iconSize="20dp" app:icon="@drawable/ic_more_vert_24dp" />
    </com.google.android.material.button.MaterialButtonGroup>

    <!-- 【重点修复 2】：为进度条增加顶部约束，并让它悬浮在右侧中央 -->
    <androidx.fragment.app.FragmentContainerView
        android:id="@+id/playbackControlsFragment"
        android:name="com.mardous.booming.ui.screen.player.styles.expressivestyle.ExpressivePlayerControlsFragment"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintTop_toBottomOf="@+id/playerToolbar"
        app:layout_constraintBottom_toTopOf="@+id/actionGroup"
        app:layout_constraintStart_toEndOf="@+id/center_guideline"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintVertical_bias="0.55"
        tools:layout="@layout/fragment_expressive_player_playback_controls" />

    <androidx.constraintlayout.widget.Group
        android:id="@+id/rightControlsGroup"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        app:constraint_referenced_ids="playbackControlsFragment,actionGroup" />

    <!-- 纯净全屏歌词面板：右侧已极限贴边 -->
    <androidx.fragment.app.FragmentContainerView
        android:id="@+id/rightLyricsFragment"
        android:name="com.mardous.booming.ui.screen.player.cover.CoverLyricsFragment"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginStart="8dp"
        android:layout_marginEnd="0dp"
        android:layout_marginBottom="0dp"
        android:visibility="gone"
        app:layout_constraintTop_toBottomOf="@+id/playerToolbar"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toEndOf="@+id/center_guideline"
        app:layout_constraintEnd_toEndOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>