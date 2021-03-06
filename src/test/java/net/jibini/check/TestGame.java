package net.jibini.check;

import net.jibini.check.engine.EngineObject;
import net.jibini.check.entity.character.Attack;
import net.jibini.check.graphics.impl.LightingShaderImpl;
import net.jibini.check.resource.Resource;
import net.jibini.check.texture.Texture;
import net.jibini.check.world.GameWorld;

import org.jetbrains.annotations.NotNull;

import org.lwjgl.glfw.GLFW;

import java.util.Objects;

public class TestGame implements CheckGame
{
    @EngineObject
    private GameWorld gameWorld;

    @EngineObject
    private LightingShaderImpl lightingShader;

    /**
     * Application entry point; calls the engine boot method and hangs
     * until the game is closed.
     * <br /><br />
     *
     * !!! <strong>DO NOT PLACE CODE IN THIS MAIN METHOD</strong> !!!
     */
    public static void main(String[] args)
    {
        Check.boot(new TestGame());

        Check.infinitelyPoll();
    }

    /**
     * Game initialization section; the OpenGL context and GLFW window
     * have been created; this method is called from the game's personal
     * thread.
     * <br /><br />
     *
     * Set up graphics and register update tasks here.
     */
    @Override
    public void initialize()
    {
        gameWorld.loadRoom("main_hub");

        Objects.requireNonNull(gameWorld.getPlayer())
                .setAttack(new Attack(
                    Texture.load(Resource.fromClasspath("characters/forbes/forbes_chop_right.gif")),
                    Texture.load(Resource.fromClasspath("characters/forbes/forbes_chop_left.gif")),

                    /* Animation time (sec):    */ 0.5,
                    /* Cool-down time (sec):    */ 0.35,
                    /* Always reset animation?  */ false,

                    /* Attack damage amount:    */ 1.0,
                    /* Movement scale effect:   */ 0.5
                ));

        gameWorld.setVisible(true);

        lightingShader.setFramebufferPixelsPerTile(16);
        GLFW.glfwSwapInterval(0);
    }

    /**
     * Game update section; this is run every frame after the render
     * buffers are cleared and before the GLFW window buffers are
     * swapped; this method is called from the game's personal thread.
     * <br /><br />
     *
     * Perform physics updates, game updates, and rendering here.
     */
    public void update()
    {

    }

    /**
     * @return A game profile information object which specifies basic
     *      game information.
     */
    @NotNull
    @Override
    public Profile getProfile()
    {
        return new CheckGame.Profile(
                /* App Name:    */ "Test Game",
                /* App Version: */ "0.0"
        );
    }
}
