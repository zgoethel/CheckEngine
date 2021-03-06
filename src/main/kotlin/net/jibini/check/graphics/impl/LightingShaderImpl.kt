package net.jibini.check.graphics.impl

import net.jibini.check.engine.EngineObject
import net.jibini.check.engine.Initializable
import net.jibini.check.engine.RegisterObject
import net.jibini.check.graphics.Framebuffer
import net.jibini.check.graphics.Light
import net.jibini.check.graphics.Matrices
import net.jibini.check.graphics.Renderer
import net.jibini.check.resource.Resource
import net.jibini.check.graphics.Shader
import net.jibini.check.graphics.Window
import net.jibini.check.world.GameWorld

import org.joml.Matrix4f
import org.joml.Vector2d
import org.joml.Vector2f

import org.lwjgl.opengles.GLES30

/**
 * The lighting engine and lighting algorithm pipeline. This is an
 * implementation class and is subject to change.
 *
 * Currently, this class performs all world rendering. The [GameWorld]
 * will pass a lambda representation of all entity and room rendering
 * tasks. This class may invoke the rendering multiple time, with the
 * [nlBlockingOverride] flag in different states or for different mask
 * or screen-space renders.
 *
 * Global illumination is applied to any entities or tiles rendered
 * in the world space. Lights outside of a screen radius will not be
 * rendered. Any item rendered within world-space should specify whether
 * or not it blocks light. Check the [nlBlockingOverride] state for
 * whether non-light-blocking items should report as light-blocking
 * (such that non-light-blocking entities will be fully lit despite
 * shadows).
 *
 * Multiple render passes are performed here, and are subject to
 * optimization. Rendering resolution is reduced to a 1:1 or 2:1 texel
 * match for tile textures. This enforces consistent pixel size and
 * snap, and increases frame rate on embedded systems.
 *
 * @author Zach Goethel
 */
@RegisterObject
class LightingShaderImpl : Initializable
{
    // Required to draw quads to process render passes
    @EngineObject
    private lateinit var renderer: Renderer

    // Required to access the current window size
    @EngineObject
    private lateinit var window: Window

    // Required to access the world's tile array size
    @EngineObject
    private lateinit var gameWorld: GameWorld

    // Required to modify the transform matrices
    @EngineObject
    private lateinit var matrices: Matrices

    /**
     * A shader program which will produce two output textures: one
     * with color and textures, and one black and white mask of which
     * elements in the world are light-blocking.
     *
     * Render attachment 0 contains textures and colors. Render
     * attachment 1 contains a lighting mask, where white indicates a
     * pixel is light-blocking.
     */
    private lateinit var lightMask: Shader

    /**
     * A framebuffer which spans over the entire level. Used to generate
     * a full-level lighting mask (rendered once, used by all lights).
     */
    private lateinit var worldSpace: Framebuffer

    /**
     * A shader program which compares fragments on screen to the rays
     * recorded in the ray atlas. It draws shadows and assigns light
     * values to fragments.
     *
     * A render pass is performed for each light using this shader, and
     * render passes are blended together using OpenGL blend.
     */
    private lateinit var shadowAndLight: Shader

    /**
     * Simple shader program for textured and colored drawing. Used to
     * render render target contents to screen.
     */
    private lateinit var textured: Shader

    /**
     * Generates a ray atlas for a light into a small output texture.
     *
     * A render pass is performed for each light using this shader, and
     * each render pass of [shadowAndLight] references the respective
     * ray atlas generated by this shader.
     */
    private lateinit var rayTracer: Shader

    /**
     * Ray atlas which stores the traced rays for each light. Generated
     * for each light by the [rayTracer] shader.
     */
    private lateinit var rays: Framebuffer

    /**
     * A framebuffer which spans over the screen, which may be
     * downscaled. Resolution should be match 1:1 or 2:1 to the texels
     * of the world tiles.
     *
     * Used to render an individual light prior to blending.
     */
    private lateinit var screenSpace: Framebuffer

    /**
     * A framebuffer which spans over the screen, which may be
     * downscaled. Resolution should be match 1:1 or 2:1 to the texels
     * of the world tiles.
     *
     * This framebuffer is used to blend multiple lights.
     */
    private lateinit var screen: Framebuffer

    /**
     * Number of pixels per tile in the world-space framebuffer. Decides
     * the resolution of the world-space framebuffer.
     */
    private val pixelsPerTile = 16

    /**
     * Number of pixels per tile in the screen-space framebuffers.
     * Should be a 1:1 or 2:1 ratio to the world-space framebuffer tile
     * size (to avoid tearing and downscaling issues).
     */
    var framebufferPixelsPerTile = 32

    /**
     * The ray atlas will have this many rays across one edge of its
     * texture. The number of rays will be this number squared.
     */
    private val raysSize = 16

