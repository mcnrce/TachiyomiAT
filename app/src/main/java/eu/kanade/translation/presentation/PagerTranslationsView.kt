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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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

    val scaleState = MutableStateFlow(1f)
    val viewTLState = MutableStateFlow(PointF())

    @Composable
    override fun Content() {
        val viewTL by viewTLState.collectAsState()
        val scale by scaleState.collectAsState()

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .offset(viewTL.x.pxToDp(), viewTL.y.pxToDp())
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        // تثبيت الارتكاز في الزاوية لضمان تطابق الإحداثيات مع الصورة الأصلية
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
            ) {
                // نستخدم 1f لأن التكبير يتم عبر الحاوية الأب (graphicsLayer)
                TextBlockBackground(1f)
                TextBlockContent(1f)
            }
        }
    }

    @Composable
    fun TextBlockBackground(zoomScale: Float) {
        translation.blocks.forEach { block ->
            if (block.translation.isNullOrBlank()) return@forEach
            
            val padX = block.symWidth / 2
            val padY = block.symHeight / 2
            
            // التصحيح: قمنا بزيادة معامل الطرح من 1.2f إلى 2.0f لسحب الفقاعة بقوة أكبر لليسار
            // لأنك ذكرت أن التصحيح السابق كان صغيراً جداً.
            val bgX = (block.x - (padX * 5.0f)) * zoomScale
            val bgY = (block.y - (padY * 0.5f)) * zoomScale
            
            // زيادة العرض (padX * 3.0f) لتعويض السحب ومنع خروج النص من اليمين
            val bgWidth = (block.width + (padX * 3.0f)) * zoomScale
            val bgHeight = (block.height + padY) * zoomScale
            
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
    fun TextBlockContent(zoomScale: Float) {
        translation.blocks.forEach { block ->
            // قمنا بمزامنة الحشوة مع الخلفية لضمان بقاء النص في المنتصف
            SmartTranslationBlock(
                block = block,
                scaleFactor = zoomScale,
                fontFamily = fontFamily,
                customPadX = block.symWidth * 2.5f, 
                customPadY = block.symHeight / 2
            )
        }
    }

    fun show() { isVisible = true }
    fun hide() { isVisible = false }
}
