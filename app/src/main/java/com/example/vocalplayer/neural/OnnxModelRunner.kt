package com.example.vocalplayer.neural

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * Robust ONNX Runtime inference wrapper for neural source separation models.
 */
class OnnxModelRunner(private val context: Context) {
    private val tag = "OnnxModelRunner"
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var currentModelPath: String? = null

    init {
        try {
            env = OrtEnvironment.getEnvironment()
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize OrtEnvironment: ${e.message}", e)
        }
    }

    /**
     * Load an ONNX model from a local file path.
     */
    fun loadModel(modelPath: String, threadCount: Int = 4): Boolean {
        return try {
            val file = File(modelPath)
            if (!file.exists()) {
                Log.w(tag, "Model file not found: $modelPath")
                return false
            }

            closeSession()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threadCount)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            session = env?.createSession(modelPath, sessionOptions)
            currentModelPath = modelPath
            Log.i(tag, "Successfully loaded ONNX model: $modelPath (threads: $threadCount)")
            true
        } catch (e: Exception) {
            Log.e(tag, "Error loading ONNX model: ${e.message}", e)
            false
        }
    }

    /**
     * Run inference on input FloatArray tensor with specified shape.
     * Returns the output float array.
     */
    fun runInference(inputData: FloatArray, shape: LongArray): FloatArray? {
        val currentSession = session ?: return null
        val currentEnv = env ?: return null

        return try {
            val buffer = FloatBuffer.wrap(inputData)
            val tensor = OnnxTensor.createTensor(currentEnv, buffer, shape)

            val inputNames = currentSession.inputNames
            if (inputNames.isEmpty()) return null

            val inputMap = mapOf(inputNames.first() to tensor)
            val results = currentSession.run(inputMap)

            val outputNames = currentSession.outputNames
            if (outputNames.isEmpty()) {
                tensor.close()
                results.close()
                return null
            }

            val outputValue = results.get(0).value
            val outputFloatArray: FloatArray? = when (outputValue) {
                is Array<*> -> flattenNestedFloatArray(outputValue)
                is FloatArray -> outputValue
                else -> null
            }

            tensor.close()
            results.close()
            outputFloatArray
        } catch (e: Exception) {
            Log.e(tag, "ONNX inference error: ${e.message}", e)
            null
        }
    }

    private fun flattenNestedFloatArray(nested: Array<*>): FloatArray {
        val list = mutableListOf<Float>()
        fun flatten(element: Any?) {
            when (element) {
                is FloatArray -> for (f in element) list.add(f)
                is Array<*> -> for (item in element) flatten(item)
                is Float -> list.add(element)
                is Number -> list.add(element.toFloat())
            }
        }
        flatten(nested)
        return list.toFloatArray()
    }

    fun isLoaded(): Boolean = session != null

    fun getLoadedModelPath(): String? = currentModelPath

    fun closeSession() {
        try {
            session?.close()
            session = null
            currentModelPath = null
        } catch (e: Exception) {
            Log.e(tag, "Error closing ONNX session: ${e.message}", e)
        }
    }

    fun release() {
        closeSession()
        try {
            env?.close()
            env = null
        } catch (e: Exception) {
            Log.e(tag, "Error closing OrtEnvironment: ${e.message}", e)
        }
    }
}
