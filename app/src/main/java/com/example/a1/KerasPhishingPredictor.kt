package com.example.a1

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * KerasPhishingPredictor loads a Keras model using Chaquopy
 * and performs inference on preprocessed features.
 */
class KerasPhishingPredictor(private val context: Context) {

    private var model: Any? = null
    private var python: Python? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "KerasPhishingPredictor"
        private const val MODEL_FILE = "classifier_model.keras"
    }

    init {
        try {
            initializePython()
            loadModel()
        } catch (e: Exception) {
            Log.e(TAG, "Keras 모델 초기화 실패", e)
            // Don't throw - let the app continue with TFLite as fallback
        }
    }

    /**
     * Chaquopy Python 초기화
     */
    private fun initializePython() {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            python = Python.getInstance()
            Log.d(TAG, "Python 초기화 성공")
        } catch (e: Exception) {
            Log.e(TAG, "Python 초기화 실패", e)
            throw e
        }
    }

    /**
     * assets에서 Keras 모델 로드
     */
    private fun loadModel() {
        try {
            if (python == null) {
                Log.w(TAG, "Python이 초기화되지 않음")
                return
            }

            // assets에서 모델 파일을 임시 위치로 복사
            val modelFile = File(context.filesDir, MODEL_FILE)
            if (!modelFile.exists()) {
                val assetInputStream = context.assets.open(MODEL_FILE)
                modelFile.outputStream().use { output ->
                    assetInputStream.copyTo(output)
                }
                Log.d(TAG, "모델 파일을 ${modelFile.absolutePath}로 복사")
            }

            // Python으로 모델 로드
            val pyModule = python!!.getModule("__main__")
            pyModule.callAttr("set_model_path", modelFile.absolutePath)

            // Keras 모델 로드 코드 실행
            python!!.eval("""
import os
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '3'
import tensorflow as tf
from tensorflow import keras

loaded_model = None
model_path = None

def set_model_path(path):
    global model_path
    model_path = path

def load_keras_model():
    global loaded_model
    if loaded_model is None:
        loaded_model = keras.models.load_model(model_path)
    return loaded_model

def predict(features_array):
    model = load_keras_model()
    import numpy as np
    arr = np.array(features_array, dtype=np.float32).reshape(1, -1)
    result = model.predict(arr, verbose=0)
    return float(result[0][0])
""".trimIndent())

            // 모델 로드
            python!!.getModule("__main__").callAttr("load_keras_model")
            isInitialized = true
            Log.d(TAG, "Keras 모델 로드 성공")

        } catch (e: Exception) {
            Log.e(TAG, "Keras 모델 로드 실패", e)
            isInitialized = false
        }
    }

    /**
     * Keras 모델로 피싱 예측 수행
     */
    fun predictWithKeras(preprocessedFeatures: FloatArray): Float {
        if (!isInitialized || python == null) {
            Log.w(TAG, "Keras 모델이 초기화되지 않아 -1.0f 반환")
            return -1.0f
        }

        return try {
            val pyModule = python!!.getModule("__main__")
            val result = pyModule.callAttr("predict", preprocessedFeatures.toList())
            val prediction = result as? Double ?: return -1.0f
            Log.d(TAG, "Keras 예측 결과: $prediction")
            prediction.toFloat()
        } catch (e: Exception) {
            Log.e(TAG, "Keras 예측 실패", e)
            -1.0f
        }
    }

    fun isModelReady(): Boolean = isInitialized

    fun close() {
        // Chaquopy는 앱 종료 시 자동으로 정리됨
    }
}
