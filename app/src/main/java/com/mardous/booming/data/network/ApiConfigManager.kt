package com.mardous.booming.data.network

import android.content.Context

/**
 * 全局动态接口配置中心
 * 统一管理网易云、QQ音乐等私有/代理 API 的自定义域名与鉴权信息
 */
object ApiConfigManager {
    const val DEFAULT_NETEASE_DOMAIN = "https://my-wangyi-api.onrender.com"
    const val DEFAULT_QQ_DOMAIN = "https://my-qqmusic-api.onrender.com"
    
    // 保持底层配置名不变，防止用户升级后丢失已有设置
    private const val PREF_NAME = "netease_config"
    private const val KEY_NETEASE_DOMAIN = "custom_domain"
    private const val KEY_QQ_DOMAIN = "qq_custom_domain"
    private const val KEY_COOKIE = "user_cookie"
    private const val DEFAULT_COOKIE = ""

    // --- 网易 API 域名 ---
    fun getNeteaseBaseUrl(context: Context): String {
        val custom = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_NETEASE_DOMAIN, "") ?: ""
        return if (custom.isNotBlank()) custom.removeSuffix("/") else DEFAULT_NETEASE_DOMAIN
    }

    fun getNeteaseCustomDomain(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_NETEASE_DOMAIN, "") ?: ""
    }

    // --- QQ 音乐 API 域名 ---
    fun getQqBaseUrl(context: Context): String {
        val custom = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_QQ_DOMAIN, "") ?: ""
        return if (custom.isNotBlank()) custom.removeSuffix("/") else DEFAULT_QQ_DOMAIN
    }

    fun getQqCustomDomain(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_QQ_DOMAIN, "") ?: ""
    }

    // --- 全局 Cookie ---
    fun getCookie(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_COOKIE, DEFAULT_COOKIE) ?: DEFAULT_COOKIE
    }

    // --- 统一保存设置 ---
    fun saveConfig(context: Context, neteaseDomain: String, cookie: String, qqDomain: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_NETEASE_DOMAIN, neteaseDomain.trim())
            .putString(KEY_COOKIE, cookie.trim())
            .putString(KEY_QQ_DOMAIN, qqDomain.trim())
            .apply()
    }
}