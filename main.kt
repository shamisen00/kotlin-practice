package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import org.tensorflow.lite.Interpreter
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap

import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : ComponentActivity() {
    private lateinit var tfliteTester: TFLiteTester

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tfliteTester = TFLiteTester(this)
        setContent {
            TFLiteTestScreen(tfliteTester)
        }
    }
}

@Composable
fun TFLiteTestScreen(tfliteTester: TFLiteTester) {
    var resultText by remember { mutableStateOf("") }
    var imageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 画像を初期化します
    LaunchedEffect(Unit) {
        imageBitmap = tfliteTester.loadImageFromAssets("download.jpg")
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = resultText)
        imageBitmap?.let { bitmap ->
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { testInference(tfliteTester) { resultText = it } }) {
            Text("Test Inference")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { testGaussianBlur(tfliteTester, imageBitmap) { blurredImage, blurTime ->
            imageBitmap = blurredImage
            resultText = "Gaussian Blur Applied in $blurTime ms"
        }}) {
            Text("Test Gaussian Blur")
        }
    }
}

private fun testGaussianBlur(
    tfliteTester: TFLiteTester,
    bitmap: Bitmap?,
    updateResult: (Bitmap, Long) -> Unit
) {
    bitmap?.let { originalBitmap ->
        CoroutineScope(Dispatchers.IO).launch {
            val startTime = System.currentTimeMillis()
            val blurredBitmap = tfliteTester.applyGaussianBlur(originalBitmap)
            val endTime = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                updateResult(blurredBitmap, endTime - startTime)
            }
        }
    }
}

private fun testInference(
    tfliteTester: TFLiteTester,
    updateResult: (String) -> Unit
) {
    val bitmap = tfliteTester.loadImageFromAssets("download.jpg")
    if (bitmap.width != TFLiteTester.BITMAP_SIZE || bitmap.height != TFLiteTester.BITMAP_SIZE) {
        throw IllegalStateException("Bitmap size is incorrect: expected ${TFLiteTester.BITMAP_SIZE}x${TFLiteTester.BITMAP_SIZE}, " +
                "but got ${bitmap.width}x${bitmap.height}")
    }

    val imageData = convertBitmapToByteBuffer(bitmap)
    tfliteTester.testModel(imageData) { averageRunTime, probabilities ->
        // 結果を処理してupdateResultを呼び出します
        val resultString = "Average Inference Time: $averageRunTime ms\nProbabilities: ${probabilities.joinToString(", ")}"
        updateResult(resultString)
    }
}

// ビットマップをByteBufferに変換するヘルパーメソッド
private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
    val byteBuffer = ByteBuffer.allocateDirect(4 * TFLiteTester.BITMAP_SIZE * TFLiteTester.BITMAP_SIZE * 3)
    byteBuffer.order(ByteOrder.nativeOrder())
    val intValues = IntArray(TFLiteTester.BITMAP_SIZE * TFLiteTester.BITMAP_SIZE)
    bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    for (value in intValues) {
        byteBuffer.putFloat((value shr 16 and 0xFF) / 255f)
        byteBuffer.putFloat((value shr 8 and 0xFF) / 255f)
        byteBuffer.putFloat((value and 0xFF) / 255f)
    }
    return byteBuffer
}

// FloatArray の拡張関数として com.example.myapplication.indexOfMax を定義
private fun FloatArray.indexOfMax(): Int =
    withIndex().maxByOrNull { it.value }?.index ?: -1

class TFLiteTester(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val renderScript = RenderScript.create(context)

    init {
        interpreter = try {
            Interpreter(loadModelFile("mobilenet_v1_0.50_128_1_default_1.tflite"))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun applyGaussianBlur(bitmap: Bitmap): Bitmap {
        val input = Allocation.createFromBitmap(renderScript, bitmap)
        val output = Allocation.createTyped(renderScript, input.type)
        ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript)).apply {
            setRadius(10f)
            setInput(input)
            forEach(output)
            destroy() // スクリプトのリソースを解放
        }
        val blurredBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
        output.copyTo(blurredBitmap)
        input.destroy()  // 入力Allocationのリソースを解放
        output.destroy() // 出力Allocationのリソースを解放
        return blurredBitmap
    }

    fun testModel(inputData: ByteBuffer, callback: (averageRunTime: Long, probabilities: FloatArray) -> Unit) {
        // 2次元配列として確率を格納する配列を初期化します。
        val probabilities = Array(1) { FloatArray(NUM_CLASSES) }
        val averageRunTime = measureInferenceTime {
            // 2次元配列をモデルに渡すことに注意してください。
            interpreter?.run(inputData, probabilities)
        }
        // 結果をコールバックに渡す前に、必要ならば1次元配列に変換します。
        callback(averageRunTime, probabilities[0])
    }

    private inline fun measureInferenceTime(block: () -> Unit): Long {
        val startTime = System.nanoTime()
        block()
        return (System.nanoTime() - startTime) / 1_000_000
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        context.assets.openFd(modelName).use { fileDescriptor ->
            FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                val startOffset = fileDescriptor.startOffset
                val declaredLength = fileDescriptor.declaredLength
                return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            }
        }
    }

    fun loadImageFromAssets(filename: String): Bitmap {
        val options = BitmapFactory.Options().apply {
            inScaled = true
            inJustDecodeBounds = true
        }

        context.assets.open(filename).use { BitmapFactory.decodeStream(it, null, options) }

        val scaleFactor = Math.max(1, Math.min(options.outWidth / BITMAP_SIZE, options.outHeight / BITMAP_SIZE))

        options.inJustDecodeBounds = false
        options.inSampleSize = scaleFactor
        options.inPreferredConfig = Bitmap.Config.ARGB_8888

        // 画像を再度デコードしますが、今回はスケールダウンしてリサイズを行います
        return context.assets.open(filename).use {
            BitmapFactory.decodeStream(it, null, options)?.let { scaledBitmap ->
                // ここで画像サイズを128x128にリサイズします
                Bitmap.createScaledBitmap(scaledBitmap, BITMAP_SIZE, BITMAP_SIZE, true)
            } ?: throw IllegalArgumentException("Cannot decode bitmap from $filename")
        }
    }


    companion object {
        const val NUM_CLASSES = 1001
        const val BITMAP_SIZE = 128  // 128x128の画像サイズに設定
    }
}
