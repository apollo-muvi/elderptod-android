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
    val SURFACE = 0xFFFAFBFC.toInt()
    val CARD = 0xFFFFFFFF.toInt()
    val TEXT_PRIMARY = 0xFF252B33.toInt()
    val TEXT_SECONDARY = 0xFF404956.toInt()
    val TEXT_MUTED = 0xFF6C7480.toInt()
    val BORDER = 0xFFE4E7EB.toInt()
    val PRIMARY = 0xFF16856F.toInt()
    val PRIMARY_SOFT = 0xFFE8F6F1.toInt()
    val DANGER = 0xFFDF3B3B.toInt()
    val DANGER_SOFT = 0xFFFCEDEC.toInt()
    val ON_PRIMARY = 0xFFFFFFFF.toInt()
}

private object ElderType {
    const val BRAND = 18f
    const val PILL = 14f
    const val TITLE = 34f
    const val HOME_TIME = 68f
    const val SCREEN_LABEL = 24f
    const val STATUS = 18f
    const val LABEL = 16f
    const val CARD_TITLE = 30f
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

enum class ElderPanelStyle {
    NORMAL,
    SOFT,
    DANGER,
}

data class ElderTopBarControl(
    val row: LinearLayout,
    val backButton: Button,
    val brandText: TextView,
    val statusText: TextView,
)

data class ElderSwitchControl(
    val row: LinearLayout,
    val switch: Switch,
)

class ElderUi(private val context: Context) {
    fun screenRoot(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(28), dp(22), dp(22))
            setBackgroundColor(ElderColors.SURFACE)
        }

