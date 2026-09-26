package com.example.isight.vision

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs

private const val TAG = "YoloDetector"
private const val MODEL_ASSET_NAME = "yolov11_det.tflite"
private const val LABELS_ASSET_NAME = "labels.txt"
private const val NUM_THREADS = 4
private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.4f

/**
 * One raw YOLO detection, decoded (dequantized) but NOT YET interpreted.
 *
 * [classId] is a bare integer — NOT mapped to a label string yet, per this
 * project's explicit rule to log raw class IDs before any label mapping
 * (Phase 9's job). [rawBox0]..[rawBox3] are the 4 dequantized box values in
 * whatever order/encoding the model's own last dimension uses (could be
 * cx,cy,w,h or x1,y1,x2,y2, normalized or pixel-space) — that geometric
 * interpretation is deliberately deferred to the bounding-box phase.
 */
data class RawDetection(
    val anchorIndex: Int,
    val classId: Int,
    val score: Float,
    val rawBox0: Float,
    val rawBox1: Float,
    val rawBox2: Float,
    val rawBox3: Float
)

/** Which output tensor INDEX plays which role, resolved at runtime — never hardcoded. */
private data class OutputRoles(
    val boxesIndex: Int,
    val scoresIndex: Int,
    val classIndex: Int,
    val numDetections: Int
)

/**
 * Loads the YOLOv11 TFLite model from assets, resolves its real output
 * tensor roles (Phase 8), and runs inference on a preprocessed input buffer.
 *
 * This class must be constructed off the main thread (blocking I/O). Inside
 * a single frame's processing, [detect] runs on whatever thread called it —
 * that's [com.example.isight.vision.DetectionPipeline]'s background
 * executor, per Phase 12.
 *
 * ## GPU delegate (Phase 29) — opt-in, not automatic
 * [useGpuDelegate] defaults to false. TFLite's GPU delegate can meaningfully
 * speed up inference on Adreno GPUs (like the iQOO 15's), but quantized
 * (uint8) model support has historically been inconsistent across GPU
 * driver versions — a delegate can construct successfully and then still
 * fail (or silently fall back) at actual inference time, which would be a
 * much worse debugging experience 29 phases into this project than a
 * documented manual toggle. CPU with [NUM_THREADS] threads is the proven
 * default that everything so far has been built and reasoned about against;
 * flip [useGpuDelegate] to true to measure whether GPU acceleration
 * actually helps on this specific device, with automatic fallback to CPU
 * if the delegate can't be created at all.
 */
class YoloDetector(context: Context, useGpuDelegate: Boolean = false) {

    val labels: List<String> = loadLabels(context)

    private val interpreter: Interpreter = buildInterpreter(context, useGpuDelegate)

    val inputTensorCount: Int get() = interpreter.inputTensorCount
    val outputTensorCount: Int get() = interpreter.outputTensorCount

    private val outputRoles: OutputRoles

    init {
        logTensorMetadata()
        outputRoles = resolveOutputRoles()
        requireUint8(interpreter.getOutputTensor(outputRoles.boxesIndex), "boxes")
        requireUint8(interpreter.getOutputTensor(outputRoles.scoresIndex), "scores")
        requireUint8(interpreter.getOutputTensor(outputRoles.classIndex), "classIds")
    }

