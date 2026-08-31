package com.elderptod.android

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

private object ElderColors {
    val SURFACE = 0xFFF7F8FA.toInt()
    val CARD = 0xFFFFFFFF.toInt()
    val TEXT_PRIMARY = 0xFF172033.toInt()
    val TEXT_SECONDARY = 0xFF3A4557.toInt()
    val TEXT_MUTED = 0xFF5F6B7A.toInt()
    val PRIMARY = 0xFF1F6FEB.toInt()
    val SECONDARY = 0xFFE8EDF5.toInt()
    val DANGER = 0xFFDF3B3B.toInt()
    val ON_PRIMARY = 0xFFFFFFFF.toInt()
}

private object ElderType {
    const val TITLE = 34f
    const val SCREEN_LABEL = 24f
    const val STATUS = 18f
    const val CARD_TITLE = 30f
    const val CARD_TIME = 26f
    const val CARD_BODY = 24f
    const val MESSAGE = 30f
    const val BUTTON = 28f
    const val INPUT = 22f
}

enum class ElderActionStyle {
    PRIMARY,
    SECONDARY,
    DANGER,
}

data class ElderSwitchControl(
    val row: LinearLayout,
    val switch: Switch,
)

class ElderUi(private val context: Context) {
    fun screenRoot(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(ElderColors.SURFACE)
        }

    fun titleText(): TextView =
        centeredText(ElderType.TITLE, ElderColors.TEXT_PRIMARY)

    fun screenLabel(): TextView =
        centeredText(ElderType.SCREEN_LABEL, ElderColors.TEXT_SECONDARY).apply {
            setPadding(0, dp(14), 0, dp(24))
        }

    fun statusText(): TextView =
        centeredText(ElderType.STATUS, ElderColors.TEXT_MUTED).apply {
            setPadding(0, 0, 0, dp(20))
        }

    fun contentColumn(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

    fun input(hint: String, value: String): EditText =
        EditText(context).apply {
            this.hint = hint
            setText(value)
            textSize = ElderType.INPUT
            setSingleLine(true)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

    fun actionButton(style: ElderActionStyle): Button {
        val background = when (style) {
            ElderActionStyle.PRIMARY -> ElderColors.PRIMARY
            ElderActionStyle.SECONDARY -> ElderColors.SECONDARY
            ElderActionStyle.DANGER -> ElderColors.DANGER
        }
        val foreground = when (style) {
            ElderActionStyle.PRIMARY,
            ElderActionStyle.DANGER -> ElderColors.ON_PRIMARY
            ElderActionStyle.SECONDARY -> ElderColors.TEXT_PRIMARY
        }
        return Button(context).apply {
            minHeight = dp(72)
            textSize = ElderType.BUTTON
            setTextColor(foreground)
            setBackgroundColor(background)
            visibility = View.GONE
            isAllCaps = false
        }
    }

    fun reminderCard(reminder: ReminderState): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = rounded(ElderColors.CARD)
            addView(
                centeredText(ElderType.CARD_TITLE, ElderColors.TEXT_PRIMARY).apply {
                    text = reminder.title
                    typeface = Typeface.DEFAULT_BOLD
                },
                matchWrap(),
            )
            addView(
                centeredText(ElderType.CARD_TIME, ElderColors.PRIMARY).apply {
                    text = reminder.timeText
                    setPadding(0, dp(8), 0, dp(8))
                },
                matchWrap(),
            )
            addView(
                centeredText(ElderType.CARD_BODY, ElderColors.TEXT_SECONDARY).apply {
                    text = reminder.message
                },
                matchWrap(),
            )
        }

    fun messageCard(text: String, size: Float = ElderType.MESSAGE): TextView =
        centeredText(size, ElderColors.TEXT_PRIMARY).apply {
            this.text = text
            setPadding(dp(22), dp(24), dp(22), dp(24))
            background = rounded(ElderColors.CARD)
        }

    fun supportMessageCard(text: String): TextView =
        centeredText(ElderType.CARD_TIME, ElderColors.TEXT_SECONDARY).apply {
            this.text = text
            setPadding(dp(22), dp(24), dp(22), dp(24))
            background = rounded(ElderColors.CARD)
        }

    @Suppress("DEPRECATION")
    fun engineeringSwitchRow(
        label: String,
        contentDescription: String,
    ): ElderSwitchControl {
        lateinit var switch: Switch
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded(ElderColors.SECONDARY)
            visibility = View.GONE
            isClickable = true
            isFocusable = true

            addView(
                TextView(context).apply {
                    text = label
                    textSize = ElderType.CARD_BODY
                    setTextColor(ElderColors.TEXT_PRIMARY)
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
            switch = Switch(context).apply {
                this.contentDescription = contentDescription
                showText = false
                minWidth = dp(96)
            }
            addView(
                switch,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return ElderSwitchControl(row, switch)
    }

    fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(0, dp(8), 0, dp(8))
        }

    private fun centeredText(size: Float, color: Int): TextView =
        TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = size
            setTextColor(color)
        }

    private fun rounded(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(8).toFloat()
        }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
