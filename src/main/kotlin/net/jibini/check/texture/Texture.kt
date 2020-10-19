package net.jibini.check.texture

import net.jibini.check.graphics.Pointer
import net.jibini.check.resource.Resource
import net.jibini.check.texture.impl.AnimatedTextureImpl
import net.jibini.check.texture.impl.BitmapTextureImpl
import net.jibini.check.texture.impl.FlippedTextureImpl
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import javax.imageio.ImageIO

interface Texture : Pointer<Int>
{
    /**
     * Changes the OpenGL texture bind state if it is not already correct
     */
    fun bind()
    {
        bind(this)
    }

    fun flip(horizontal: Boolean = true, vertical: Boolean = false): Texture
    {
        return FlippedTextureImpl(this, horizontal, vertical)
    }

    /**
     * Returns the base texture coordinate and directional offsets
     */
    val textureCoordinates: TextureCoordinates

    fun putData(offsetX: Int, offsetY: Int, width: Int, height: Int, data: ByteBuffer)

    fun putData(offsetX: Int, offsetY: Int, data: BufferedImage)
    {
        this.putData(offsetX, offsetY, data.width, data.height, data.toUnsignedBytes())
    }

    companion object
    {
        private val boundPerThread = mutableMapOf<Thread, Texture>()
        private val boundPointerPerThread = mutableMapOf<Thread, Int>()

        val bound: Texture?
            get() = boundPerThread[Thread.currentThread()]

        private val boundPointer: Int
            get() = boundPointerPerThread[Thread.currentThread()] ?: 0

        fun bind(texture: Texture)
        {
            // Only change bind if the currently bound state is different
            if (texture.pointer != boundPointer)
            {
                boundPointerPerThread[Thread.currentThread()] = texture.pointer

                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.pointer)
            }

            boundPerThread[Thread.currentThread()] = texture
        }

        @JvmStatic
        fun load(resource: Resource): Texture
        {
            // Open texture resource and get readers
            val stream = ImageIO.createImageInputStream(resource.stream)
            val readers = ImageIO.getImageReaders(stream)

            var animate = false

            while (readers.hasNext())
            {
                val reader = readers.next()

                // If any reader is for GIFs, it should animate
                if (reader.formatName == "gif")
                {
                    animate = true

                    break
                }
            }

            // Use correct implementation for file format
            return if (animate)
            {
                AnimatedTextureImpl(stream)
            } else
            {
                // Read in buffered image and pass to texture
                val image = ImageIO.read(stream)
                val texture = BitmapTextureImpl(image.width, image.height)

                texture.putData(0, 0, image)

                texture
            }
        }

        fun BufferedImage.toUnsignedBytes(): ByteBuffer
        {
            // Created because this method was refactored
            val image = this
            // Allocate array for all image pixels
            val pixels = IntArray(image.width * image.height)

            // Read pixel data into array as integers (4 bytes)
            image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)

            // Check alpha channel and allocate buffer
            val hasAlpha = image.colorModel.hasAlpha()
            val buffer = BufferUtils.createByteBuffer(image.width * image.height * 4)

            for (y in 0 until image.height)
                for (x in 0 until image.width)
                {
                    // Get current pixel
                    val pixel = pixels[y * image.width + x]

                    // Shift through bytes and push them on buffer
                    buffer.put(((pixel shr 16) and 0xFF).toByte())
                    buffer.put(((pixel shr  8) and 0xFF).toByte())
                    buffer.put(((pixel       ) and 0xFF).toByte())

                    // Default to opaque if alpha doesn't exist
                    if (hasAlpha)
                        buffer.put(((pixel shr 24) and 0xFF).toByte())
                    else
                        buffer.put(0xFF.toByte())
                }

            // Flip buffer; very important for OpenGL operations
            buffer.flip()

            return buffer
        }
    }
}