    /**
     * The offset of the character compared to the center of the screen.
     */
    private val offset = 0.3f

    /**
     * World tiles and entities will be rendered with this size scale.
     */
    private val scale = 1.4f

    /**
     * When this flag is set to true, non-light-blocking entities which
     * should be bright despite any surrounding lighting should be
     * rendered as light-blocking.
     *
     * Used on characters in top-down mode: only lower body casts a
     * shadow, but the entire body is fully lit.
     */
    var nlBlockingOverride = false

    /**
     * The global collection of lights in the level.
     */
    val lights = mutableListOf<Light>()

    /**
     * Fallback display translation in case [GameWorld.player] is null.
     * Used primarily by the in-engine world editor.
     */
    var translation = Vector2f()

    // The size the framebuffers _should_ be; if different from actual
    // framebuffer size, framebuffers are rebuilt
    private var properWidth = 0
    private var properHeight = 0

    /**
     * The ratio of the window width to the window height.
     */
    private val windowRatio: Float
        get() = properWidth.toFloat() / properHeight

    override fun initialize()
    {
        lightMask = Shader.create(
            Resource.fromClasspath("shaders/textured.vert"),
            Resource.fromClasspath("shaders/light_mask.frag")
        )

        textured = Shader.create(
            Resource.fromClasspath("shaders/textured.vert"),
            Resource.fromClasspath("shaders/textured.frag")
        )

        rayTracer = Shader.create(
            Resource.fromClasspath("shaders/ray_tracing.vert"),
            Resource.fromClasspath("shaders/ray_tracing.frag")
        )

        shadowAndLight = Shader.create(
            Resource.fromClasspath("shaders/shadow.vert"),
            Resource.fromClasspath("shaders/shadow.frag")
        )

        rays = Framebuffer(raysSize, raysSize, 1)
    }

    /**
     * Compares the resolution of the framebuffers to what they should
     * be and recreates them if necessary.
     */
    private fun validateFramebuffers()
    {
        properWidth = gameWorld.room!!.width * pixelsPerTile
        properHeight = gameWorld.room!!.height * pixelsPerTile

        if (!this::worldSpace.isInitialized
            || worldSpace.width != properWidth
            || worldSpace.height != properHeight)
        {
            worldSpace = Framebuffer(properWidth, properHeight, 2)
        }

        properHeight = (2.0f / scale / 0.2f * framebufferPixelsPerTile).toInt();
        properWidth = (properHeight.toFloat() * (window.width.toFloat() / window.height)).toInt()

        properWidth -= properWidth % (framebufferPixelsPerTile * scale).toInt()

        if (!this::screenSpace.isInitialized
            || screenSpace.width != properWidth
            || screenSpace.height != properHeight)
        {
            screenSpace = Framebuffer(properWidth, properHeight, 2)
            screen = Framebuffer(properWidth, properHeight, 2)
        }
    }

    /**
     * Renders the entirety of the level into the world-space
     * framebuffer. This creates a world-wide mask of which elements
     * block light.
     *
     * This should be called once per frame, and its rendered texture
     * output should be shared across all lighting render passes.
     */
    private fun generateLightMask(renderTask: () -> Unit)
    {
        worldSpace.bind()
        lightMask.use()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // Set the projection matrix to cover the whole world
        matrices.projection
            .identity()
            .ortho(
                0.0f, 0.2f * gameWorld.room!!.width,
                0.0f, 0.2f * gameWorld.room!!.height,
                -100.0f, 100.0f
            )
        matrices.model.identity()

        renderTask()

        Framebuffer.release()
    }

    /**
     * Applies a scale and translation to correctly center the player on
     * the screen and the world around the player.
     *
     * If [GameWorld.player] is a null-pointer, resorts to [translation].
     */
    private fun worldTransform()
    {
        matrices.projection
            .identity()
            .ortho(
                -windowRatio, windowRatio,
                -1.0f, 1.0f,
                -100.0f, 100.0f
            )
        matrices.model.identity()

        val playerX = gameWorld.player?.x?.toFloat() ?: translation.x
        val playerY = gameWorld.player?.y?.toFloat() ?: translation.y

        // Snap to pixel
        var translateX = -playerX
        translateX -= translateX % (0.2f / framebufferPixelsPerTile)
        var translateY = -playerY - offset
        translateY -= translateY % (0.2f / framebufferPixelsPerTile)

        matrices.model.scale(scale)
        matrices.model.translate(translateX, translateY, 0.0f)
    }

    /**
     * Renders the portion of the level which is currently on screen.
     * During this render process, the [nlBlockingOverride] is set.
     *
     * Entities which should be fully lit should render as blocking,
     * even if they do not block light.
     */
    private fun generatePresentedCopy(renderTask: () -> Unit)
    {
        screenSpace.bind()
        lightMask.use()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        worldTransform()

        nlBlockingOverride = true
        renderTask()
        nlBlockingOverride = false

        Framebuffer.release()
    }

