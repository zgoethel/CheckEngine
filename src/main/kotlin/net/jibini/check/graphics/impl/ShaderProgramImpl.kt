package net.jibini.check.graphics.impl

import net.jibini.check.engine.EngineObject
import net.jibini.check.graphics.Pointer
import net.jibini.check.graphics.Shader
import net.jibini.check.graphics.Uniforms
import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengles.GLES30

class ShaderProgramImpl : AbstractAutoDestroyable(), Shader, Pointer<Int> by PointerImpl(GLES30.glCreateProgram())
{
    @EngineObject
    private lateinit var uniforms: Uniforms

    @EngineObject
    private lateinit var statefulShaderImpl: StatefulShaderImpl

    override fun destroy()
    {
        GLES30.glDeleteProgram(pointer)
    }

    override fun use()
    {
        if (statefulShaderImpl.boundShader != this)
        {
            GLES30.glUseProgram(pointer)
            statefulShaderImpl.boundShader = this

            uniform("tex_offset", uniforms.textureOffset.x, uniforms.textureOffset.y)
            uniform("tex", uniforms.texture)
            uniform("blocking", uniforms.blocking.compareTo(false))
        }
    }

    override fun uniform(name: String, x: Int)
    {
        use()
        val location = GLES30.glGetUniformLocation(pointer, name)

        GLES30.glUniform1i(location, x)
    }

    override fun uniform(name: String, x: Float, y: Float)
    {
        use()
        val location = GLES30.glGetUniformLocation(pointer, name)

        GLES30.glUniform2f(location, x, y)
    }

    override fun uniform(name: String, x: Float, y: Float, z: Float)
    {
        use()
        val location = GLES30.glGetUniformLocation(pointer, name)

        GLES30.glUniform3f(location, x, y, z)
    }

    override fun uniform(name: String, x: Float, y: Float, z: Float, w: Float)
    {
        use()
        val location = GLES30.glGetUniformLocation(pointer, name)

        GLES30.glUniform4f(location, x, y, z, w)
    }

    override fun uniform(name: String, matrix: Matrix4f)
    {
        val buffer = BufferUtils.createFloatBuffer(16)
        matrix.get(buffer)

        use()
        val location = GLES30.glGetUniformLocation(pointer, name)

        GLES30.glUniformMatrix4fv(location, false, buffer)
    }

    fun attach(shaderImpl: ShaderImpl)
    {
        GLES30.glAttachShader(pointer, shaderImpl.pointer)
    }

    fun link()
    {
        GLES30.glLinkProgram(pointer)
    }

    companion object
    {
        private var bound = -1
    }
}