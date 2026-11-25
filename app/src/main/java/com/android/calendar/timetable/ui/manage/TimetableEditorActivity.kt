package com.android.calendar.timetable.ui.manage

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.android.calendar.theme.applyTheme
import ws.xsoh.etar.R

/** Hosts the timetable management UI as a normal Etar-style activity. */
class TimetableEditorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        setContentView(R.layout.activity_timetable_editor)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.timetable_editor_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            val fragment = TimetableEditorFragment().apply {
                intent.getLongExtra(EXTRA_TERM_ID, -1L).takeIf { it != -1L }?.let {
                    arguments = Bundle().apply { putLong(EXTRA_TERM_ID, it) }
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.editor_frame, fragment)
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_TERM_ID = "term_id"
    }
}
