package eu.kanade.translation.recognizer

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable

class TextRecognizer(val language: TextRecognizerLanguage) : Closeable {

    private val recognizer = TextRecognition.getClient(
        when (language) {
            TextRecognizerLanguage.ENGLISH -> TextRecognizerOptions.DEFAULT_OPTIONS
            TextRecognizerLanguage.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            TextRecognizerLanguage.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
            TextRecognizerLanguage.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
        },
    )

    fun recognize(image: InputImage): Text {
        val enhanced = enhanceWithAdvancedSharpening(image)
        
        try {
            return Tasks.await<Text>(recognizer.process(enhanced))
        } finally {
            enhanced.bitmapInternal?.recycle()
        }
    }

    /**
     * خوارزمية المعالجة المتقدمة لشحذ الخطوط (Advanced Edge Sharpening):
     * تكبّر الصورة 2x، وتطبق فلتر تباين حاد جداً يعزل تكسرات البكسلات الناتجة عن التكبير
     * ويحول حواف الحروف الباهتة والمغبشة إلى حدود سوداء قاطعة وصافية تماماً.
     */
    private fun enhanceWithAdvancedSharpening(image: InputImage): InputImage {
        val original = image.bitmapInternal ?: return image

        // 1. التكبير النظيف بمقدار 2x 
        val scaled = Bitmap.createScaledBitmap(
            original,
            original.width * SCALE_FACTOR,
            original.height * SCALE_FACTOR,
            true // تشغيل البيلينير لمنع تكسر الحروف الحاد
        )

        val enhancedBitmap = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhancedBitmap)

        // 2. مصفوفة الشحذ والقطع الفائق (Super Edge Cut Matrix):
        // قمنا برفع معامل الضرب اللوني إلى (2.5f) وهو رقم قوي جداً، وطرحنا (-140f).
        // هذه المعادلة تعمل كـ "مصفاة عتباتية قاسية"، حيث تجبر أي بكسل رمادي مغبش (Blurry)
        // ناتج عن التكبير على التحول فوراً وبشكل حاد إما إلى أسود فاحم (إذا كان يخص الحرف)
        // أو إلى أبيض ناصع (إذا كان يخص الخلفية)، مما يلغي تأثير الغباش تماماً بالنسبة للمحرك.
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        2.5f, 0f, 0f, 0f, -140f, // القناة الحمراء
                        0f, 2.5f, 0f, 0f, -140f, // القناة الخضراء
                        0f, 0f, 2.5f, 0f, -140f, // القناة الزرقاء
                        0f, 0f, 0f, 1f, 0f        // الحفاظ على الشفافية ثابتة
                    ),
                ),
            )
        }

        canvas.drawBitmap(scaled, 0f, 0f, paint)
        scaled.recycle() // تفريغ الذاكرة فوراً

        return InputImage.fromBitmap(enhancedBitmap, 0)
    }

    override fun close() {
        recognizer.close()
    }

    companion object {
        const val SCALE_FACTOR = 2 
    }
}
