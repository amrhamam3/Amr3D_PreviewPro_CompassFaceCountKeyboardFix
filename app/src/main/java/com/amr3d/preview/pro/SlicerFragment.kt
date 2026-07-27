package com.amr3d.preview.pro

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment

/**
 * صفحة الـ Slicer - قيد التطوير
 */
class SlicerFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_slicer, container, false)
        AppTheme.applyThemeRecursively(view, requireContext())
        return view
    }

    companion object {
        fun newInstance() = SlicerFragment()
    }
}
