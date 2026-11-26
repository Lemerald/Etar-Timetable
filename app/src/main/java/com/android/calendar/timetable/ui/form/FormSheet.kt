/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable.ui.form

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import ws.xsoh.etar.R

/**
 * A full-height editor sheet: close button, title and Save in a header, and a
 * scrolling column of labelled Material fields below. The builder methods add
 * fields to the current container (the sheet, or a [collapsible] group).
 *
 * [onSave] returns true to close the sheet; on false it stays open (e.g. after
 * [requireText] marked a field).
 */
class FormSheet(context: Context, title: String, private val onSave: () -> Boolean) {

    val context: Context = context
    private val dialog = BottomSheetDialog(context)
    private val inflater = LayoutInflater.from(context)
    private val root = inflater.inflate(R.layout.form_sheet, null)
    private var container: LinearLayout = root.findViewById(R.id.form_content)
    private var firstSection = true
    private var autoFocus: View? = null

    init {
        root.findViewById<TextView>(R.id.form_title).text = title
        root.findViewById<View>(R.id.form_close).setOnClickListener { dialog.cancel() }
        root.findViewById<View>(R.id.form_save).setOnClickListener {
            hideKeyboard()
            if (onSave()) dialog.dismiss()
        }
        dialog.setContentView(root)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    fun setSaveText(text: String) {
        root.findViewById<MaterialButton>(R.id.form_save).text = text
    }

    fun show() {
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
            dialog.behavior.apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
                // Dragging would fight with scrolling the form and lose input by accident.
                isDraggable = false
            }
            autoFocus?.let { view ->
                view.requestFocus()
                view.post {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        dialog.show()
    }

    fun dismiss() = dialog.dismiss()

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
    }

    // ---- Structure --------------------------------------------------------

    /** A small accent-coloured heading that starts a group of fields. */
    fun section(title: String): TextView =
        TextView(context).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            setPadding(0, dp(if (firstSection) 12 else 28), 0, 0)
            firstSection = false
        }.also(container::addView)

    /** Secondary explanatory text. */
    fun note(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        setPadding(dp(4), dp(8), dp(4), 0)
    }.also(container::addView)

    /**
     * Fields hidden behind a "More options" style button, expanded from the start
     * when [expanded]. Builder calls inside [build] go into the group.
     */
    fun collapsible(buttonLabel: String, expanded: Boolean, build: () -> Unit) {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        if (!expanded) {
            textButton(buttonLabel, R.drawable.ic_add) { button ->
                group.visibility = View.VISIBLE
                button.visibility = View.GONE
            }
        }
        container.addView(group)
        inside(group, build)
    }

    /** Runs [build] with fields going into [group]. */
    fun inside(group: LinearLayout, build: () -> Unit) {
        val outer = container
        container = group
        try { build() } finally { container = outer }
    }

    /** A vertical group whose visibility the caller controls. */
    fun group(build: () -> Unit): LinearLayout {
        val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        container.addView(group)
        inside(group, build)
        return group
    }

