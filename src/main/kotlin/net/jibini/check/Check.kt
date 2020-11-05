package net.jibini.check

import net.jibini.check.engine.impl.EngineObjectsImpl
import net.jibini.check.engine.FeatureSet
import net.jibini.check.engine.Initializable
import net.jibini.check.engine.LifeCycle
import net.jibini.check.engine.Updatable
import net.jibini.check.engine.timing.GlobalDeltaSync
import net.jibini.check.graphics.Renderer
import net.jibini.check.graphics.Window
import net.jibini.check.graphics.impl.DestroyableRegistry
import net.jibini.check.input.Keyboard
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

/**
 * Game engine entry point factory and lifecycle management
 *
 * @author Zach Goethel
 */
object Check
{
    private val log = LoggerFactory.getLogger(javaClass)

    // Tracks if any contexts have already been created
    private var contextInit = false
    private var contextInitThread: Thread? = null

    // Tracks duplicate instances of one game type
    private val instanceCount = mutableMapOf<String, Int>()

//    @JvmStatic
//    fun boot()
//    {
//        EngineObjectsImpl.initialize()
//
//        val games = EngineObjectsImpl.get<CheckGame>()
//        log.info("Found ${games.size} game class(es) on classpath")
//
//        for (game in games)
//            boot(game)
//        infinitelyPoll()
//    }

    /**
     * Boots the given instance of the given game and sets up its context; the game will not function correctly until
     * [infinitelyPoll] is called from the main thread
     *
     * @param game Game to boot and initialize context
     */
    @JvmStatic
    fun boot(game: CheckGame)
    {
        log.info("Booting application '${game.profile.appName}' version ${game.profile.appVersion} . . .")

        if (contextInit)
        {
            // Check that the game is booting from the same thread as the first game
            if (Thread.currentThread() != contextInitThread)
                // Warn about GLFW's required thread safety protocol
                log.warn("Applications should all be booted from the same thread; on some systems, it may also be necessary for that" +
                            " thread to be the main thread  (https://www.glfw.org/docs/3.3.2/intro_guide.html#thread_safety)")
        } else
        {
            // This is the first game to boot; save the thread
            contextInitThread = Thread.currentThread()
            contextInit = true

            // Init GLFW
            GLFW.glfwInit()
        }

        // If there are duplicate instances of a game, add a game number to the end of its title
        val postfix =
            if (instanceCount.containsKey(game.profile.appName))
            {
                // Register this instance in the instance count
                instanceCount[game.profile.appName] = (instanceCount[game.profile.appName]!! + 1)

                " <${instanceCount[game.profile.appName]}>"
            } else
            {
                // Register this instance in the instance count as the first instance
                instanceCount[game.profile.appName] = 1

                ""
            }

        // Create and place game's window
        val window = Window(game.profile)

        // Create and place game's keyboard
        val keyboard = Keyboard(window)

        // Enable VSync because screen tearing on high FPS
//        GLFW.glfwSwapInterval(1)

        // Create and place game's feature set
        val featureSet = FeatureSet()

        GLFW.glfwSetWindowSizeCallback(window.pointer) {
            _: Long, w: Int, h: Int ->

            window.width = w
            window.height = h
        }

        // Release the context (required for multithreading)
        GLFW.glfwMakeContextCurrent(0L)

        // Start the game's individual thread
        thread(name = "${game.profile.appName}$postfix") {
            log.debug("Branched application main engine thread")

            // Add created engine objects
            EngineObjectsImpl.objects += window
            EngineObjectsImpl.objects += keyboard
            EngineObjectsImpl.objects += featureSet
            // The game itself is also an engine object
            EngineObjectsImpl.objects += game

            // Make and keep OpenGL context current
            window.makeCurrent()
            GL.createCapabilities()

            // Create and place game's lifecycle
            val lifeCycle = LifeCycle()
            EngineObjectsImpl.objects += lifeCycle

            // Create and place game's renderer
            val renderer = Renderer()
            EngineObjectsImpl.objects += renderer

            // Search classpath for more engine objects
            EngineObjectsImpl.initialize()

            log.info("Initializing all initializable engine objects . . .")
            for (each in EngineObjectsImpl.get<Initializable>())
                each.initialize()

            log.info("Registering all updatable engine objects in lifecycle . . .")
            for (each in EngineObjectsImpl.get<Updatable>())
                lifeCycle.registerTask(each::update)

            // Register the OpenGL clear and identity reset operations
            lifeCycle.registerTask({
                GL11.glClear(featureSet.clearFlags)
                GL11.glLoadIdentity()

                val w = IntArray(1)
                val h = IntArray(1)

                GLFW.glfwGetWindowSize(window.pointer, w, h)

                GL11.glViewport(0, 0, w[0], h[0])

                val widthRatio = w[0].toDouble() / h[0]

                GL11.glMatrixMode(GL11.GL_PROJECTION)
                GL11.glLoadIdentity()
                GL11.glOrtho(-widthRatio, widthRatio, -1.0, 1.0, -100.0, 100.0)
                GL11.glMatrixMode(GL11.GL_MODELVIEW)
            }, 0)

            // Register the OpenGL/GLFW window buffer swap
            lifeCycle.registerTask {
                window.swapBuffers()

                EngineObjectsImpl.get<GlobalDeltaSync>()[0].globalAutoUpdate()
            }

            // Start game lifecycle until the window should close
            lifeCycle.start { !window.shouldClose }

            log.debug("Breaking application engine thread")

            // Destroy all destroyable objects created on this thread
            EngineObjectsImpl.get<DestroyableRegistry>()[0].flushRegistered()

            window.destroy()

            // Remove this game from the tracked instances
            instanceCount[game.profile.appName] = instanceCount[game.profile.appName]!! - 1
            if (instanceCount[game.profile.appName] == 0)
                instanceCount.remove(game.profile.appName)

            log.debug("Removed application instance; orphaned and released")
        }
    }

    /**
     * Polls and waits for GLFW input events on the main thread; hangs until all game instances are closed
     */
    @JvmStatic
    fun infinitelyPoll()
    {
        // Polls GLFW window inputs until all instances are closed
        log.debug("Entering infinite main thread polling . . .")
        while (instanceCount.isNotEmpty())
            GLFW.glfwWaitEventsTimeout(0.1)

        // All instances are closed
        log.debug("Exited infinite polling; no instances remain")
    }
}