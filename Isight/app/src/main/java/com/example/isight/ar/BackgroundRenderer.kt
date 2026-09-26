package com.example.isight.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val COORDS_PER_VERTEX = 2
private const val FLOAT_SIZE_BYTES = 4
private const val VERTEX_COUNT = 4

// Full-screen quad in OpenGL normalized device coordinates (x, y per vertex),
// wound for GL_TRIANGLE_STRIP: bottom-left, bottom-right, top-left, top-right.
private val NDC_QUAD_COORDS = floatArrayOf(
    -1.0f, -1.0f,
    +1.0f, -1.0f,
    -1.0f, +1.0f,
    +1.0f, +1.0f
)

private const val VERTEX_SHADER = """
    attribute vec2 a_Position;
    attribute vec2 a_TexCoord;
    varying vec2 v_TexCoord;
    void main() {
        gl_Position = vec4(a_Position, 0.0, 1.0);
        v_TexCoord = a_TexCoord;
    }
"""

private const val FRAGMENT_SHADER = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 v_TexCoord;
    uniform samplerExternalOES sTexture;
    void main() {
        gl_FragColor = texture2D(sTexture, v_TexCoord);
    }
"""

/**
 * Renders the ARCore camera feed as a fullscreen background quad, sampling
 * from a `GL_TEXTURE_EXTERNAL_OES` texture that ARCore writes the camera
 * image into (via [com.google.ar.core.Session.setCameraTextureName]).
 *
 * Must only be touched from the GL thread (GLSurfaceView.Renderer callbacks).
 */
class BackgroundRenderer {

    var textureId = -1
        private set

    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0
    private var textureUniform = 0

    private val quadCoords: FloatBuffer = directFloatBuffer(NDC_QUAD_COORDS.size).apply {
        put(NDC_QUAD_COORDS)
        position(0)
    }

    // Filled in every time the display geometry changes, via
    // Frame#transformCoordinates2d — this is what accounts for device
    // rotation and the camera-sensor-vs-screen aspect ratio crop.
    private val quadTexCoords: FloatBuffer = directFloatBuffer(NDC_QUAD_COORDS.size)

    /** Must be called once, on the GL thread, from onSurfaceCreated. */
    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Could not link background shader program: $log")
        }

        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "sTexture")
    }

    /** Draws the current camera frame. Call once per onDrawFrame, after Session#update(). */
    fun draw(frame: Frame) {
        if (frame.timestamp == 0L) {
            // Camera hasn't produced a frame yet.
            return
        }

        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords
            )
        }

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        quadCoords.position(0)
        GLES20.glVertexAttribPointer(
            positionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadCoords
        )
        quadTexCoords.position(0)
        GLES20.glVertexAttribPointer(
            texCoordAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadTexCoords
        )

        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile ${shaderTypeName(type)} shader: $log")
        }
        return shader
    }

    private fun shaderTypeName(type: Int) =
        if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"

    private fun directFloatBuffer(floatCount: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floatCount * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
}
