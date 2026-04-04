package eu.kanade.translation.presentation

import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.toFontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.flow.MutableStateFlow

class PagerTranslationsView : AbstractComposeView {

    private val translation: PageTranslation
    private val font: TranslationFont
    private val fontFamily: FontFamily

    companion object {
        // ثوابت مشتركة — عدّل هنا فقط ليتأثر الخلفية والنص معاً
        private const val PAD_X_FACTOR = 2.5f
        private const val PAD_Y_FACTOR = 0.5f
    }

    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : super(context, attrs, defStyleAttr) {
        this.translation = PageTranslation.EMPTY
        this.font = TranslationFont.ANIME_ACE
        this.fontFamily = Font(resId = font.res, weight = FontWeight.Bold).toFontFamily()
    }

    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        translation: PageTranslation,
        font: TranslationFont? = null,
    ) : super(context, attrs, defStyleAttr) {
        this.translation = translation
        this.font = font ?: TranslationFont.ANIME_ACE
        this.fontFamily = Font(resId = this.font.res, weight = FontWeight.Bold).toFontFamily()
    }

    // يُضبط من PagerPageHolder.updateTranslationCoords:
    //   scaleState  ← vi.scale                  (بكسل شاشة ÷ بكسل صورة)
    //   viewTLState ← vi.sourceToViewCoord(0,0)  (موقع الزاوية العلوية اليسرى للصورة بالبكسل)
    val scaleState = MutableStateFlow(1f)
    val viewTLState = MutableStateFlow(PointF())

    @Composable
    override fun Content() {
        val viewTL by viewTLState.collectAsState()
        val scale   by scaleState.collectAsState()

        // لا graphicsLayer — نفس نهج Webtoon بالضبط
        // الفرق الوحيد: نضيف viewTL كـ offset لأن الصورة قد لا تبدأ من (0,0) في Pager
        Box(modifier = Modifier.fillMaxSize()) {
            TextBlockBackground(viewTL, scale)
            TextBlockContent(viewTL, scale)
        }
    }

    @Composable
    fun TextBlockBackground(viewTL: PointF, scale: Float) {
        translation.blocks.forEach { block ->
            if (block.translation.isNullOrBlank()) return@forEach

            val padX = block.symWidth  * PAD_X_FACTOR
            val padY = block.symHeight * PAD_Y_FACTOR

            // حساب مباشر للإحداثيات على الشاشة بالبكسل
            // = موقع الصورة على الشاشة + موقع الفقاعة داخل الصورة × الـ scale
            val bgX     = viewTL.x + (block.x - padX / 2f) * scale
            val bgY     = viewTL.y + (block.y - padY / 2f) * scale
            val bgWidth  = (block.width  + padX) * scale
            val bgHeight = (block.height + padY) * scale

            val isVertical = block.angle > 85

            Box(
                modifier = Modifier
                    .offset(bgX.pxToDp(), bgY.pxToDp())
                    .requiredSize(bgWidth.pxToDp(), bgHeight.pxToDp())
                    .rotate(if (isVertical) 0f else block.angle)
                    .background(Color.White, shape = RoundedCornerShape(4.dp)),
            )
        }
    }

    @Composable
    fun TextBlockContent(viewTL: PointF, scale: Float) {
        translation.blocks.forEach { block ->
            SmartTranslationBlock(
                block = block,
                scaleFactor = scale,
                fontFamily = fontFamily,
                customPadX = block.symWidth  * PAD_X_FACTOR,
                customPadY = block.symHeight * PAD_Y_FACTOR,
                // تمرير موقع الصورة على الشاشة كـ offset
                offsetX = viewTL.x,
                offsetY = viewTL.y,
            )
        }
    }

    fun show() { isVisible = true }
    fun hide() { isVisible = false }
}
