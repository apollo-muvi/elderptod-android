package com.elderptod.android

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

private object ElderColors {
    val SURFACE = 0xFFF3F6F7.toInt()
    val CARD = 0xFFFFFFFF.toInt()
    val FIELD = 0xFFFFFFFF.toInt()
    val TEXT_PRIMARY = 0xFF252B33.toInt()
    val TEXT_SECONDARY = 0xFF404956.toInt()
    val TEXT_MUTED = 0xFF6C7480.toInt()
    val BORDER = 0xFFD8DEE3.toInt()
    val PRIMARY = 0xFF16856F.toInt()
    val PRIMARY_SOFT = 0xFFE8F6F1.toInt()
    val DANGER = 0xFFDF3B3B.toInt()
    val DANGER_SOFT = 0xFFFCEDEC.toInt()
    val ON_PRIMARY = 0xFFFFFFFF.toInt()
}

private object ElderType {
    const val BRAND = 20f
    const val PILL = 16f
    const val TITLE = 38f
    const val HOME_TIME = 68f
    const val SCREEN_LABEL = 26f
    const val STATUS = 22f
    const val LABEL = 18f
    const val CARD_TITLE = 30f
    const val CARD_BODY = 28f
    const val MESSAGE = 32f
    const val BUTTON = 30f
    const val INPUT = 24f
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

enum class ElderStatusStyle {
    NORMAL,
    OK,
    WARNING,
}

enum class ElderFontSizeMode(
    val storageValue: String,
    val label: String,
    val scale: Float,
) {
    STANDARD("standard", "標準", 1f),
    LARGE("large", "大", 1.14f),
    EXTRA_LARGE("extra_large", "特大", 1.28f);

    companion object {
        fun fromStorage(value: String?): ElderFontSizeMode =
            entries.firstOrNull { it.storageValue == value } ?: STANDARD
    }
}

data class ElderTopBarControl(
    val row: LinearLayout,
    val backButton: ImageButton,
    val brandText: TextView,
    val statusText: TextView,
)

data class ElderSwitchControl(
    val row: LinearLayout,
    val switch: Switch,
)

data class ElderCallActionControl(
    val row: LinearLayout,
    val declineButton: ImageButton,
    val acceptButton: ImageButton,
)

data class ElderFontSizeControl(
    val row: LinearLayout,
    val standardButton: Button,
    val largeButton: Button,
    val extraLargeButton: Button,
)

class ElderUi(
    private val context: Context,
    private val fontScale: Float = 1f,
) {
    fun screenRoot(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(20), dp(18), dp(16))
            setBackgroundColor(ElderColors.SURFACE)
        }

    fun topBar(): ElderTopBarControl {
        val back = ImageButton(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            scaleType = ImageView.ScaleType.CENTER
            minimumWidth = dp(56)
            minimumHeight = dp(56)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(ElderColors.CARD, dp(999).toFloat())
            visibility = View.GONE
            contentDescription = "返回"
        }
        val brand = TextView(context).apply {
            textSize = sp(ElderType.BRAND, 25f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ElderColors.TEXT_PRIMARY)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
        }
        val status = TextView(context).apply {
            textSize = sp(ElderType.PILL, 22f)
            gravity = Gravity.CENTER
            maxLines = 1
            minHeight = dp(42)
            setPadding(dp(14), 0, dp(14), 0)
            applyStatusPill(this, ElderStatusStyle.NORMAL)
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
            setPadding(0, dp(6), 0, dp(14))
        }

    fun statusText(): TextView =
        centeredText(ElderType.STATUS, ElderColors.TEXT_SECONDARY).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(ElderColors.CARD, dp(14).toFloat())
        }

