package com.mardous.booming.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.mardous.booming.R
import com.mardous.booming.data.local.lyrics.ttml.UniversalDownloadEngine
import kotlinx.coroutines.launch
import java.io.File

class DownloadSheetFragment(private val targetDir: File) : BottomSheetDialogFragment() {
    private lateinit var etInput: EditText
    private lateinit var btnSearch: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private val resultList = mutableListOf<UniversalDownloadEngine.NetSongItem>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_download_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        etInput = view.findViewById(R.id.etInput)
        btnSearch = view.findViewById(R.id.btnSearch)
        progressBar = view.findViewById(R.id.progressBar)
        recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // 极简内部 Adapter 显示 5 首歌曲详情
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply { 
                    setPadding(0, 24, 0, 24)
                    textSize = 14f 
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = resultList[position]
                // 🌟 这里按照你要求，展示了 歌名、专辑、文件大小、和是否是FLAC
                (holder.itemView as TextView).text = "🎵 ${item.title} - ${item.artist}\n💿 专辑: ${item.album} | 💾 [${item.format}] ${item.fileSizeStr}"
                holder.itemView.setOnClickListener { startDownload(item) }
            }
            override fun getItemCount() = resultList.size
        }

        btnSearch.setOnClickListener {
            val input = etInput.text.toString()
            if (input.isNotBlank()) {
                recyclerView.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val results = UniversalDownloadEngine.searchOrParse(input)
                    progressBar.visibility = View.GONE
                    if (results.isEmpty()) {
                        Toast.makeText(context, "未找到结果，请更换关键词", Toast.LENGTH_SHORT).show()
                    } else {
                        resultList.clear(); resultList.addAll(results)
                        recyclerView.adapter?.notifyDataSetChanged()
                        recyclerView.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun startDownload(song: UniversalDownloadEngine.NetSongItem) {
        btnSearch.isEnabled = false
        progressBar.visibility = View.VISIBLE
        
        // 🌟 防误触：在下载途中强制锁定对话框，禁止用户点击外部或下滑关闭
        isCancelable = false 
        
        Toast.makeText(context, "正在下载 [${song.format}]...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            val file = UniversalDownloadEngine.downloadSong(song, targetDir) { /* 可选更新进度条 */ }
            
            // 🌟 释放锁
            isCancelable = true 
            btnSearch.isEnabled = true
            progressBar.visibility = View.GONE
            
            if (file != null) {
                Toast.makeText(context, "✅ 下载成功！已存至此文件夹并注入无损标签", Toast.LENGTH_LONG).show()
                dismiss() // 成功后自动优雅收起抽屉
            } else {
                Toast.makeText(context, "❌ 下载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
}