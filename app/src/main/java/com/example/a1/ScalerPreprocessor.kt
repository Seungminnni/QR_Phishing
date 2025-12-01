package com.example.a1

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * ScalerPreprocessor applies RobustScaler to specific features
 * and keeps other features unscaled (raw).
 * 
 * RobustScaler formula: (x - center) / scale
 * where center = median (Q2) and scale = IQR (Q3 - Q1)
 */
class ScalerPreprocessor(private val context: Context) {

    private var robustCols: List<String> = emptyList()
    private var robustCenter: List<Float> = emptyList()
    private var robustScale: List<Float> = emptyList()
    private var rawCols: List<String> = emptyList()
    private var featureColumnOrder: List<String> = emptyList()

    companion object {
        private const val TAG = "ScalerPreprocessor"
        private const val SCALER_PARAMS_FILE = "scaler_params.json"
    }

    init {
        try {
            loadScalerParams()
            buildFeatureColumnOrder()
        } catch (e: Exception) {
            Log.e(TAG, "ScalerPreprocessor 초기화 실패", e)
            throw e
        }
    }

    /**
     * scaler_params.json에서 RobustScaler 파라미터 로드
     */
    private fun loadScalerParams() {
        try {
            val assetManager = context.assets
            val scalerJson = assetManager.open(SCALER_PARAMS_FILE).bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(scalerJson)

            // RobustScaler columns
            val robustColsArray = jsonObject.getJSONArray("robust_cols")
            robustCols = (0 until robustColsArray.length()).map { robustColsArray.getString(it) }

            // RobustScaler center (median)
            val robustCenterArray = jsonObject.getJSONArray("robust_center")
            robustCenter = (0 until robustCenterArray.length()).map { robustCenterArray.getDouble(it).toFloat() }

            // RobustScaler scale (IQR)
            val robustScaleArray = jsonObject.getJSONArray("robust_scale")
            robustScale = (0 until robustScaleArray.length()).map { robustScaleArray.getDouble(it).toFloat() }

            // Raw columns (no scaling)
            val rawColsArray = jsonObject.getJSONArray("raw_cols")
            rawCols = (0 until rawColsArray.length()).map { rawColsArray.getString(it) }

            Log.d(TAG, "ScalerPreprocessor 로드 성공")
            Log.d(TAG, "RobustScaler 피처: ${robustCols.size}개")
            Log.d(TAG, "Raw 피처: ${rawCols.size}개")

        } catch (e: Exception) {
            Log.e(TAG, "scaler_params.json 로드 실패", e)
            throw e
        }
    }

    /**
     * RobustScaler 피처와 Raw 피처의 순서를 모델 입력 순서로 정렬
     */
    private fun buildFeatureColumnOrder() {
        try {
            val assetManager = context.assets
            val featureInfoJson = assetManager.open("feature_info.json").bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(featureInfoJson)
            val columnsArray = jsonObject.getJSONArray("feature_columns")
            featureColumnOrder = (0 until columnsArray.length()).map { columnsArray.getString(it) }
            Log.d(TAG, "피처 순서 로드 성공: ${featureColumnOrder.size}개 피처")
        } catch (e: Exception) {
            Log.e(TAG, "피처 순서 로드 실패", e)
            throw e
        }
    }

    /**
     * WebFeatures를 전처리된 float array로 변환
     * RobustScaler를 적용할 피처에만 적용, 나머지는 원본 사용
     */
    fun preprocessFeatures(features: WebFeatures): FloatArray {
        val result = FloatArray(featureColumnOrder.size)

        // 각 피처를 순서대로 전처리
        for ((index, featureName) in featureColumnOrder.withIndex()) {
            val value = features[featureName] ?: 0f

            result[index] = when {
                // RobustScaler 적용 피처
                robustCols.contains(featureName) -> {
                    val colIndex = robustCols.indexOf(featureName)
                    val center = robustCenter.getOrNull(colIndex) ?: 0f
                    val scale = robustScale.getOrNull(colIndex) ?: 1f
                    
                    // RobustScaler: (x - center) / scale
                    val scaled = if (scale != 0f) {
                        (value - center) / scale
                    } else {
                        value - center
                    }
                    scaled
                }
                // Raw 피처 (원본 그대로)
                rawCols.contains(featureName) -> value
                // 기본값 (예상하지 못한 피처)
                else -> {
                    Log.w(TAG, "Unknown feature: $featureName")
                    0f
                }
            }
        }

        Log.d(TAG, "피처 전처리 완료: ${result.size}개 값")
        return result
    }

    /**
     * 디버그용: 전처리된 값 로깅
     */
    fun logPreprocessedFeatures(result: FloatArray) {
        val logMessage = result.take(10).mapIndexed { idx, v -> "$idx=$v" }.joinToString(", ")
        Log.d(TAG, "전처리된 피처 (처음 10개): [$logMessage]")
    }
}