    fun contentScroll(child: LinearLayout): ScrollView =
        ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(child, scrollContent())
        }

    fun contentColumn(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

    fun input(hint: String, value: String): EditText =
        EditText(context).apply {
            this.hint = hint
            setText(value)
            textSize = sp(ElderType.INPUT)
            setSingleLine(true)
            minHeight = dp(72)
            setPadding(dp(18), 0, dp(18), 0)
            setTextColor(ElderColors.TEXT_PRIMARY)
            setHintTextColor(ElderColors.TEXT_MUTED)
            background = bordered(ElderColors.FIELD, dp(12).toFloat())
            setSelectAllOnFocus(true)
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
            minHeight = if (style == ElderActionStyle.PRIMARY) dp(82) else dp(70)
            textSize = if (style == ElderActionStyle.PRIMARY) sp(ElderType.BUTTON) else sp(26f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(foreground)
            this.background = if (style == ElderActionStyle.SECONDARY) {
                bordered(background, dp(14).toFloat())
            } else {
                rounded(background, dp(14).toFloat())
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
    ): LinearLayout {
        val titleView = TextView(context).apply {
            text = title
            textSize = sp(26f, 28f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            includeFontPadding = false
            setTextColor(if (primary) ElderColors.ON_PRIMARY else ElderColors.TEXT_PRIMARY)
            setAutoSizeTextTypeUniformWithConfiguration(
                spInt(18),
                spInt(28),
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
        }
        val subtitleView = TextView(context).apply {
            text = subtitle
            textSize = sp(19f, 20f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            includeFontPadding = false
            setTextColor(if (primary) 0xD9FFFFFF.toInt() else ElderColors.TEXT_MUTED)
            setPadding(0, dp(3), 0, 0)
            setAutoSizeTextTypeUniformWithConfiguration(
                spInt(14),
                spInt(20),
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minimumHeight = dp(126)
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = if (primary) {
                rounded(ElderColors.PRIMARY, dp(16).toFloat())
            } else {
                bordered(ElderColors.CARD, dp(16).toFloat())
            }
            isClickable = true
            isFocusable = true
            addView(titleView, innerWrap())
            addView(subtitleView, innerWrap())
            post {
                val narrow = width < dp(148)
                val horizontalPadding = if (narrow) dp(10) else dp(14)
                setPadding(horizontalPadding, dp(10), horizontalPadding, dp(12))
                val textWidth = (width - paddingLeft - paddingRight).coerceAtLeast(dp(72))
                titleView.maxWidth = textWidth
                subtitleView.maxWidth = textWidth
                if (narrow) {
                    titleView.setAutoSizeTextTypeUniformWithConfiguration(
                        spInt(16),
                        spInt(23),
                        1,
                        TypedValue.COMPLEX_UNIT_SP,
                    )
                    subtitleView.setAutoSizeTextTypeUniformWithConfiguration(
                        spInt(12),
                        spInt(16),
                        1,
                        TypedValue.COMPLEX_UNIT_SP,
                    )
                }
            }
        }
    }

    fun homeActionParams(first: Boolean): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            dp(128),
            1f,
        ).apply {
            if (first) {
                rightMargin = dp(4)
            } else {
                leftMargin = dp(4)
            }
        }

    fun formSection(
        title: String,
        detail: String,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(2), dp(8), dp(2), dp(2))
            addView(
                TextView(context).apply {
                    text = title
                    textSize = sp(28f)
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    setTextColor(ElderColors.TEXT_PRIMARY)
                    setAutoSizeTextTypeUniformWithConfiguration(
                        spInt(20),
                        spInt(28),
                        1,
                        TypedValue.COMPLEX_UNIT_SP,
                    )
                },
                innerWrap(),
            )
            addView(
                TextView(context).apply {
                    text = detail
                    textSize = sp(20f)
                    maxLines = 4
                    setTextColor(ElderColors.TEXT_MUTED)
                    setPadding(0, dp(8), 0, 0)
                    setAutoSizeTextTypeUniformWithConfiguration(
                        spInt(16),
                        spInt(20),
                        1,
                        TypedValue.COMPLEX_UNIT_SP,
                    )
                },
                innerWrap(),
            )
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
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(118)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            this.background = bordered(background, dp(16).toFloat())
            addView(
                labelBadge(label, style),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(8)
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.START
                    addView(
                        TextView(context).apply {
                            text = title
                            textSize = sp(ElderType.CARD_TITLE, 34f)
                            typeface = Typeface.DEFAULT_BOLD
                            maxLines = 2
                            setTextColor(ElderColors.TEXT_PRIMARY)
                        },
                        innerWrap(),
                    )
                    if (meta.isNotBlank()) {
                        addView(
                            TextView(context).apply {
                                text = meta
                                textSize = sp(ElderType.STATUS, 26f)
                                maxLines = 2
                                setTextColor(ElderColors.TEXT_MUTED)
                                setPadding(0, dp(4), 0, 0)
                            },
                            innerWrap(),
                        )
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    fun reminderCard(reminder: ReminderState?): LinearLayout =
        panel(
            label = "下一個提醒",
            title = reminder?.title ?: "無",
            meta = reminder?.let { "${it.timeText} 播放語音提醒" }.orEmpty(),
            style = ElderPanelStyle.SOFT,
        )

    fun stateScreen(
        symbol: String,
        title: String,
        detail: String,
        warning: Boolean = false,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(18), dp(8), dp(12))
            addView(
                centeredText(52f, ElderColors.ON_PRIMARY).apply {
                    text = symbol
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    background = rounded(
                        if (warning) ElderColors.DANGER else ElderColors.PRIMARY,
                        dp(999).toFloat(),
                    )
                },
                LinearLayout.LayoutParams(dp(118), dp(118)).apply {
                    bottomMargin = dp(22)
                },
            )
            addView(
                TextView(context).apply {
                    text = title
                    textSize = sp(36f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 3
                    includeFontPadding = false
                    setTextColor(ElderColors.TEXT_PRIMARY)
                    setAutoSizeTextTypeUniformWithConfiguration(
                        spInt(24),
                        spInt(36),
                        1,
                        TypedValue.COMPLEX_UNIT_SP,
                    )
                },
                innerWrap(),
            )
            addView(
                TextView(context).apply {
                    text = detail
                    textSize = sp(24f)
                    gravity = Gravity.CENTER
                    maxLines = 5
                    setTextColor(ElderColors.TEXT_SECONDARY)
                    setPadding(dp(8), dp(14), dp(8), 0)
                    setAutoSizeTextTypeUniformWithConfiguration(
                        spInt(18),
                        spInt(24),
                        1,
                        TypedValue.COMPLEX_UNIT_SP,
                    )
                },
                innerWrap(),
            )
        }

    fun reminderScreen(reminder: ReminderState): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(14), dp(8), dp(8))
            addView(
                centeredText(48f, ElderColors.PRIMARY).apply {
                    text = "鈴"
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    background = rounded(ElderColors.PRIMARY_SOFT, dp(999).toFloat())
                },
                LinearLayout.LayoutParams(dp(104), dp(104)).apply {
                    bottomMargin = dp(18)
                },
            )
            addView(
                TextView(context).apply {
                    text = reminder.title
                    textSize = sp(34f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 3
                    includeFontPadding = false
                    setTextColor(ElderColors.TEXT_PRIMARY)
                    setAutoSizeTextTypeUniformWithConfiguration(
                        spInt(22),
                        spInt(34),
                        1,
                        TypedValue.COMPLEX_UNIT_SP,
                    )
                },
                innerWrap(),
            )
            addView(
                TextView(context).apply {
                    text = reminder.message.ifBlank { "正在播放錄音提醒" }
                    textSize = sp(ElderType.MESSAGE)
                    gravity = Gravity.CENTER
                    maxLines = 6
                    setTextColor(ElderColors.TEXT_PRIMARY)
                    setPadding(dp(4), dp(18), dp(4), 0)
                    setAutoSizeTextTypeUniformWithConfiguration(
                        spInt(22),
                        spInt(ElderType.MESSAGE.toInt()),
                        1,
                        TypedValue.COMPLEX_UNIT_SP,
                    )
                },
                innerWrap(),
            )
        }

    fun callDurationText(value: String): TextView =
        centeredText(42f, ElderColors.TEXT_PRIMARY).apply {
            text = value.ifBlank { "00:00" }
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(0, dp(6), 0, dp(8))
        }

    fun callScreen(
        callerName: String,
        state: String,
        detail: String,
        durationText: TextView? = null,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(12), dp(10), dp(10))
            addView(
                centeredText(54f, ElderColors.ON_PRIMARY).apply {
                    text = callerName.take(1).ifBlank { "家" }
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    background = rounded(ElderColors.PRIMARY, dp(999).toFloat())
                },
                LinearLayout.LayoutParams(dp(124), dp(124)).apply {
                    bottomMargin = dp(18)
                },
            )
            addView(
                TextView(context).apply {
                    text = callerName
                    textSize = sp(36f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 2
                    includeFontPadding = false
                    setTextColor(ElderColors.TEXT_PRIMARY)
                    setAutoSizeTextTypeUniformWithConfiguration(
                        spInt(24),
                        spInt(36),
                        1,
                        TypedValue.COMPLEX_UNIT_SP,
                    )
                },
                innerWrap(),
            )
            addView(
                TextView(context).apply {
                    text = state
                    textSize = sp(28f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(ElderColors.PRIMARY)
                    setPadding(0, dp(10), 0, 0)
                },
                innerWrap(),
            )
            durationText?.let {
                addView(it, innerWrap())
            }
            addView(
                TextView(context).apply {
                    text = detail
                    textSize = sp(24f)
                    gravity = Gravity.CENTER
                    maxLines = 3
                    setTextColor(ElderColors.TEXT_MUTED)
                    setPadding(dp(8), dp(10), dp(8), 0)
                    setAutoSizeTextTypeUniformWithConfiguration(
                        spInt(18),
                        spInt(24),
                        1,
                        TypedValue.COMPLEX_UNIT_SP,
                    )
                },
                innerWrap(),
            )
        }

    fun incomingCallActions(): ElderCallActionControl {
        val decline = callCircleButton(
            iconRes = R.drawable.ic_call_end,
            color = ElderColors.DANGER,
            contentDescription = "拒絕來電",
        )
        val accept = callCircleButton(
            iconRes = R.drawable.ic_call,
            color = ElderColors.PRIMARY,
            contentDescription = "接聽來電",
        )
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(8))
            addView(
                callActionColumn(decline, "拒絕"),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(14)
                },
            )
            addView(
                callActionColumn(accept, "接聽"),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(14)
                },
            )
        }
        return ElderCallActionControl(row, decline, accept)
    }

    fun fontSizeSelector(selected: ElderFontSizeMode): ElderFontSizeControl {
        val standard = fontSizeButton(ElderFontSizeMode.STANDARD, selected)
        val large = fontSizeButton(ElderFontSizeMode.LARGE, selected)
        val extraLarge = fontSizeButton(ElderFontSizeMode.EXTRA_LARGE, selected)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(74)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(ElderColors.PRIMARY, dp(18).toFloat())
            addView(
                TextView(context).apply {
                    text = "A"
                    textSize = sp(18f, 22f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(ElderColors.ON_PRIMARY)
                },
                LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(standard, fontSizeButtonParams())
            addView(large, fontSizeButtonParams())
            addView(extraLarge, fontSizeButtonParams())
            addView(
                TextView(context).apply {
                    text = "A"
                    textSize = sp(26f, 30f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(ElderColors.ON_PRIMARY)
                },
                LinearLayout.LayoutParams(dp(38), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        return ElderFontSizeControl(row, standard, large, extraLarge)
    }

    fun audioStatusRow(text: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(80)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(ElderColors.CARD, dp(16).toFloat())
            addView(
                centeredText(20f, ElderColors.PRIMARY).apply {
                    this.text = "聲"
                    typeface = Typeface.DEFAULT_BOLD
                    minWidth = dp(44)
                    minHeight = dp(44)
                    background = rounded(ElderColors.PRIMARY_SOFT, dp(999).toFloat())
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
                    textSize = sp(22f)
                    setTextColor(ElderColors.TEXT_MUTED)
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
        }

    fun applyStatusPill(
        view: TextView,
        style: ElderStatusStyle,
    ) {
        when (style) {
            ElderStatusStyle.NORMAL -> {
                view.typeface = Typeface.DEFAULT
                view.setTextColor(ElderColors.TEXT_MUTED)
                view.background = bordered(ElderColors.CARD, dp(999).toFloat())
            }
            ElderStatusStyle.OK -> {
                view.typeface = Typeface.DEFAULT_BOLD
                view.setTextColor(ElderColors.PRIMARY)
                view.background = rounded(ElderColors.PRIMARY_SOFT, dp(999).toFloat())
            }
            ElderStatusStyle.WARNING -> {
                view.typeface = Typeface.DEFAULT_BOLD
                view.setTextColor(ElderColors.DANGER)
                view.background = rounded(ElderColors.DANGER_SOFT, dp(999).toFloat())
            }
        }
    }

    fun applyHomeTime(view: TextView) {
        view.textSize = sp(ElderType.HOME_TIME)
        view.typeface = Typeface.DEFAULT_BOLD
        view.gravity = Gravity.CENTER
        view.setTextColor(ElderColors.TEXT_PRIMARY)
        view.visibility = View.VISIBLE
    }

    fun applyHomeDate(view: TextView) {
        view.textSize = sp(ElderType.SCREEN_LABEL)
        view.typeface = Typeface.DEFAULT
        view.gravity = Gravity.CENTER
        view.setTextColor(ElderColors.TEXT_SECONDARY)
        view.setPadding(0, dp(4), 0, dp(4))
        view.visibility = View.VISIBLE
    }

    fun applyScreenTitle(view: TextView) {
        view.textSize = sp(ElderType.TITLE)
        view.typeface = Typeface.DEFAULT_BOLD
        view.gravity = Gravity.CENTER
        view.setTextColor(ElderColors.TEXT_PRIMARY)
        view.visibility = View.VISIBLE
    }

    fun applyScreenLabel(view: TextView) {
        view.textSize = sp(ElderType.SCREEN_LABEL)
        view.typeface = Typeface.DEFAULT
        view.gravity = Gravity.CENTER
        view.setTextColor(ElderColors.TEXT_SECONDARY)
        view.setPadding(0, dp(8), 0, dp(16))
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
            minimumHeight = dp(84)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = bordered(ElderColors.CARD, dp(16).toFloat())
            visibility = View.GONE
            isClickable = true
            isFocusable = true

            addView(
                TextView(context).apply {
                    text = label
                    textSize = sp(28f)
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
            setMargins(0, dp(8), 0, dp(8))
        }

    fun homeContent(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply {
            setMargins(0, dp(2), 0, dp(2))
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

    private fun scrollContent(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )

    private fun labelBadge(text: String, style: ElderPanelStyle): TextView =
        centeredText(ElderType.LABEL, ElderColors.TEXT_PRIMARY).apply {
            this.text = text
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            setPadding(dp(6), 0, dp(6), 0)
            background = rounded(
                when (style) {
                    ElderPanelStyle.NORMAL -> ElderColors.SURFACE
                    ElderPanelStyle.SOFT -> ElderColors.CARD
                    ElderPanelStyle.DANGER -> ElderColors.DANGER_SOFT
                },
                dp(14).toFloat(),
            )
        }

    private fun centeredText(size: Float, color: Int): TextView =
        TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = sp(size)
            setTextColor(color)
        }

    private fun fontSizeButton(
        mode: ElderFontSizeMode,
        selected: ElderFontSizeMode,
    ): Button =
        Button(context).apply {
            text = mode.label
            textSize = sp(
                when (mode) {
                    ElderFontSizeMode.STANDARD -> 19f
                    ElderFontSizeMode.LARGE -> 22f
                    ElderFontSizeMode.EXTRA_LARGE -> 22f
                },
                24f,
            )
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            minHeight = dp(52)
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(if (mode == selected) ElderColors.PRIMARY else ElderColors.ON_PRIMARY)
            background = if (mode == selected) {
                rounded(ElderColors.ON_PRIMARY, dp(999).toFloat())
            } else {
                borderedTransparent(ElderColors.PRIMARY, 0x99FFFFFF.toInt(), dp(999).toFloat())
            }
            isAllCaps = false
        }

    private fun fontSizeButtonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        }

    private fun callActionColumn(
        button: ImageButton,
        label: String,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(button, LinearLayout.LayoutParams(dp(104), dp(104)))
            addView(
                centeredText(28f, ElderColors.TEXT_PRIMARY).apply {
                    text = label
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    setPadding(0, dp(12), 0, 0)
                },
                wrapContent(),
            )
        }

    private fun callCircleButton(
        iconRes: Int,
        color: Int,
        contentDescription: String,
    ): ImageButton =
        ImageButton(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER
            minimumWidth = dp(104)
            minimumHeight = dp(104)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = rounded(color, dp(999).toFloat())
            isFocusable = true
            this.contentDescription = contentDescription
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

    private fun borderedTransparent(
        color: Int,
        strokeColor: Int,
        radius: Float = dp(18).toFloat(),
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(dp(1), strokeColor)
        }

    private fun sp(value: Float): Float = value * fontScale

    private fun sp(
        value: Float,
        max: Float,
    ): Float = (value * fontScale).coerceAtMost(max)

    private fun spInt(value: Int): Int = (value * fontScale).toInt().coerceAtLeast(1)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
