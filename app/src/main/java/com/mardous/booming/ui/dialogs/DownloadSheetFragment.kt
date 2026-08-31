package com.mardous.booming.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

// 1. 移除这里的 (private val targetDir: File)，改为空构造函数
class DownloadSheetFragment : BottomSheetDialogFragment() {
    
    // 2. 延迟获取 targetDir
    private val targetDir: File by lazy {
        val path = arguments?.getString("KEY_TARGET_DIR") ?: ""
        File(path)
    }

    private lateinit var etInput: EditText
    private lateinit var btnSearch: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var rgQuality: RadioGroup 
    private val resultList = mutableListOf<UniversalDownloadEngine.NetSongItem>()
    
    private var searchJob: Job? = null 

    // 3. 添加 companion object 规范实例化方法
    companion object {
        fun newInstance(targetDir: File): DownloadSheetFragment {
            return DownloadSheetFragment().apply {
                arguments = Bundle().apply {
                    putString("KEY_TARGET_DIR", targetDir.absolutePath)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_download_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        etInput = view.findViewById(R.id.etInput)
        btnSearch = view.findViewById(R.id.btnSearch)
        progressBar = view.findViewById(R.id.progressBar)
        recyclerView = view.findViewById(R.id.recyclerView)
        rgQuality = view.findViewById(R.id.rgQuality) 
        
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
                
                val totalSeconds = item.durationMs / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                val timeString = String.format("%02d:%02d", minutes, seconds)

                (holder.itemView as TextView).text = "🎵 ${item.title} - ${item.artist}\n💿 专辑: ${item.album} | ⏱ $timeString | 💾 [${item.format}] ${item.fileSizeStr}"
                holder.itemView.setOnClickListener { startDownload(item) }
            }
            override fun getItemCount() = resultList.size
        }

        btnSearch.setOnClickListener {
            val input = etInput.text.toString()
            if (input.isNotBlank()) {
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(etInput.windowToken, 0)

                val targetLevel = if (rgQuality.checkedRadioButtonId == R.id.rbFlac) "lossless" else "exhigh"

                recyclerView.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                
                searchJob?.cancel()
                
                searchJob = lifecycleScope.launch {
                    resultList.clear()
                    recyclerView.adapter?.notifyDataSetChanged()

                    val results = UniversalDownloadEngine.searchOrParse(requireContext(), input, targetLevel)
                    
                    progressBar.visibility = View.GONE
                    if (results.isEmpty()) {
                        Toast.makeText(context, "未找到结果，请更换关键词", Toast.LENGTH_SHORT).show()
                    } else {
                        resultList.addAll(results)
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
        Toast.makeText(context, "正在提取 [${song.format}] 直链...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            // 4. 这里依然可以直接使用 targetDir，因为上面通过 by lazy 初始化了
            val file = UniversalDownloadEngine.downloadSong(requireContext(), song, targetDir) { }
            
            isCancelable = true 
            btnSearch.isEnabled = true
            progressBar.visibility = View.GONE
            
            if (file != null) {
                Toast.makeText(context, "✅ 下载成功！已存至此文件夹并注入满血标签", Toast.LENGTH_LONG).show()
                dismiss() 
            } else {
                Toast.makeText(context, "❌ 下载失败或无法获取真实地址", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
