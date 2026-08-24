package com.example.a1

import android.content.Context
import android.util.Log
import kotlin.math.exp
import org.json.JSONObject

class DynamicHgbModel(
    context: Context,
    assetName: String = "dynapd_hgb_strict_state59_60_40.json",
) {
    data class Result(
        val score: Double,
        val threshold: Double,
        val isPhishing: Boolean,
    )

    private data class Node(
        val value: Double,
        val feature: Int,
        val threshold: Double,
        val missingLeft: Boolean,
        val left: Int,
        val right: Int,
        val leaf: Boolean,
    )

    private val features: List<String>
    private val threshold: Double
    private val baseline: Double
    private val trees: List<List<Node>>

    val isReady: Boolean

    init {
        val loaded = runCatching {
            val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val featureArray = root.getJSONArray("features")
            val parsedFeatures = (0 until featureArray.length()).map { featureArray.getString(it) }
            val parsedThreshold = root.getDouble("threshold")
            val parsedBaseline = root.getDouble("baseline")
            val treeArray = root.getJSONArray("trees")
            val parsedTrees = (0 until treeArray.length()).map { treeIndex ->
                val nodesArray = treeArray.getJSONObject(treeIndex).getJSONArray("nodes")
                (0 until nodesArray.length()).map { nodeIndex ->
                    val node = nodesArray.getJSONObject(nodeIndex)
                    Node(
                        value = node.getDouble("value"),
                        feature = node.getInt("feature"),
                        threshold = node.getDouble("threshold"),
                        missingLeft = node.getBoolean("missing_left"),
                        left = node.getInt("left"),
                        right = node.getInt("right"),
                        leaf = node.getBoolean("leaf"),
                    )
                }
            }
            Triple(parsedFeatures, Pair(parsedThreshold, parsedBaseline), parsedTrees)
        }

        if (loaded.isSuccess) {
            val value = loaded.getOrThrow()
            features = value.first
            threshold = value.second.first
            baseline = value.second.second
            trees = value.third
            isReady = true
            Log.i(TAG, "Loaded DynaPD HGB model: features=${features.size}, trees=${trees.size}, threshold=$threshold")
        } else {
            features = emptyList()
            threshold = 0.47
            baseline = 0.0
            trees = emptyList()
            isReady = false
            Log.e(TAG, "Failed to load DynaPD HGB model", loaded.exceptionOrNull())
        }
    }

    fun predict(values: Map<String, Double>): Result? {
        if (!isReady) return null
        val vector = DoubleArray(features.size) { index -> values[features[index]] ?: 0.0 }
        var raw = baseline
        for (tree in trees) {
            raw += predictTree(tree, vector)
        }
        val score = sigmoid(raw)
        return Result(
            score = score,
            threshold = threshold,
            isPhishing = score >= threshold,
        )
    }

    private fun predictTree(nodes: List<Node>, vector: DoubleArray): Double {
        var index = 0
        while (true) {
            val node = nodes[index]
            if (node.leaf) return node.value
            val value = vector.getOrElse(node.feature) { 0.0 }
            index = if (value.isNaN()) {
                if (node.missingLeft) node.left else node.right
            } else if (value <= node.threshold) {
                node.left
            } else {
                node.right
            }
        }
    }

    private fun sigmoid(raw: Double): Double {
        return if (raw >= 0.0) {
            1.0 / (1.0 + exp(-raw))
        } else {
            val e = exp(raw)
            e / (1.0 + e)
        }
    }

    companion object {
        private const val TAG = "DynamicHgbModel"
    }
}
