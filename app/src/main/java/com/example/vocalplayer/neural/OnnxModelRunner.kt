package com.example.vocalplayer.neural

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * Metadata extracted directly from loaded ONNX neural model.
 */
data class LoadedModelMetadata(
    val inputName: String,
    val inputShape: LongArray,
    val outputName: String,
    val outputShape: LongArray,
    val isMdxNet: Boolean,
    val nFft: Int = 4096,
    val hopLength: Int = 1024,
    val dimF: Int = 2048,
    val dimT: Int = 256,
    val sampleRate: Int = 44100
)

/**
 * Robust ONNX Runtime inference wrapper for neural source separation models.
 */
class OnnxModelRunner(private val context: Context) {
    private val tag = "OnnxModelRunner"
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var currentModelPath: String? = null
    private var modelMetadata: LoadedModelMetadata? = null

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

            val currentEnv = env ?: OrtEnvironment.getEnvironment().also { env = it }
            val newSession = currentEnv.createSession(modelPath, sessionOptions)
            session = newSession
            currentModelPath = modelPath

            // Introspect input/output tensor shapes and metadata
            val inputNames = newSession.inputNames.toList()
            val outputNames = newSession.outputNames.toList()

            val inputName = inputNames.firstOrNull() ?: "input"
            val outputName = outputNames.firstOrNull() ?: "output"

            val inputTensorInfo = newSession.inputInfo[inputName]?.info as? TensorInfo
            val outputTensorInfo = newSession.outputInfo[outputName]?.info as? TensorInfo

            val inputShape = inputTensorInfo?.shape ?: longArrayOf(1, 4, 2048, 256)
            val outputShape = outputTensorInfo?.shape ?: longArrayOf(1, 4, 2048, 256)

            val customMeta = try {
                newSession.metadata.customMetadata ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }

            val nFft = customMeta["n_fft"]?.toIntOrNull() ?: 4096
            val hopLength = customMeta["hop_length"]?.toIntOrNull() ?: 1024
            val dimF = customMeta["dim_f"]?.toIntOrNull()
                ?: if (inputShape.size >= 3 && inputShape[2] > 0) inputShape[2].toInt() else 2048
            val dimT = customMeta["dim_t"]?.toIntOrNull()
                ?: if (inputShape.size >= 4 && inputShape[3] > 0) inputShape[3].toInt() else 256
            val sampleRate = customMeta["sample_rate"]?.toIntOrNull() ?: 44100

            val isMdx = (inputShape.size == 4 && inputShape[1] == 4L) ||
                    file.name.contains("MDX", ignoreCase = true) ||
                    customMeta.containsKey("n_fft")

            modelMetadata = LoadedModelMetadata(
                inputName = inputName,
                inputShape = inputShape,
                outputName = outputName,
                outputShape = outputShape,
                isMdxNet = isMdx,
                nFft = nFft,
                hopLength = hopLength,
                dimF = dimF,
                dimT = dimT,
                sampleRate = sampleRate
            )

            Log.i(tag, "Successfully loaded ONNX model: $modelPath (MDX: $isMdx, dimF: $dimF, dimT: $dimT, threads: $threadCount)")
            true
        } catch (e: Exception) {
            Log.e(tag, "Error loading ONNX model: ${e.message}", e)
            false
        }
    }

    /**
     * Run high-performance inference for 4-channel UVR MDX-Net models.
     * Uses zero-allocation direct FloatBuffer mapping.
     */
    fun runMdxInference(inputTensorData: FloatArray): FloatArray? {
        val currentSession = session ?: return null
        val currentEnv = env ?: return null
        val meta = modelMetadata ?: return null

        val shape = longArrayOf(1, 4, meta.dimF.toLong(), meta.dimT.toLong())

        return try {
            val buffer = FloatBuffer.wrap(inputTensorData)
            val tensor = OnnxTensor.createTensor(currentEnv, buffer, shape)

            val inputMap = mapOf(meta.inputName to tensor)
            val results = currentSession.run(inputMap)

            val outputTensor = results.get(0) as? OnnxTensor
            val outputArray: FloatArray? = if (outputTensor != null) {
                val outBuffer = outputTensor.floatBuffer
                val arr = FloatArray(outBuffer.remaining())
                outBuffer.get(arr)
                arr
            } else {
                null
            }

            tensor.close()
            results.close()
            outputArray
        } catch (e: Exception) {
            Log.e(tag, "MDX-Net ONNX inference error: ${e.message}", e)
            null
        }
    }

    /**
     * Run inference on input FloatArray tensor with specified shape.
     * Returns the output float array via direct FloatBuffer or fallback flattening.
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

            val resultValue = results.get(0)
            val outputFloatArray: FloatArray? = if (resultValue is OnnxTensor) {
                val outBuffer = resultValue.floatBuffer
                val arr = FloatArray(outBuffer.remaining())
                outBuffer.get(arr)
                arr
            } else {
                when (val outputValue = resultValue.value) {
                    is Array<*> -> flattenNestedFloatArray(outputValue)
                    is FloatArray -> outputValue
                    else -> null
                }
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
        val list = ArrayList<Float>(nested.size * 64)
        fun flatten(element: Any?) {
            when (element) {
                is FloatArray -> for (f in element) list.add(f)
                is Array<*> -> for (item in element) flatten(item)
                is Float -> list.add(element)
                is Number -> list.add(element.toFloat())
            }
        }
        flatten(nested)
        val result = FloatArray(list.size)
        for (i in list.indices) {
            result[i] = list[i]
        }
        return result
    }

    fun isLoaded(): Boolean = session != null

    fun getLoadedModelPath(): String? = currentModelPath

    fun getModelMetadata(): LoadedModelMetadata? = modelMetadata

    fun closeSession() {
        try {
            session?.close()
            session = null
            currentModelPath = null
            modelMetadata = null
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
