package com.example.a1

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat

enum class SandboxLogLevel(val color: Int) {
    SAFE(Color.rgb(0, 200, 83)),
    WARNING(Color.rgb(255, 171, 0)),
    DYNAMIC(Color.rgb(0, 174, 255)),
    DANGER(Color.rgb(255, 82, 82))
}

enum class SandboxVerdict(
    val color: Int,
    val mark: String,
    val defaultTitle: String,
    val defaultDesc: String
) {
    SAFE(
        Color.rgb(0, 200, 83),
        "\u2713",
        "안전 페이지 확정",
        "검증이 완료되어 사용자 WebView를 로드합니다."
    ),
    WARNING(
        Color.rgb(255, 171, 0),
        "!",
        "의심 요소 발견",
        "임계값 부근의 신호가 감지되어 추가 확인이 필요합니다."
    ),
    DANGER(
        Color.rgb(255, 82, 82),
        "\u2715",
        "피싱 판정 (위험)",
        "비정상적 행위가 감지되어 접근을 차단했습니다."
    )
}

class SandboxAnalysisUi(
    private val context: Context,
    private val statusContainer: View,
    private val iconBox: FrameLayout,
    private val spinner: ProgressBar,
    private val statusIconText: TextView,
    private val statusTitle: TextView,
    private val statusDesc: TextView,
    private val targetUrlText: TextView,
    private val pipelineLogContainer: LinearLayout,
    private val logScrollView: ScrollView,
    private val detailTextView: TextView
) {
    fun start(url: String?) {
        pipelineLogContainer.removeAllViews()
        detailTextView.text = ""
        detailTextView.visibility = View.GONE
        targetUrlText.text = url.orEmpty()
        targetUrlText.visibility = if (url.isNullOrBlank()) View.GONE else View.VISIBLE

        statusContainer.background = solid(Color.WHITE)
        iconBox.background = circle(Color.TRANSPARENT, Color.rgb(242, 242, 247), dp(4))
        spinner.visibility = View.VISIBLE
        statusIconText.visibility = View.GONE
        statusTitle.text = "분석 중..."
        statusTitle.setTextColor(SandboxLogLevel.DYNAMIC.color)
        statusDesc.text = "격리된 샌드박스에서 페이지를 검사하고 있습니다."
    }

    fun log(level: SandboxLogLevel, message: String, detail: String? = null) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.rgb(242, 242, 247), dp(14))
            alpha = 0f
            translationY = dp(15).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val dot = View(context).apply {
            background = circle(level.color)
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                rightMargin = dp(16)
            }
        }

        val textGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val label = TextView(context).apply {
            text = message
            setTextColor(if (level == SandboxLogLevel.DANGER) level.color else Color.rgb(28, 28, 30))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

        val sub = TextView(context).apply {
            text = detail ?: "Realtime"
            setTextColor(Color.rgb(142, 142, 147))
            textSize = 12f
            includeFontPadding = false
            setPadding(0, dp(4), 0, 0)
        }

        textGroup.addView(label)
        textGroup.addView(sub)
        row.addView(dot)
        row.addView(textGroup)
        pipelineLogContainer.addView(row)

        row.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        if (level == SandboxLogLevel.DANGER) {
            pulse(dot)
        }

        logScrollView.post {
            logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    fun finish(
        verdict: SandboxVerdict,
        title: String = verdict.defaultTitle,
        desc: String = verdict.defaultDesc
    ) {
        spinner.visibility = View.GONE
        statusIconText.visibility = View.VISIBLE
        statusIconText.text = verdict.mark
        statusIconText.setTextColor(Color.WHITE)
        statusTitle.text = title
        statusTitle.setTextColor(verdict.color)
        statusDesc.text = desc
        iconBox.background = circle(verdict.color)
        statusContainer.background = if (verdict == SandboxVerdict.DANGER) {
            topTint(verdict.color)
        } else {
            solid(Color.WHITE)
        }
        pop(iconBox)
    }

    fun showDetails(text: String) {
        detailTextView.text = text
        detailTextView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    fun reset() {
        pipelineLogContainer.removeAllViews()
        targetUrlText.text = ""
        detailTextView.text = ""
        detailTextView.visibility = View.GONE
    }

    private fun pop(view: View) {
        view.scaleX = 0.75f
        view.scaleY = 0.75f
        val upX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1.12f)
        val upY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1.12f)
        val downX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f)
        val downY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f)
        AnimatorSet().apply {
            playTogether(upX, upY)
            duration = 140L
            interpolator = DecelerateInterpolator()
            start()
        }
        view.postDelayed({
            AnimatorSet().apply {
                playTogether(downX, downY)
                duration = 160L
                interpolator = DecelerateInterpolator()
                start()
            }
        }, 140L)
    }

    private fun pulse(view: View) {
        val x = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.8f, 1f)
        val y = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.8f, 1f)
        val a = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.45f, 1f)
        AnimatorSet().apply {
            playTogether(x, y, a)
            duration = 900L
            start()
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
        }
    }

    private fun circle(color: Int, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (strokeColor != null && strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    private fun solid(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
        }
    }

    private fun topTint(color: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(adjustAlpha(color, 0.22f), adjustAlpha(color, 0.04f), Color.WHITE)
        )
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        return Color.argb(
            (255 * factor).toInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    init {
        DrawableCompat.setTint(spinner.indeterminateDrawable, SandboxLogLevel.DYNAMIC.color)
    }
}
