package net.jibini.check.entity.character

import net.jibini.check.engine.EngineObject
import net.jibini.check.entity.ActionableEntity
import net.jibini.check.input.Keyboard
import net.jibini.check.texture.Texture
import org.lwjgl.glfw.GLFW

/**
 * Keyboard-controlled [actionable character][ActionableEntity] which is likely a human sprite-set
 *
 * @author Zach Goethel
 */
class Player(
    /**
     * Character's right-facing idle texture
     */
    idleRight: Texture,

    /**
     * Character's left-facing idle texture
     */
    idleLeft: Texture = idleRight.flip(),

    /**
     * Character's right-facing walking texture
     */
    walkRight: Texture,

    /**
     * Character's left-facing walking texture
     */
    walkLeft: Texture = idleRight.flip()
) : ActionableEntity(idleRight, idleLeft, walkRight, walkLeft)
{
    @EngineObject
    private lateinit var keyboard: Keyboard

    init
    {
        // Listen to space to trigger attack
        keyboard.addKeyListener(GLFW.GLFW_KEY_SPACE, this::attack)
        // Listen to left shift to trigger jump
        keyboard.addKeyListener(GLFW.GLFW_KEY_LEFT_SHIFT) { jump(0.55) }
    }

    override fun update()
    {
        // Call super's rendering and physics
        super.update()

        // Check WASD keys
        val w = keyboard.isPressed(GLFW.GLFW_KEY_W) && !(gameWorld.room?.isSideScroller ?: false)
        val a = keyboard.isPressed(GLFW.GLFW_KEY_A)
        val s = keyboard.isPressed(GLFW.GLFW_KEY_S) && !(gameWorld.room?.isSideScroller ?: false)
        val d = keyboard.isPressed(GLFW.GLFW_KEY_D)

        // Calculate x/y movement based on key input
        val x = (if (a) -1 else 0) + (if (d) 1 else 0).toDouble()
        val y = (if (s) -1 else 0) + (if (w) 1 else 0).toDouble()

        // Walk based on previous movement
        this.walk(x, y)

        if (this.y < -16.0)
        {
            //TODO FANCIER DEATH
            gameWorld.loadRoom("main_hub")
            gameWorld.visible = true

            velocity.y = 0.0
        }
    }
}