    fun topBar(): ElderTopBarControl {
        val back = Button(context).apply {
            text = "‹"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(48)
            minWidth = dp(56)
            setTextColor(ElderColors.TEXT_PRIMARY)
            background = bordered(ElderColors.CARD, dp(16).toFloat())
            visibility = View.GONE
            isAllCaps = false
        }
        val brand = TextView(context).apply {
            textSize = ElderType.BRAND
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ElderColors.TEXT_PRIMARY)
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = TextView(context).apply {
            textSize = ElderType.PILL
            setTextColor(ElderColors.TEXT_MUTED)
            gravity = Gravity.CENTER
            minHeight = dp(36)
            setPadding(dp(12), 0, dp(12), 0)
            background = bordered(ElderColors.CARD, dp(999).toFloat())
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            addView(back, wrapContent())
            addView(
                brand,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    leftMargin = dp(8)
                    rightMargin = dp(8)
                },
            )
            addView(status, wrapContent())
        }
        return ElderTopBarControl(row, back, brand, status)
    }

    fun titleText(): TextView =
        centeredText(ElderType.TITLE, ElderColors.TEXT_PRIMARY)

    fun homeTimeText(): TextView =
        centeredText(ElderType.HOME_TIME, ElderColors.TEXT_PRIMARY).apply {
            typeface = Typeface.DEFAULT_BOLD
        }

    fun screenLabel(): TextView =
        centeredText(ElderType.SCREEN_LABEL, ElderColors.TEXT_SECONDARY).apply {
            setPadding(0, dp(8), 0, dp(16))
        }

    fun statusText(): TextView =
        centeredText(ElderType.STATUS, ElderColors.TEXT_MUTED).apply {
            setPadding(0, 0, 0, dp(10))
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
            background = bordered(ElderColors.CARD, dp(16).toFloat())
        }

    fun actionButton(style: ElderActionStyle): Button {
        val background = when (style) {
            ElderActionStyle.PRIMARY -> ElderColors.PRIMARY
            ElderActionStyle.SECONDARY -> ElderColors.CARD
            ElderActionStyle.DANGER -> ElderColors.DANGER
        }
        val foreground = when (style) {
            ElderActionStyle.PRIMARY,
            ElderActionStyle.DANGER -> ElderColors.ON_PRIMARY
            ElderActionStyle.SECONDARY -> ElderColors.TEXT_PRIMARY
        }
        return Button(context).apply {
            minHeight = if (style == ElderActionStyle.PRIMARY) dp(76) else dp(58)
            textSize = if (style == ElderActionStyle.PRIMARY) ElderType.BUTTON else 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(foreground)
            this.background = if (style == ElderActionStyle.SECONDARY) {
                bordered(background, dp(16).toFloat())
            } else {
                rounded(background, dp(16).toFloat())
            }
            visibility = View.GONE
            isAllCaps = false
        }
    }

    fun homeActionList(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

    fun homeActionCard(
        title: String,
        subtitle: String,
        primary: Boolean,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.START
            minimumHeight = dp(156)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = if (primary) {
                rounded(ElderColors.PRIMARY, dp(18).toFloat())
            } else {
                bordered(ElderColors.CARD)
            }
            isClickable = true
            isFocusable = true
            addView(
                TextView(context).apply {
                    text = "›"
                    textSize = 34f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.RIGHT
                    setTextColor(if (primary) ElderColors.ON_PRIMARY else ElderColors.TEXT_PRIMARY)
                },
                innerWrap(),
            )
            addView(
                TextView(context).apply {
                    text = title
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (primary) ElderColors.ON_PRIMARY else ElderColors.TEXT_PRIMARY)
                },
                innerWrap(),
            )
            addView(
                TextView(context).apply {
                    text = subtitle
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 2
                    setTextColor(if (primary) 0xD9FFFFFF.toInt() else ElderColors.TEXT_MUTED)
                    setPadding(0, dp(4), 0, 0)
                },
                innerWrap(),
            )
        }

    fun homeActionParams(first: Boolean): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            dp(156),
            1f,
        ).apply {
            if (first) {
                rightMargin = dp(6)
            } else {
                leftMargin = dp(6)
            }
        }

    fun panel(
        label: String,
        title: String,
        meta: String,
        style: ElderPanelStyle = ElderPanelStyle.NORMAL,
    ): LinearLayout {
        val background = when (style) {
            ElderPanelStyle.NORMAL -> ElderColors.CARD
            ElderPanelStyle.SOFT -> ElderColors.PRIMARY_SOFT
            ElderPanelStyle.DANGER -> ElderColors.DANGER_SOFT
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(20), dp(20), dp(20), dp(20))
            this.background = bordered(background)
            addView(labelText(label), innerWrap())
            addView(
                TextView(context).apply {
                    text = title
                    textSize = ElderType.CARD_TITLE
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ElderColors.TEXT_PRIMARY)
                },
                innerWrap(),
            )
            if (meta.isNotBlank()) {
                addView(
                    TextView(context).apply {
                        text = meta
                        textSize = ElderType.STATUS
                        setTextColor(ElderColors.TEXT_MUTED)
                        setPadding(0, dp(4), 0, 0)
                    },
                    innerWrap(),
                )
            }
        }
    }

    fun reminderCard(reminder: ReminderState): LinearLayout =
        panel(
            label = "下一個提醒",
            title = reminder.title,
            meta = "${reminder.timeText} 播放語音提醒",
            style = ElderPanelStyle.SOFT,
        )

    fun messageCard(text: String, size: Float = ElderType.MESSAGE): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ElderColors.TEXT_PRIMARY)
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }

    fun supportMessageCard(text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ElderColors.TEXT_PRIMARY)
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }

    fun audioStatusRow(text: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(
                centeredText(20f, ElderColors.PRIMARY).apply {
                    this.text = "聲"
                    typeface = Typeface.DEFAULT_BOLD
                    minWidth = dp(44)
                    minHeight = dp(44)
                    background = bordered(ElderColors.CARD, dp(14).toFloat())
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    rightMargin = dp(14)
                },
            )
            addView(
                TextView(context).apply {
                    this.text = text
                    textSize = ElderType.STATUS
                    setTextColor(ElderColors.TEXT_MUTED)
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
        }

    fun settingRow(label: String, value: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(0, dp(14), 0, dp(14))
            addView(
                TextView(context).apply {
                    text = label
                    textSize = ElderType.CARD_BODY
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ElderColors.TEXT_PRIMARY)
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
            addView(
                TextView(context).apply {
                    text = value
                    textSize = ElderType.CARD_BODY
                    setTextColor(ElderColors.TEXT_MUTED)
                },
                wrapContent(),
            )
        }

    fun footerNote(text: String): TextView =
        centeredText(15f, ElderColors.TEXT_MUTED).apply {
            this.text = text
            setPadding(0, dp(10), 0, 0)
        }

    fun applyHomeTime(view: TextView) {
        view.textSize = ElderType.HOME_TIME
        view.typeface = Typeface.DEFAULT_BOLD
        view.gravity = Gravity.CENTER
        view.setTextColor(ElderColors.TEXT_PRIMARY)
        view.visibility = View.VISIBLE
    }

    fun applyScreenTitle(view: TextView) {
        view.textSize = ElderType.TITLE
        view.typeface = Typeface.DEFAULT_BOLD
        view.gravity = Gravity.CENTER
        view.setTextColor(ElderColors.TEXT_PRIMARY)
        view.visibility = View.VISIBLE
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
            background = bordered(ElderColors.CARD)
            visibility = View.GONE
            isClickable = true
            isFocusable = true

            addView(
                TextView(context).apply {
                    text = label
                    textSize = ElderType.CARD_BODY
                    typeface = Typeface.DEFAULT_BOLD
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
            addView(switch, wrapContent())
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

    fun expandedContent(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply {
            setMargins(0, dp(10), 0, dp(10))
        }

    private fun wrapContent(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    private fun innerWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    private fun labelText(text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = ElderType.LABEL
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ElderColors.TEXT_MUTED)
        }

    private fun centeredText(size: Float, color: Int): TextView =
        TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = size
            setTextColor(color)
        }

    private fun rounded(
        color: Int,
        radius: Float = dp(8).toFloat(),
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun bordered(
        color: Int,
        radius: Float = dp(18).toFloat(),
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(dp(1), ElderColors.BORDER)
        }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
