package net.jibini.check

import imgui.ImGui
import imgui.flag.ImGuiConfigFlags
import imgui.gl3.ImGuiGLES30
import imgui.glfw.ImGuiGLFW

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import net.jibini.check.engine.Initializable
import net.jibini.check.engine.LifeCycle
import net.jibini.check.engine.Updatable
import net.jibini.check.engine.impl.EngineObjectsImpl
import net.jibini.check.engine.timing.GlobalDeltaSync
import net.jibini.check.graphics.Matrices
import net.jibini.check.graphics.Renderer
import net.jibini.check.graphics.Window
import net.jibini.check.graphics.impl.DestroyableRegistry
import net.jibini.check.input.Keyboard

import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengles.GLES
import org.lwjgl.opengles.GLES30
import org.lwjgl.system.Configuration
import org.lwjgl.system.Library

import org.slf4j.LoggerFactory

import java.io.File

import kotlin.concurrent.thread

/**
 * Game engine entry point factory and lifecycle management.
 *
 * @author Zach Goethel
 */
object Check
{
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Ensures GLFW doesn't poll events while rendering is in process.
     */
    @JvmStatic
    val pollMutex = Mutex()

    // Tracks if any contexts have already been created
    private var contextInit = false
    private var contextInitThread: Thread? = null

    // Tracks duplicate instances of one game type
    private val instanceCount = mutableMapOf<String, Int>()

    /**
     * Boots the given instance of the given game and sets up
     * its context; the game will not function correctly until
     * [infinitelyPoll] is called from the main thread.
     *
     * @param game Game to boot and initialize context.
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
            GLFWErrorCallback.createPrint(System.err).set()

            val l = Library.loadNative(
                Check::class.java,
                "net.jibini.check",
                "imgui-java${if (System.getProperty("os.arch").contains("64")) "64" else ""}",
                false
            )
            log.info("Loading ImGui natives from '${l.name}'")
            System.load(l.name)

            ImGui.createContext()

            val io = ImGui.getIO()
            io.iniFilename = null
            io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard)

            val fontAtlas = io.fonts
            fontAtlas.addFontDefault()
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

        if (!File("opengl_es").exists())
        {
            Configuration.OPENGLES_EXPLICIT_INIT.set(true)
            GLES.create(GL.getFunctionProvider()!!)
        }

        GLES.createCapabilities()

        // Create and place game's keyboard
        val keyboard = Keyboard(window)

        val glfw = ImGuiGLFW()
        val gles30 = ImGuiGLES30()

        window.makeCurrent()
        glfw.init(window.pointer, true)
        gles30.init()


        // Release the context (required for multithreading)
        GLFW.glfwMakeContextCurrent(0L)

        // Start the game's individual thread
        thread(name = "${game.profile.appName}$postfix") {
            log.debug("Branched application main engine thread")

            // Add created engine objects
            EngineObjectsImpl.objects += window
            EngineObjectsImpl.objects += keyboard
            // The game itself is also an engine object
            EngineObjectsImpl.objects += game

            EngineObjectsImpl.objects += glfw
            EngineObjectsImpl.objects += gles30

            // Make and keep OpenGL context current
            window.makeCurrent()

            GLES.createCapabilities()

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
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                EngineObjectsImpl.get<Matrices>()[0].model.identity()

                glfw.newFrame()
                ImGui.newFrame()

                val w = IntArray(1)
                val h = IntArray(1)

                GLFW.glfwGetWindowSize(window.pointer, w, h)

                GLES30.glViewport(0, 0, w[0], h[0])

                val widthRatio = w[0].toFloat() / h[0]

                EngineObjectsImpl.get<Matrices>()[0].projection
                    .identity()
                    .ortho(
                        -widthRatio, widthRatio,
                        -1.0f, 1.0f,
                        -100.0f, 100.0f
                    )
            }, 0)

            // Register the OpenGL/GLFW window buffer swap
            lifeCycle.registerTask {
                ImGui.render()
                gles30.renderDrawData(ImGui.getDrawData())

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
     * Polls and waits for GLFW input events on the main thread; hangs
     * until all game instances are closed.
     */
    @JvmStatic
    fun infinitelyPoll()
    {
        // Polls GLFW window inputs until all instances are closed
        log.debug("Entering infinite main thread polling . . .")
        while (instanceCount.isNotEmpty())
        {
            runBlocking {
                pollMutex.withLock {
                    GLFW.glfwPollEvents()
                }
            }

            Thread.sleep(1000L / 60)
        }

        // All instances are closed
        log.debug("Exited infinite polling; no instances remain")
    }
}