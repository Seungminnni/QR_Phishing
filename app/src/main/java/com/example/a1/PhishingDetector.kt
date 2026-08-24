package com.example.a1

import android.content.Context
import android.util.Log

/**
 * PhishingDetector uses TFLite 모델 with RobustScaler preprocessing
 * and fallback to heuristics if model inference fails.
 */
class PhishingDetector(private val context: Context) {

    private val tflitePredictor: TFLitePhishingPredictor?
    private val scalerPreprocessor: ScalerPreprocessor?

    companion object {
        private const val TAG = "PhishingDetector"
        private const val ML_THRESHOLD = 0.70f
    }

    init {
        // TFLite 모델 초기화
        tflitePredictor = try {
            TFLitePhishingPredictor(context).also {
                if (it.isModelReady()) {
                    Log.d(TAG, "✅ TFLite 모델 초기화 성공")
                } else {
                    Log.w(TAG, "⚠️ TFLite 모델 로드 실패")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ TFLite 모델 초기화 예외 발생", e)
            null
        }

        // RobustScaler 전처리 초기화
        scalerPreprocessor = try {
            ScalerPreprocessor(context).also {
                Log.d(TAG, "✅ ScalerPreprocessor 초기화 성공")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ ScalerPreprocessor 초기화 실패", e)
            null
        }
    }

    fun analyzePhishing(features: WebFeatures, currentUrl: String?): PhishingAnalysisResult {
        Log.d(TAG, "analyzePhishing() 호출됨 - 피처 수: ${features.size}, URL: $currentUrl")
        val riskReasons = mutableListOf<String>()

        // Basic heuristics for explainability
        runCatching {
            if (features["shortening_service"] == 1.0f) riskReasons.add("단축 URL 서비스 감지")
            if (features["login_form"] == 1.0f) riskReasons.add("로그인/외부 폼 감지")
            if ((features["nb_redirection"] ?: 0f) >= 3f) riskReasons.add("다수의 리다이렉션 감지")
            if (features["suspecious_tld"] == 1.0f) riskReasons.add("의심스러운 최상위 도메인")
            if (features["domain_in_brand"] == 1.0f) riskReasons.add("브랜드명 포함 도메인")
            if (features["brand_in_path"] == 1.0f) riskReasons.add("브랜드명 포함 경로")
        }

        // TFLite 모델로 예측
        var mlScoreFloat = -1.0f

        if (tflitePredictor?.isModelReady() == true && scalerPreprocessor != null) {
            Log.d(TAG, "🤖 TFLite 모델로 예측 시작")
            try {
                val preprocessedFeatures = scalerPreprocessor.preprocessFeatures(features)
                scalerPreprocessor.logPreprocessedFeatures(preprocessedFeatures)
                mlScoreFloat = tflitePredictor.predictWithTFLite(preprocessedFeatures)
                if (mlScoreFloat >= 0) {
                    Log.d(TAG, "✅ TFLite 예측 성공: $mlScoreFloat")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ TFLite 예측 실패", e)
            }
        } else {
            Log.w(TAG, "⚠️ TFLite 모델이 준비되지 않음")
        }

        val (confidenceScore, isPhishing) = if (mlScoreFloat >= 0f) {
            val score = mlScoreFloat.coerceIn(0f, 1f).toDouble()

            // ⭐ 모델 출력 해석 (CRITICAL FIX):
            // 노트북: status (0=Legitimate, 1=Phishing) 학습
            // 모델 출력: sigmoid → P(Phishing) 확률
            // 따라서: score >= THRESHOLD → 피싱 (역전이 아님!)
            //
            // 이전 버그: isPhishing = score >= ML_THRESHOLD (정상과 피싱이 역반대)
            // 수정: 모델 학습 시 P(Phishing)을 출력하도록 구성했으므로 그대로 사용

            Log.d(TAG, "🤖 ML Score: $score (Threshold: $ML_THRESHOLD)")
            Pair(score, score >= ML_THRESHOLD)
        } else {
            // ML 실패 시 휴리스틱
            val heuristicsScore = if (riskReasons.isNotEmpty()) 0.6 else 0.0
            Log.w(TAG, "⚠️ ML 모델 예측 불가, 휴리스틱 사용: $heuristicsScore")
            Pair(heuristicsScore, heuristicsScore >= ML_THRESHOLD)
        }

        return PhishingAnalysisResult(
            inspectedUrl = currentUrl,
            isPhishing = isPhishing,
            confidenceScore = confidenceScore,
            features = features,
            riskFactors = riskReasons
        )
    }

    fun isModelReady(): Boolean {
        return tflitePredictor?.isModelReady() == true
    }

    fun close() {
        tflitePredictor?.close()
    }
}
