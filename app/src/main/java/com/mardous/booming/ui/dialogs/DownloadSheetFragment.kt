package com.mardous.booming.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
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
    private lateinit var rgQuality: RadioGroup // 🌟 新增
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
        rgQuality = view.findViewById(R.id.rgQuality) // 🌟 绑定选项
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

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
                (holder.itemView as TextView).text = "🎵 ${item.title} - ${item.artist}\n💿 专辑: ${item.album} | 💾 [${item.format}] ${item.fileSizeStr}"
                holder.itemView.setOnClickListener { startDownload(item) }
            }
            override fun getItemCount() = resultList.size
        }

        btnSearch.setOnClickListener {
            val input = etInput.text.toString()
            if (input.isNotBlank()) {
                // 🌟 获取用户选择的音质：FLAC 传 lossless，MP3 传 exhigh
                val targetLevel = if (rgQuality.checkedRadioButtonId == R.id.rbFlac) "lossless" else "exhigh"

                recyclerView.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    // 🌟 把音质参数传给搜索引擎
                    val results = UniversalDownloadEngine.searchOrParse(input, targetLevel)
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
        isCancelable = false 
        Toast.makeText(context, "正在下载 [${song.format}]...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            val file = UniversalDownloadEngine.downloadSong(requireContext(), song, targetDir) { }
            
            isCancelable = true 
            btnSearch.isEnabled = true
            progressBar.visibility = View.GONE
            
            if (file != null) {
                Toast.makeText(context, "✅ 下载成功！已存至此文件夹并注入无损标签", Toast.LENGTH_LONG).show()
                dismiss() 
            } else {
                Toast.makeText(context, "❌ 下载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
}