    /** Lays out fields created in [build] side by side with equal width. */
    fun row(build: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        container.addView(row)
        inside(row, build)
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            val params = child.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.width = 0
            params.weight = 1f
            if (i > 0) params.marginStart = dp(12)
            child.layoutParams = params
        }
        return row
    }

    fun add(view: View) = container.addView(view)

    // ---- Fields -----------------------------------------------------------

    /** Outlined text field with a floating [label]. */
    fun text(
        label: String,
        value: String = "",
        helper: String? = null,
        placeholder: String? = null,
        multiline: Boolean = false,
        capitalize: Boolean = true,
        focus: Boolean = false,
    ): TextInputEditText {
        val layout = inflater.inflate(R.layout.form_text_field, container, false) as TextInputLayout
        layout.hint = label
        helper?.let { layout.helperText = it }
        placeholder?.let { layout.placeholderText = it }
        val edit = layout.findViewById<TextInputEditText>(R.id.form_edit)
        var type = InputType.TYPE_CLASS_TEXT
        if (capitalize) type = type or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        if (multiline) {
            type = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            edit.minLines = 2
        }
        edit.inputType = type
        edit.setText(value)
        // Clear a "required" error as soon as the user types.
        edit.doAfterTextChanged { if (layout.error != null) layout.error = null }
        container.addView(layout)
        if (focus) autoFocus = edit
        return edit
    }

    /** Marks [edit] with [message] if it is blank; returns true when it has text. */
    fun requireText(edit: TextInputEditText, message: String): Boolean {
        if (!edit.text.isNullOrBlank()) return true
        (edit.parent.parent as? TextInputLayout)?.error = message
        edit.requestFocus()
        return false
    }

    /** Read-only field that opens a picker on tap, e.g. a date or a time. */
    fun picker(label: String, @DrawableRes icon: Int?, value: String, onClick: (Picker) -> Unit): Picker {
        val layout = inflater.inflate(R.layout.form_text_field, container, false) as TextInputLayout
        layout.hint = label
        icon?.let { layout.setStartIconDrawable(it) }
        val edit = layout.findViewById<TextInputEditText>(R.id.form_edit).apply {
            isFocusable = false
            isCursorVisible = false
            isLongClickable = false
            inputType = InputType.TYPE_NULL
            setText(value)
        }
        val picker = Picker(layout, edit)
        edit.setOnClickListener { onClick(picker) }
        layout.setStartIconOnClickListener { onClick(picker) }
        container.addView(layout)
        return picker
    }

    class Picker(val layout: TextInputLayout, private val edit: TextInputEditText) {
        var value: String
            get() = edit.text.toString()
            set(v) { edit.setText(v) }
        var visible: Boolean
            get() = layout.visibility == View.VISIBLE
            set(v) { layout.visibility = if (v) View.VISIBLE else View.GONE }
        var enabled: Boolean
            get() = layout.isEnabled
            set(v) { layout.isEnabled = v; edit.isEnabled = v }
        fun setError(message: String?) { layout.error = message }

        /** Replaces the tap action (field and icon). */
        fun setOnClick(action: () -> Unit) {
            edit.setOnClickListener { action() }
            layout.setStartIconOnClickListener { action() }
        }
    }

    /**
     * Choice field: looks like an outlined field with a drop-down arrow and opens a list
     * dialog. (A popup menu would open behind the sheet: Android attaches it to the
     * activity window.)
     */
    fun dropdown(
        label: String,
        items: List<String>,
        selected: Int,
        helper: String? = null,
        onSelect: ((Int) -> Unit)? = null,
    ): Dropdown {
        val layout = inflater.inflate(R.layout.form_text_field, container, false) as TextInputLayout
        layout.hint = label
        helper?.let { layout.helperText = it }
        layout.endIconMode = TextInputLayout.END_ICON_CUSTOM
        layout.setEndIconDrawable(com.google.android.material.R.drawable.mtrl_ic_arrow_drop_down)
        val edit = layout.findViewById<TextInputEditText>(R.id.form_edit).apply {
            isFocusable = false
            isCursorVisible = false
            isLongClickable = false
            inputType = InputType.TYPE_NULL
        }
        val dropdown = Dropdown(label, layout, edit, onSelect)
        dropdown.setItems(items, selected)
        container.addView(layout)
        return dropdown
    }

    class Dropdown(
        private val label: String,
        val layout: TextInputLayout,
        private val edit: TextInputEditText,
        private val onSelect: ((Int) -> Unit)?,
    ) {
        private var items: List<String> = emptyList()
        var selected: Int = 0
            private set

        init {
            val open = View.OnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(layout.context)
                    .setTitle(label)
                    .setSingleChoiceItems(items.toTypedArray(), selected) { dialog, which ->
                        dialog.dismiss()
                        select(which)
                        onSelect?.invoke(which)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            edit.setOnClickListener(open)
            layout.setEndIconOnClickListener(open)
        }

        fun setItems(items: List<String>, selected: Int) {
            this.items = items
            select(selected)
        }

        /** Selects without calling the listener. */
        fun select(index: Int) {
            selected = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            edit.setText(items.getOrNull(selected).orEmpty())
        }

        /** Changes the label of one entry, keeping the selection. */
        fun relabel(index: Int, label: String) {
            if (items.getOrNull(index) == label) return
            setItems(items.toMutableList().also { it[index] = label }, selected)
        }

        var visible: Boolean
            get() = layout.visibility == View.VISIBLE
            set(v) { layout.visibility = if (v) View.VISIBLE else View.GONE }
    }

    /**
     * Segmented buttons. Single selection (exactly one) unless [multi]; [onChange]
     * gets the selected indexes.
     */
    fun segments(
        items: List<String>,
        selected: Set<Int>,
        multi: Boolean = false,
        onChange: ((Set<Int>) -> Unit)? = null,
    ): Segments {
        val group = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = !multi
            isSelectionRequired = !multi
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        val ids = items.map { label ->
            val button = inflater.inflate(R.layout.form_segment_button, group, false) as MaterialButton
            button.id = View.generateViewId()
            button.text = label
            button.icon = null
            button.setPadding(0, button.paddingTop, 0, button.paddingBottom)
            group.addView(button)
            button.id
        }
        selected.forEach { index -> ids.getOrNull(index)?.let(group::check) }
        val segments = Segments(group, ids)
        group.addOnButtonCheckedListener { _, _, _ -> onChange?.invoke(segments.selected) }
        container.addView(group)
        return segments
    }

    class Segments(val group: MaterialButtonToggleGroup, private val ids: List<Int>) {
        val selected: Set<Int> get() = ids.indices.filter { ids[it] in group.checkedButtonIds }.toSet()
        val first: Int get() = selected.minOrNull() ?: 0
        var visible: Boolean
            get() = group.visibility == View.VISIBLE
            set(v) { group.visibility = if (v) View.VISIBLE else View.GONE }
    }

    /** Single-choice chips that wrap onto several lines; nothing may be selected. */
    fun chips(): Chips {
        val group = ChipGroup(context).apply {
            isSingleSelection = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        container.addView(group)
        return Chips(group, inflater)
    }

    class Chips(val group: ChipGroup, private val inflater: LayoutInflater) {
        private var suppress = false

        /** Replaces the chips; [onPick] is called when the user taps one. */
        fun setItems(labels: List<String>, checked: Int?, onPick: (Int) -> Unit) {
            suppress = true
            group.removeAllViews()
            labels.forEachIndexed { index, label ->
                val chip = inflater.inflate(R.layout.form_filter_chip, group, false) as Chip
                chip.id = View.generateViewId()
                chip.text = label
                chip.isChecked = index == checked
                chip.setOnCheckedChangeListener { _, isChecked -> if (isChecked && !suppress) onPick(index) }
                group.addView(chip)
            }
            suppress = false
            group.visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
        }

        /** Unchecks all chips, e.g. after the user picked a date by hand. */
        fun clear() {
            suppress = true
            group.clearCheck()
            suppress = false
        }

        fun check(index: Int?) {
            suppress = true
            group.clearCheck()
            index?.let { (group.getChildAt(it) as? Chip)?.isChecked = true }
            suppress = false
        }
    }

    fun switch(label: String, checked: Boolean, onChange: ((Boolean) -> Unit)? = null): MaterialSwitch =
        MaterialSwitch(context).apply {
            text = label
            isChecked = checked
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnCheckedChangeListener { _, isChecked -> onChange?.invoke(isChecked) }
        }.also(container::addView)

    /** A borderless text button, left aligned. */
    fun textButton(label: String, @DrawableRes icon: Int? = null, onClick: (MaterialButton) -> Unit): MaterialButton {
        val button = MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label
            icon?.let { setIconResource(it) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        button.setOnClickListener { onClick(button) }
        container.addView(button)
        return button
    }

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
