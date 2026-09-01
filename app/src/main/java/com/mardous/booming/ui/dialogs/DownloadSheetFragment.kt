package com.mardous.booming.ui.dialogs

import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
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

class DownloadSheetFragment : BottomSheetDialogFragment() {
    private lateinit var etInput: EditText
    private lateinit var btnSearch: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var rgQuality: RadioGroup // 🌟 绑定音质选项
    private val resultList = mutableListOf<UniversalDownloadEngine.NetSongItem>()
    
    // 🌟 内存防漏核心：持有当前正在进行的搜索任务
    private var searchJob: Job? = null 
	
	// 🌟 新增：重写 onStart()，强制展开解决平板大屏显示异常
    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
        if (bottomSheet != null) {
            val behavior = BottomSheetBehavior.from(bottomSheet)
            
            // 1. 强制完全展开状态，避免在平板上被折叠成一条缝
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            
            // 2. 在平板上放开原本的 peekHeight 限制
            behavior.skipCollapsed = true
            
            // 3. 在大屏/平板横屏下，限定 BottomSheet 最大宽度防止过宽拉伸，居中显示
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val isTablet = screenWidth > 600 * displayMetrics.density
            if (isTablet) {
                val layoutParams = bottomSheet.layoutParams
                layoutParams.width = (500 * displayMetrics.density).toInt() // 限制在约 500dp 宽
                bottomSheet.layoutParams = layoutParams
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
                
                // 🌟 将毫秒换算为 MM:SS 格式，列表显示更加直观
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
                // 🌟 细节优化：点击搜索后自动隐藏软键盘，留出空间看 80 首列表
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(etInput.windowToken, 0)

                val targetLevel = if (rgQuality.checkedRadioButtonId == R.id.rbFlac) "lossless" else "exhigh"

                recyclerView.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                
                // 🌟 性能核弹 1：如果用户狂点搜索，立刻切断上一个网络请求，防止内存溢出！
                searchJob?.cancel()
                
                searchJob = lifecycleScope.launch {
                    // 🌟 性能核弹 2：在发起新请求前，立刻清空旧的数据对象，为 GC 腾出空间
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
        
		// 🌟 新增：在这里直接生成目标路径，再也不需要外部通过参数传进来
        val targetDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
            "newdown"
        )
        if (!targetDir.exists()) targetDir.mkdirs()
		
		
        lifecycleScope.launch {
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
