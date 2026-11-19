package inego.zungjunggame.android

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.badlogic.gdx.backends.android.AndroidApplication
import inego.zungjunggame.platform.LineRequest
import inego.zungjunggame.platform.OverlayTextPrompt
import inego.zungjunggame.platform.postToAppThread
import inego.zungjunggame.text.TextEdit

/**
 * System one-line dialog with the IME shown and the seed selected so typing
 * replaces it. Installed as [OverlayTextPrompt.factory] from the launcher.
 */
class AndroidOverlayTextPrompt(
    private val activity: AndroidApplication,
) : OverlayTextPrompt {
    override val replacesKeyboard: Boolean = true

    private var dialog: AlertDialog? = null
    private var gen: Int = 0

    override fun request(request: LineRequest, onDone: (String?) -> Unit) {
        val captured = ++gen
        activity.runOnUiThread {
            if (captured != gen) return@runOnUiThread
            dismissUnlocked()
            fun finish(value: String?) {
                if (captured != gen) return
                gen += 1
                dismissUnlocked()
                postToAppThread { onDone(value) }
            }
            val input = EditText(activity).apply {
                setText(request.text)
                hint = request.hint
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_DONE
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PERSON_NAME or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSelectAllOnFocus(request.selectAll)
                filters = inputFilters(request)
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        finish(text?.toString())
                        true
                    } else {
                        false
                    }
                }
            }
            val shown = AlertDialog.Builder(activity)
                .setTitle(request.title)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    finish(input.text?.toString())
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    finish(null)
                }
                .setOnCancelListener { finish(null) }
                .create()
            shown.window?.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            )
            shown.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            )
            shown.setOnShowListener {
                input.requestFocus()
                if (request.selectAll) input.selectAll()
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager
                input.post {
                    @Suppress("DEPRECATION")
                    imm?.showSoftInput(input, InputMethodManager.SHOW_FORCED)
                }
            }
            dialog = shown
            shown.show()
        }
    }

    override fun dismiss() {
        gen += 1
        activity.runOnUiThread { dismissUnlocked() }
    }

    private fun dismissUnlocked() {
        dialog?.dismiss()
        dialog = null
    }

    /**
     * Length cap, then [LineRequest.acceptEdit]. The seed is already in the
     * field, so a stored name longer than the policy stays visible; the filter
     * only sees later edits. A paste that overshoots is cut at a grapheme
     * boundary to the longest prefix the policy accepts. A value already over
     * the cap can still be shortened: [TextEdit] holds its current length as
     * the ceiling instead of refusing every replacement above the cap.
     */
    private fun inputFilters(request: LineRequest): Array<InputFilter> {
        val filters = ArrayList<InputFilter>(1)
        val policy = request.acceptEdit
        if (request.maxLength in 1 until Int.MAX_VALUE || policy != null) {
            filters += InputFilter { source, start, end, dest, dstart, dend ->
                val incoming = source.subSequence(start, end).toString()
                val before = dest.toString()
                val take = TextEdit.replacement(
                    text = before,
                    start = dstart,
                    end = dend,
                    incoming = incoming,
                    maxLength = request.maxLength,
                    acceptEdit = policy ?: { _, _ -> true },
                )
                if (take == null) {
                    // Refuse the edit without erasing the selected slice.
                    dest.subSequence(dstart, dend)
                } else if (take == incoming) {
                    null
                } else {
                    take
                }
            }
        }
        return filters.toTypedArray()
    }
}
