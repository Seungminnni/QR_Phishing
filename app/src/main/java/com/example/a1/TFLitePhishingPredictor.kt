package com.example.a1

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Loads and runs the TFLite phishing classifier.
 *
 * The model input width is read from the TFLite tensor at runtime so the app
 * fails clearly when feature_info.json and the bundled model drift apart.
 */
class TFLitePhishingPredictor(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private var expectedInputSize = -1
    private var outputSize = 1

    companion object {
        private const val TAG = "TFLitePhishingPredictor"
        private const val MODEL_FILE = "phishing_classifier.tflite"
    }

    init {
        try {
            loadModel()
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model", e)
            isInitialized = false
        }
    }

    private fun loadModel() {
        val modelBuffer = loadModelFile()
        val loadedInterpreter = Interpreter(modelBuffer)
        interpreter = loadedInterpreter
        readModelShape(loadedInterpreter)
        logModelInfo(loadedInterpreter)
    }

    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream = assetFileDescriptor.createInputStream()
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.declaredLength
        )
    }

    private fun readModelShape(model: Interpreter) {
        val inputShape = model.getInputTensor(0).shape()
        val outputShape = model.getOutputTensor(0).shape()

        expectedInputSize = inputShape.lastOrNull { it > 0 } ?: -1
        outputSize = outputShape.lastOrNull { it > 0 } ?: 1
    }

    private fun logModelInfo(model: Interpreter) {
        runCatching {
            val inputTensor = model.getInputTensor(0)
            val outputTensor = model.getOutputTensor(0)

            Log.d(TAG, "Model loaded")
            Log.d(TAG, "  input shape: ${inputTensor.shape().contentToString()}")
            Log.d(TAG, "  input type: ${inputTensor.dataType()}")
            Log.d(TAG, "  expected feature count: $expectedInputSize")
            Log.d(TAG, "  output shape: ${outputTensor.shape().contentToString()}")
            Log.d(TAG, "  output type: ${outputTensor.dataType()}")
        }.onFailure {
            Log.w(TAG, "Failed to log model tensor info", it)
        }
    }

    /**
     * @param features preprocessed feature vector in the exact order from feature_info.json
     * @return phishing probability (0.0..1.0), or -1.0 when inference cannot run
     */
    fun predictWithTFLite(features: FloatArray): Float {
        val model = interpreter
        if (!isInitialized || model == null) {
            Log.w(TAG, "TFLite model is not initialized")
            return -1.0f
        }

        if (expectedInputSize > 0 && features.size != expectedInputSize) {
            Log.e(
                TAG,
                "Feature count mismatch: model expects $expectedInputSize, app produced ${features.size}"
            )
            return -1.0f
        }

        return try {
            val input = arrayOf(features)
            val output = Array(1) { FloatArray(outputSize) }

            model.run(input, output)

            val prediction = output[0][0]
            Log.d(TAG, "TFLite prediction: $prediction")
            prediction.coerceIn(0.0f, 1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference failed", e)
            -1.0f
        }
    }

    fun isModelReady(): Boolean {
        return isInitialized && interpreter != null && expectedInputSize > 0
    }

    fun getExpectedInputSize(): Int {
        return expectedInputSize
    }

    fun close() {
        runCatching {
            interpreter?.close()
            interpreter = null
            isInitialized = false
        }.onFailure {
            Log.e(TAG, "Failed to close TFLite interpreter", it)
        }
    }
}