    /**
     * Runs one inference pass and returns detections at or above
     * [confidenceThreshold]. Deliberately does NOT do non-max suppression
     * here — with 8400 raw anchors, many overlapping boxes for the same
     * real object are expected at this stage; deduplicating them is the
     * bounding-box phase's job, not this decode step's.
     */
    fun detect(inputBuffer: ByteBuffer, confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD): List<RawDetection> {
        inputBuffer.rewind()

        val boxesTensor = interpreter.getOutputTensor(outputRoles.boxesIndex)
        val scoresTensor = interpreter.getOutputTensor(outputRoles.scoresIndex)
        val classTensor = interpreter.getOutputTensor(outputRoles.classIndex)

        val boxesBuffer = directBuffer(boxesTensor.numBytes())
        val scoresBuffer = directBuffer(scoresTensor.numBytes())
        val classBuffer = directBuffer(classTensor.numBytes())

        val outputs = mapOf<Int, Any>(
            outputRoles.boxesIndex to boxesBuffer,
            outputRoles.scoresIndex to scoresBuffer,
            outputRoles.classIndex to classBuffer
        )
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), outputs)

        boxesBuffer.rewind()
        scoresBuffer.rewind()
        classBuffer.rewind()

        val boxesQuant = boxesTensor.quantizationParams()
        val scoresQuant = scoresTensor.quantizationParams()
        val classQuant = classTensor.quantizationParams()

        val results = mutableListOf<RawDetection>()
        for (i in 0 until outputRoles.numDetections) {
            val rawScore = scoresBuffer.get(i).toInt() and 0xFF
            val score = (rawScore - scoresQuant.zeroPoint) * scoresQuant.scale
            if (score < confidenceThreshold) continue

            val rawClass = classBuffer.get(i).toInt() and 0xFF
            val classId = Math.round((rawClass - classQuant.zeroPoint) * classQuant.scale)

            val boxBase = i * 4
            fun dequantBox(offset: Int): Float {
                val raw = boxesBuffer.get(boxBase + offset).toInt() and 0xFF
                return (raw - boxesQuant.zeroPoint) * boxesQuant.scale
            }

            results.add(
                RawDetection(
                    anchorIndex = i,
                    classId = classId,
                    score = score,
                    rawBox0 = dequantBox(0),
                    rawBox1 = dequantBox(1),
                    rawBox2 = dequantBox(2),
                    rawBox3 = dequantBox(3)
                )
            )
        }

        // Required by this project's debugging rule: log raw class IDs
        // BEFORE any label mapping, so a mislabeling bug is visible here
        // rather than hidden behind whatever label string comes out later.
        for (d in results) {
            Log.i(TAG, "RAW: index=${d.anchorIndex} classId=${d.classId} score=${"%.4f".format(d.score)}")
        }

        return results
    }

    private fun directBuffer(byteCount: Int): ByteBuffer =
        ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())

    private fun requireUint8(tensor: Tensor, role: String) {
        check(tensor.dataType() == DataType.UINT8) {
            "Expected the '$role' output tensor (name=${tensor.name()}) to be UINT8 " +
                "per this model's documented export, but it is ${tensor.dataType()}. " +
                "YoloDetector.detect()'s decode logic assumes 1-byte uint8 elements " +
                "and needs updating before this model can be used."
        }
    }

    /**
     * Resolves which output tensor INDEX is boxes/scores/classIds. Tries
     * tensor NAME matching first (per this project's rule to map outputs by
     * name, not assumed index order); falls back to shape (boxes is the
     * only tensor whose last dim is 4) and quantization scale (a genuine
     * [0,1] confidence quantized to uint8 has scale near 1/255) only when
     * names don't disambiguate. Every step is logged so a wrong resolution
     * is visible in Logcat rather than silently wrong.
     */
    private fun resolveOutputRoles(): OutputRoles {
        data class Candidate(val index: Int, val name: String, val shape: IntArray, val scale: Float)

        val candidates = (0 until interpreter.outputTensorCount).map { i ->
            val t = interpreter.getOutputTensor(i)
            Candidate(i, t.name(), t.shape(), t.quantizationParams().scale)
        }

        val boxesCandidates = candidates.filter { it.shape.isNotEmpty() && it.shape.last() == 4 }
        check(boxesCandidates.size == 1) {
            "Expected exactly one output tensor with last dimension = 4 (boxes); " +
                "found ${boxesCandidates.size} among: $candidates"
        }
        val boxes = boxesCandidates.single()
        val numDetections = boxes.shape[1]

        var remaining = candidates.filterNot { it.index == boxes.index }
        check(remaining.size == 2) {
            "Expected exactly 2 remaining output tensors (scores + class ids); " +
                "found ${remaining.size} among: $remaining"
        }

        var scores = remaining.firstOrNull { it.name.contains("score", ignoreCase = true) }
        remaining = remaining.filterNot { it.index == scores?.index }
        var classIdx = remaining.firstOrNull {
            it.name.contains("class", ignoreCase = true) || it.name.contains("idx", ignoreCase = true)
        }

        if (scores == null && classIdx != null) {
            scores = remaining.firstOrNull { it.index != classIdx?.index }
        }
        if (scores != null && classIdx == null) {
            classIdx = remaining.firstOrNull { it.index != scores?.index }
        }
        if (scores == null && classIdx == null) {
            Log.w(
                TAG,
                "No name match for 'scores'/'class ids' among: $remaining -- falling back to " +
                    "quantization scale (a real [0,1] confidence has scale near 1/255). VERIFY " +
                    "this resolution against this model's actual behavior before trusting it."
            )
            scores = remaining.minByOrNull { abs(it.scale - (1f / 255f)) }
            classIdx = remaining.firstOrNull { it.index != scores?.index }
        }

        val resolvedScores = requireNotNull(scores) { "Could not resolve 'scores' output tensor among: $remaining" }
        val resolvedClassIdx = requireNotNull(classIdx) { "Could not resolve 'class ids' output tensor among: $remaining" }
        check(resolvedScores.index != resolvedClassIdx.index) {
            "Resolved scores and classIds to the SAME output tensor index " +
                "(${resolvedScores.index}) -- resolution logic bug, not a model problem"
        }

        Log.i(
            TAG,
            "Resolved output roles -> boxes: idx=${boxes.index} name='${boxes.name}'; " +
                "scores: idx=${resolvedScores.index} name='${resolvedScores.name}'; " +
                "classIds: idx=${resolvedClassIdx.index} name='${resolvedClassIdx.name}'. " +
                "numDetections=$numDetections. VERIFY these roles are correct against Phase 6's " +
                "tensor dump before trusting any detection output."
        )

        return OutputRoles(boxes.index, resolvedScores.index, resolvedClassIdx.index, numDetections)
    }

    private fun buildInterpreter(context: Context, useGpuDelegate: Boolean): Interpreter {
        val modelBuffer = loadModelFile(context)

        if (useGpuDelegate) {
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                try {
                    val delegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                    val options = Interpreter.Options().addDelegate(delegate)
                    val interpreter = Interpreter(modelBuffer, options)
                    Log.i(TAG, "Using GPU delegate for inference")
                    return interpreter
                } catch (e: Exception) {
                    Log.w(TAG, "GPU delegate failed to initialize; falling back to CPU", e)
                }
            } else {
                Log.i(TAG, "GPU delegate not supported on this device; using CPU")
            }
        }

        Log.i(TAG, "Using CPU ($NUM_THREADS threads) for inference")
        return Interpreter(modelBuffer, Interpreter.Options().apply { setNumThreads(NUM_THREADS) })
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        context.assets.openFd(MODEL_ASSET_NAME).use { assetFileDescriptor ->
            FileInputStream(assetFileDescriptor.fileDescriptor).use { inputStream ->
                return inputStream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.declaredLength
                )
            }
        }
    }

    private fun loadLabels(context: Context): List<String> =
        context.assets.open(LABELS_ASSET_NAME).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }

    private fun logTensorMetadata() {
        Log.i(TAG, "Model loaded: $MODEL_ASSET_NAME")
        Log.i(TAG, "Labels loaded: ${labels.size} entries from $LABELS_ASSET_NAME")

        Log.i(TAG, "Input tensor count: ${interpreter.inputTensorCount}")
        for (i in 0 until interpreter.inputTensorCount) {
            logTensor("INPUT", i, interpreter.getInputTensor(i))
        }

        Log.i(TAG, "Output tensor count: ${interpreter.outputTensorCount}")
        for (i in 0 until interpreter.outputTensorCount) {
            logTensor("OUTPUT", i, interpreter.getOutputTensor(i))
        }
    }

    private fun logTensor(kind: String, index: Int, tensor: Tensor) {
        val quant = tensor.quantizationParams()
        Log.i(
            TAG,
            "  $kind[$index] name=${tensor.name()} " +
                "shape=${tensor.shape().contentToString()} " +
                "dtype=${tensor.dataType()} " +
                "numBytes=${tensor.numBytes()} " +
                "quant(scale=${quant.scale}, zeroPoint=${quant.zeroPoint})"
        )
    }

    fun close() {
        interpreter.close()
    }
}