    /**
     * Performs the ray-tracing for one light position.
     */
    private fun generateRays(lightX: Float, lightY: Float)
    {
        rays.bind()
        rayTracer.use()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        matrices.projection.identity()
            .ortho(
                -1.0f, 1.0f,
                -1.0f, 1.0f,
                -1.0f, 1.0f
            )
        matrices.model.identity()

        // Bind the global light mask
        worldSpace
            .renderAttachments[1]
            .bind()

        // Set the ray-tracer uniforms
        rayTracer.uniform("output_size", raysSize)
        rayTracer.uniform("input_width", worldSpace.width)
        rayTracer.uniform("input_height", worldSpace.height)
        rayTracer.uniform("light_mask", 0)
        rayTracer.uniform("light_position", lightX, lightY)

        renderer.drawRectangle(-1.0f, -1.0f, 2.0f, 2.0f)

        Framebuffer.release()
    }

    /**
     * Draws the shadows in screen-space for one light position.
     */
    private fun drawShadows(lightX: Float, lightY: Float, r: Float, g: Float, b: Float)
    {
        screen.bind()
        shadowAndLight.use()

        matrices.projection.identity()
            .ortho(
                -windowRatio, windowRatio,
                -1.0f, 1.0f,
                -1.0f, 1.0f
            )
        matrices.model.identity()

        rays.renderAttachments[0]
            .flip(horizontal = false, vertical = true)
            .bind()

        val playerX = gameWorld.player?.x?.toFloat() ?: translation.x
        val playerY = gameWorld.player?.y?.toFloat() ?: translation.y

        val matrix = Matrix4f()
            .ortho(-windowRatio, windowRatio, -1.0f, 1.0f, -1.0f, 1.0f)
            .invertOrtho()
            .translate(playerX / windowRatio * scale, (playerY + offset) * scale, 0.0f)
            .scaleLocal(1.0f / scale, 1.0f / scale, 1.0f)

        shadowAndLight.uniform("input_size", raysSize)
        shadowAndLight.uniform("light_color", r, g, b)
        shadowAndLight.uniform("light_position", lightX, lightY)
        shadowAndLight.uniform("frag_matrix", matrix)
        shadowAndLight.uniform("ray_scale", 1.0f / scale)

        renderer.drawRectangle(-windowRatio, -1.0f, windowRatio * 2.0f, 2.0f)

        Framebuffer.release()
    }

    /**
     * Performs a blend of all of the lights, a [nlBlockingOverride]
     * mask, and the world colors.
     */
    private fun halfResolutionRender()
    {
        screen.bind()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)

        for (light in lights)
        {
            if (Vector2d(
                    gameWorld.player?.x ?: translation.x.toDouble(),
                    gameWorld.player?.y ?: translation.y.toDouble()
                ).distance(Vector2d(
                    light.x.toDouble() * 0.2,
                    light.y.toDouble() * 0.2)
                ) > windowRatio * 1.6f
            ) continue

            generateRays(light.x, light.y)
            drawShadows(light.x, light.y, light.r, light.g, light.b)
        }

        screen.bind()
        textured.use()

        matrices.projection.identity()
            .ortho(
                -windowRatio, windowRatio,
                -1.0f, 1.0f,
                -1.0f, 1.0f
            )
        matrices.model.identity()

        screenSpace.renderAttachments[1]
            .flip(horizontal = false, vertical = true)
            .bind()

        renderer.drawRectangle(-windowRatio, -1.0f, windowRatio * 2, 2.0f)

        if (lights.isEmpty())
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        else
            GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        screenSpace.renderAttachments[0]
            .flip(horizontal = false, vertical = true)
            .bind()

        renderer.drawRectangle(-windowRatio, -1.0f, windowRatio * 2, 2.0f)

        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        Framebuffer.release()
    }

    /**
     * Performs a lit render of the given lambda collection of render
     * calls. This can include rendering of entities and world rooms,
     * but should not include updates to physics or AI.
     *
     * @param renderTask Lambda containing render calls for lighting.
     */
    fun perform(renderTask: () -> Unit)
    {
        validateFramebuffers()

        generateLightMask(renderTask)
        generatePresentedCopy(renderTask)

        halfResolutionRender()
        
        textured.use()

        GLES30.glViewport(0, 0, window.width, window.height)

        screen.renderAttachments[0]
            .flip(horizontal = false, vertical = true)
            .bind()

        renderer.drawRectangle(-windowRatio - 0.1f, -1.1f, windowRatio * 2.2f, 2.2f)
    }
}
