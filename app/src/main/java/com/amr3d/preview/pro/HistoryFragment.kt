package com.amr3d.preview.pro

import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import java.io.File

class HistoryFragment : Fragment() {

    interface OnFileSelectedListener { fun onFileSelected(file: File) }
    var fileSelectedListener: OnFileSelectedListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view     = inflater.inflate(R.layout.fragment_history, container, false)
        AppTheme.applyThemeRecursively(view, requireContext())
        val listView = view.findViewById<ListView>(R.id.historyList)
        val empty    = view.findViewById<TextView>(R.id.emptyHistory)
        val btnClear = view.findViewById<Button>(R.id.btnClearHistory)

        fun refresh() {
            val history = loadHistory(requireContext())
            if (history.isEmpty()) {
                empty.visibility    = View.VISIBLE
                listView.visibility = View.GONE
            } else {
                empty.visibility    = View.GONE
                listView.visibility = View.VISIBLE

                val items = history.map { path ->
                    val f = File(path)
                    val ext  = f.extension.uppercase()
                    val icon = if (ext == "STL") "🧊" else if (ext == "DXF") "📐" else "📄"
                    val size = if (f.exists()) {
                        val kb = f.length() / 1024
                        if (kb >= 1024) "${"%.1f".format(kb/1024f)} MB" else "$kb KB"
                    } else getString(R.string.history_file_missing)
                    "$icon  ${f.name}\n    $size  •  ${f.parent}"
                }

                val adapter = ArrayAdapter(requireContext(),
                    android.R.layout.simple_list_item_2,
                    android.R.id.text1, items)
                listView.adapter = adapter

                listView.setOnItemClickListener { _, _, pos, _ ->
                    val file = File(history[pos])
                    if (file.exists()) fileSelectedListener?.onFileSelected(file)
                    else Toast.makeText(context, getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
                }
            }
        }

        refresh()

        btnClear.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.dialog_clear_history_title))
                .setMessage(getString(R.string.dialog_clear_history_message))
                .setPositiveButton(getString(R.string.dialog_clear)) { _, _ ->
                    clearHistory(requireContext()); refresh()
                }
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show()
        }

        return view
    }

    companion object {
        private const val PREF_KEY   = "file_history"
        private const val MAX_HISTORY = 20

        fun addToHistory(context: Context, path: String) {
            // تجاهل مسارات SAF (primary:، content://) غير القابلة للوصول لاحقاً
            if (path.startsWith("primary:") || path.startsWith("content://") && !path.contains("external")) return
            val prefs   = context.getSharedPreferences("amr3d_prefs", Context.MODE_PRIVATE)
            val history = loadHistory(context).toMutableList()
            history.remove(path)
            history.add(0, path)
            if (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
            prefs.edit().putString(PREF_KEY, history.joinToString("|")).apply()
        }

        fun loadHistory(context: Context): List<String> {
            val str = context.getSharedPreferences("amr3d_prefs", Context.MODE_PRIVATE)
                .getString(PREF_KEY, "") ?: ""
            if (str.isEmpty()) return emptyList()
            // نظّف المسارات غير الموجودة تلقائياً
            val valid = str.split("|").filter { it.isNotEmpty() && java.io.File(it).exists() }
            if (valid.size < str.split("|").size) {
                // حفظ القائمة المنظفة
                context.getSharedPreferences("amr3d_prefs", Context.MODE_PRIVATE)
                    .edit().putString(PREF_KEY, valid.joinToString("|")).apply()
            }
            return valid
        }

        fun clearHistory(context: Context) {
            context.getSharedPreferences("amr3d_prefs", Context.MODE_PRIVATE)
                .edit().remove(PREF_KEY).apply()
        }
    }
}
