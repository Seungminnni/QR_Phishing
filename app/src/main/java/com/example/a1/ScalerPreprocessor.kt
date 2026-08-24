package com.example.a1

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Applies the training-time RobustScaler parameters to the configured feature
 * columns and leaves all other model columns as raw values.
 */
class ScalerPreprocessor(private val context: Context) {

    private var robustCols: List<String> = emptyList()
    private var robustCenter: List<Float> = emptyList()
    private var robustScale: List<Float> = emptyList()
    private var rawCols: List<String> = emptyList()
    private var featureColumnOrder: List<String> = emptyList()
    private var featureInfoInputShape: List<Int> = emptyList()

    companion object {
        private const val TAG = "ScalerPreprocessor"
        private const val SCALER_PARAMS_FILE = "scaler_params.json"
        private const val FEATURE_INFO_FILE = "feature_info.json"
        private const val STATIC_FEATURE_COUNT = 54
    }

    init {
        loadScalerParams()
        loadFeatureColumnOrder()
        validateConfiguration()
    }

    private fun loadScalerParams() {
        val scalerJson = context.assets.open(SCALER_PARAMS_FILE).bufferedReader().use { it.readText() }
        val jsonObject = JSONObject(scalerJson)

        robustCols = jsonObject.getJSONArray("robust_cols").toStringList()
        robustCenter = jsonObject.getJSONArray("robust_center").toFloatList()
        robustScale = jsonObject.getJSONArray("robust_scale").toFloatList()
        rawCols = jsonObject.getJSONArray("raw_cols").toStringList()

        Log.d(TAG, "Loaded scaler params: robust=${robustCols.size}, raw=${rawCols.size}")
    }

    private fun loadFeatureColumnOrder() {
        val featureInfoJson = context.assets.open(FEATURE_INFO_FILE).bufferedReader().use { it.readText() }
        val jsonObject = JSONObject(featureInfoJson)

        featureColumnOrder = jsonObject.getJSONArray("feature_columns").toStringList()
        featureInfoInputShape = jsonObject.optJSONArray("input_shape")?.toIntList() ?: emptyList()
        Log.d(TAG, "Loaded feature order: ${featureColumnOrder.size} features, inputShape=$featureInfoInputShape")
    }

    private fun validateConfiguration() {
        require(robustCols.size == robustCenter.size) {
            "robust_center count (${robustCenter.size}) does not match robust_cols (${robustCols.size})"
        }
        require(robustCols.size == robustScale.size) {
            "robust_scale count (${robustScale.size}) does not match robust_cols (${robustCols.size})"
        }

        val scalerColumns = (robustCols + rawCols).toSet()
        val modelColumns = featureColumnOrder.toSet()

        val missingFromScaler = modelColumns - scalerColumns
        val extraInScaler = scalerColumns - modelColumns

        require(missingFromScaler.isEmpty()) {
            "feature_info.json has columns missing from scaler_params.json: $missingFromScaler"
        }
        require(extraInScaler.isEmpty()) {
            "scaler_params.json has columns missing from feature_info.json: $extraInScaler"
        }

        val expectedInputCount = featureInfoInputShape.lastOrNull() ?: featureColumnOrder.size
        require(featureColumnOrder.size == expectedInputCount) {
            "feature_info.json feature_columns count (${featureColumnOrder.size}) does not match input_shape ($expectedInputCount)"
        }
        require(featureColumnOrder.size == STATIC_FEATURE_COUNT) {
            "Static model must use $STATIC_FEATURE_COUNT features, but feature_info.json has ${featureColumnOrder.size}"
        }
    }

    fun preprocessFeatures(features: WebFeatures): FloatArray {
        val result = FloatArray(featureColumnOrder.size)

        for ((index, featureName) in featureColumnOrder.withIndex()) {
            val value = features[featureName] ?: 0f

            result[index] = when {
                featureName in robustCols -> {
                    val colIndex = robustCols.indexOf(featureName)
                    val center = robustCenter[colIndex]
                    val scale = robustScale[colIndex]
                    if (scale != 0f) (value - center) / scale else value - center
                }
                featureName in rawCols -> value
                else -> 0f
            }
        }

        val extraRawFeatures = features.keys - featureColumnOrder.toSet()
        val missingModelFeatures = featureColumnOrder.filter { !features.containsKey(it) }
        Log.d(
            TAG,
            "Preprocessed ${result.size} static features from raw=${features.size}, " +
                "extraRaw=${extraRawFeatures.size}, missingModel=${missingModelFeatures.size}"
        )
        logModelFeatureValues(features)
        return result
    }

    fun logPreprocessedFeatures(result: FloatArray) {
        val logMessage = result.mapIndexed { idx, v ->
            "${featureColumnOrder.getOrElse(idx) { "feature_$idx" }}=$v"
        }.joinToString(", ")
        Log.d(TAG, "MODEL_INPUT_SCALED_54: $logMessage")
    }

    private fun logModelFeatureValues(features: WebFeatures) {
        val rawLogMessage = featureColumnOrder.joinToString(", ") { featureName ->
            "$featureName=${features[featureName] ?: 0f}"
        }
        Log.d(TAG, "MODEL_INPUT_RAW_54: $rawLogMessage")
    }

    fun getFeatureCount(): Int {
        return featureColumnOrder.size
    }

    private fun org.json.JSONArray.toStringList(): List<String> {
        return (0 until length()).map { getString(it) }
    }

    private fun org.json.JSONArray.toFloatList(): List<Float> {
        return (0 until length()).map { getDouble(it).toFloat() }
    }

    private fun org.json.JSONArray.toIntList(): List<Int> {
        return (0 until length()).map { getInt(it) }
    }
}
