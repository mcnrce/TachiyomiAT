package eu.kanade.translation.presentation

import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.toFontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.flow.MutableStateFlow

class PagerTranslationsView : AbstractComposeView {

    private val translation: PageTranslation
    private val font: TranslationFont
    private val fontFamily: FontFamily

    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : super(context, attrs, defStyleAttr) {
        this.translation = PageTranslation.EMPTY
        this.font = TranslationFont.ANIME_ACE
        this.fontFamily = Font(
            resId = font.res,
            weight = FontWeight.Bold,
        ).toFontFamily()
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
        this.fontFamily = Font(
            resId = this.font.res,
            weight = FontWeight.Bold,
        ).toFontFamily()
    }

    // يمكن ضبطها من الخارج للتكبير والتحريك
    val scaleState = MutableStateFlow(1f)
    val viewTLState = MutableStateFlow(PointF())

    @Composable
    override fun Content() {
        val viewTL by viewTLState.collectAsState()
        val zoomScale by scaleState.collectAsState()

        // 1. تتبع حجم الحاوية
        var containerSize by remember { mutableStateOf(IntSize.Zero) }

        // 2. حساب عامل القياس الأساسي بناءً على حجم الحاوية وعرض الصورة الأصلي
        val baseScaleFactor = if (containerSize.width > 0 && translation.imgWidth > 0) {
            containerSize.width.toFloat() / translation.imgWidth
        } else {
            1f
        }

        // 3. عامل القياس النهائي = الأساسي × التكبير الخارجي (إن وُجد)
        val finalScale = baseScaleFactor * zoomScale

        Box(
            modifier = Modifier
                .fillMaxSize()                // نملأ المساحة المتاحة لاستقبال onSizeChanged
                .onSizeChanged { size ->
                    containerSize = size
                    // يمكن هنا إخفاء أو إظهار العناصر حسب الحجم (اختياري)
                    if (size == IntSize.Zero) hide() else show()
                }
                // تطبيق الإزاحة العامة (التحريك)
                .offset(viewTL.x.pxToDp(), viewTL.y.pxToDp())
        ) {
            // تمرير عامل القياس النهائي للخلفيات والنصوص
            TextBlockBackground(finalScale)
            TextBlockContent(finalScale)
        }
    }

    @Composable
    fun TextBlockBackground(scale: Float) {
        translation.blocks.forEach { block ->
            val padX = block.symWidth / 2
            val padY = block.symHeight / 2

            // الإحداثيات المحسوبة بنفس منطق WebtoonTranslationsView
            val bgX = (block.x - padX / 2) * scale
            val bgY = (block.y - padY / 2) * scale
            val bgWidth = (block.width + padX) * scale
            val bgHeight = (block.height + padY) * scale
            val isVertical = block.angle > 85

            Box(
                modifier = Modifier
                    .offset(bgX.pxToDp(), bgY.pxToDp())
                    .size(bgWidth.pxToDp(), bgHeight.pxToDp())
                    .rotate(if (isVertical) 0f else block.angle)
                    .background(Color.White, shape = RoundedCornerShape(4.dp))
            )
        }
    }

    @Composable
    fun TextBlockContent(scale: Float) {
        translation.blocks.forEach { block ->
            SmartTranslationBlock(
                block = block,
                scaleFactor = scale,
                fontFamily = fontFamily,
            )
        }
    }

    fun show() {
        isVisible = true
    }

    fun hide() {
        isVisible = false
    }
}
