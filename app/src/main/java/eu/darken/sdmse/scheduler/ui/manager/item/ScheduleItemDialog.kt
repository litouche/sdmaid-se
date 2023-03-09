package eu.darken.sdmse.scheduler.ui.manager.item

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.sdmse.common.uix.BottomSheetDialogFragment2
import eu.darken.sdmse.databinding.SchedulerItemDialogBinding
import java.time.Duration

@AndroidEntryPoint
class ScheduleItemDialog : BottomSheetDialogFragment2() {
    override val vm: ScheduleItemDialogVM by viewModels()
    override lateinit var ui: SchedulerItemDialogBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        ui = SchedulerItemDialogBinding.inflate(inflater, container, false)
        return ui.root
    }

    private val labelWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable) {
            s.toString().takeIf { it.isNotBlank() }?.let { vm.updateLabel(it) }
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vm.state.observe2(ui) { state ->
            nameInput.apply {
                if (text.isNullOrEmpty()) {
                    removeTextChangedListener(labelWatcher)
                    setText(state.label)
                    addTextChangedListener(labelWatcher)
                }
            }

            if (state.hour != null && state.minute != null) {
                val hourTxt = state.hour.toString().padStart(2, '0')
                val minuteTxt = state.minute.toString().padStart(2, '0')
                timeInput.setText("$hourTxt:$minuteTxt")
            } else {
                timeInput.setText("")
            }

            timeEditAction.setOnClickListener {
                val picker = MaterialTimePicker.Builder().apply {
                    setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                    setTimeFormat(TimeFormat.CLOCK_24H)
                }.build()

                picker.addOnPositiveButtonClickListener {
                    vm.updateTime(picker.hour, picker.minute)
                }

                picker.show(childFragmentManager, "asbd")
            }

            repeatDaysValue.text = Duration.ofMillis(state.repeatMillis).toDays().toString()
            repeatDaysLessAction.setOnClickListener { vm.decreasedays() }
            repeatDaysMoreAction.setOnClickListener { vm.increaseDays() }

            saveAction.apply {
                setOnClickListener { vm.saveSchedule() }
                isEnabled = state.canSave
            }

            loadingOverlay.isVisible = false
            contentContainer.isVisible = true
        }

        super.onViewCreated(view, savedInstanceState)
    }
}