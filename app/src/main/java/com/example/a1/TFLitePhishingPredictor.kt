package com.example.a1

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files

/**
 * TFLitePhishingPredictor loads and runs a TFLite model for phishing detection.
 * 
 * Model: phishing_classifier.tflite (온-디바이스 추론용 경량 모델)
 * Input: 71개의 float32 특성 (RobustScaler 전처리됨)
 * Output: 1개의 float32 값 (피싱 확률, 0.0~1.0)
 */
class TFLitePhishingPredictor(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "TFLitePhishingPredictor"
        private const val MODEL_FILE = "phishing_classifier.tflite"
        private const val INPUT_SIZE = 64  // 64개 피처
    }

    init {
        try {
            loadModel()
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "❌ TFLite 모델 로드 실패", e)
            isInitialized = false
        }
    }

    /**
     * Assets에서 TFLite 모델 파일 로드 및 메모리 매핑
     */
    private fun loadModel() {
        try {
            val modelBuffer = loadModelFile()
            interpreter = Interpreter(modelBuffer)
            Log.d(TAG, "✅ TFLite 모델 로드 성공")
            logModelInfo()
        } catch (e: Exception) {
            Log.e(TAG, "❌ TFLite 모델 로드 실패: ${e.message}", e)
            throw e
        }
    }

    /**
     * Assets에서 모델 파일을 메모리 버퍼로 로드
     */
    private fun loadModelFile(): MappedByteBuffer {
        val assetManager = context.assets
        val assetFileDescriptor = assetManager.openFd(MODEL_FILE)
        val inputStream = assetFileDescriptor.createInputStream()
        val fileChannel = inputStream.channel

        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength

        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * 모델 입출력 정보 로깅
     */
    private fun logModelInfo() {
        try {
            interpreter?.let {
                val inputTensor = it.getInputTensor(0)
                val outputTensor = it.getOutputTensor(0)

                Log.d(TAG, "📊 모델 구조:")
                Log.d(TAG, "  입력 Shape: ${inputTensor.shape().contentToString()}")
                Log.d(TAG, "  입력 타입: ${inputTensor.dataType()}")
                Log.d(TAG, "  출력 Shape: ${outputTensor.shape().contentToString()}")
                Log.d(TAG, "  출력 타입: ${outputTensor.dataType()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "모델 정보 로깅 실패", e)
        }
    }

    /**
     * TFLite 모델로 피싱 확률 예측
     * 
     * ⭐ 모델 출력 해석 (CRITICAL):
     * - 학습 데이터: status (0=Legitimate(정상), 1=Phishing(피싱))
     * - 모델: sigmoid 활성화 → output = P(Phishing) 확률
     * - 의미: 0.0 = 정상, 1.0 = 피싱
     * 
     * 사용 방법:
     * - output >= 0.55 → 피싱 판정
     * - output < 0.55 → 정상 판정
     * 
     * @param features RobustScaler로 전처리된 71개 특성 배열 [71]
     * @return 피싱 확률 (0.0~1.0), 실패 시 -1.0
     */
    fun predictWithTFLite(features: FloatArray): Float {
        if (!isInitialized || interpreter == null) {
            Log.w(TAG, "⚠️ TFLite 모델이 초기화되지 않음")
            return -1.0f
        }

        if (features.size != INPUT_SIZE) {
            Log.e(TAG, "❌ 피처 개수 불일치: 예상=${INPUT_SIZE}, 실제=${features.size}")
            return -1.0f
        }

        return try {
            // 입력 데이터 준비 (batch size = 1)
            val input = arrayOf(features)

            // 출력 버퍼 준비
            val outputSize = 1
            val output = Array(1) { FloatArray(outputSize) }

            // 추론 수행
            interpreter?.run(input, output)

            val prediction = output[0][0]
            Log.d(TAG, "✅ TFLite 예측 성공: $prediction")

            prediction.coerceIn(0.0f, 1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "❌ TFLite 추론 실패", e)
            -1.0f
        }
    }

    /**
     * 모델 준비 상태 확인
     */
    fun isModelReady(): Boolean {
        return isInitialized && interpreter != null
    }

    /**
     * 리소스 정리
     */
    fun close() {
        try {
            interpreter?.close()
            Log.d(TAG, "✅ TFLite 인터프리터 종료")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ TFLite 인터프리터 종료 실패", e)
        }
    }
}