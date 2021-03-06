package net.jibini.check.world

import net.jibini.check.engine.EngineObject
import net.jibini.check.engine.Macro
import net.jibini.check.engine.RegisterObject
import net.jibini.check.engine.Updatable
import net.jibini.check.entity.Entity
import net.jibini.check.entity.character.NonPlayer
import net.jibini.check.entity.character.Player
import net.jibini.check.engine.impl.EngineObjectsImpl
import net.jibini.check.entity.Platform
import net.jibini.check.entity.behavior.EntityBehavior
import net.jibini.check.graphics.Light
import net.jibini.check.graphics.Matrices
import net.jibini.check.graphics.impl.LightingShaderImpl
import net.jibini.check.physics.Bounded
import net.jibini.check.physics.BoundingBox
import net.jibini.check.physics.QuadTree
import net.jibini.check.resource.Resource
import net.jibini.check.texture.Texture
import net.jibini.check.texture.impl.BitmapTextureImpl

import org.joml.Math

import org.slf4j.LoggerFactory

import java.io.FileNotFoundException
import java.lang.IllegalStateException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

import javax.imageio.ImageIO

import kotlin.math.abs

/**
 * An engine object which manages the game's current room and entities.
 *
 * @author Zach Goethel
 */
@RegisterObject
class GameWorld : Updatable
{
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Quad-tree index of entities in the world.
     */
    private var quadTree = QuadTree<Bounded>(0.0, 0.0, 1.0, 1.0)

    // Required to render the room with lighting
    @EngineObject
    private lateinit var lightingShader: LightingShaderImpl

    // Required to modify transformation matrices
    @EngineObject
    private lateinit var matrices: Matrices

    /**
     * Whether the world should be rendered and updated (set to false by
     * default; should be changed to true once the game is initialized
     * and ready to start a level).
     */
    var visible = false
        set(value)
        {
            if (value)
                for (entity in entities)
                    entity.deltaTimer.reset()

            field = value
        }

    /**
     * Entities in the world; can be directly added to by the game.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    val entities: MutableList<Entity> = CopyOnWriteArrayList()

    /**
     * Current room to update and render; set to null if no room should
     * be rendered.
     */
    var room: Room? = null

    /**
     * A controllable character on which the renderer will center the
     * screen.
     */
    var player: Player? = null
        set(value)
        {
            if (field != null)
            {
                if (value == null)
                {
                    if (entities.contains(field!!))
                        entities.remove(field!!)
                } else if (!entities.contains(value))
                    entities += value
            }

            field = value
        }

    /**
     * A collection of world portals which trigger world loads.
     */
    private val portals = ConcurrentHashMap<BoundingBox, String>()

    /**
     * Renders the game world and all of the entities within.
     */
    fun render()
    {
        if (!visible)
            return
        room ?: return

        room?.render()

        // Update entities last for transparency
        matrices.model.pushMatrix()

        entities.sortByDescending { it.y }
        entities.sortByDescending { it.renderBehind }

        for (entity in entities)
        {
            // Translate forward to avoid transparency issues
            matrices.model.translate(0.0f, 0.0f, 0.02f)
            entity.render()
        }

        matrices.model.popMatrix()
    }

    /**
     * Updates the quad-tree index and resolves collisions.
     */
    private fun quadTreeResolution()
    {
        quadTree.reevaluate()

        quadTree.iteratePairs {
                a, b ->

            if (a is Entity)
            {
                if ((b !is Entity || b.blocking) && !a.static)
                    a.boundingBox.resolve(b.boundingBox, a.deltaPosition, a)
            }

            if (b is Entity)
            {
                if ((a !is Entity || a.blocking) && !b.static)
                    b.boundingBox.resolve(a.boundingBox, b.deltaPosition, b)
            }
        }
    }

    /**
     * Resolves collisions with static world tiles.
     */
    private fun tileResolution(entity: Entity)
    {
        // Reset delta position aggregation
        entity.deltaPosition.set(0.0, 0.0)

        // Get the current game room; return if null
        val room = room ?: return

        val bB = entity.boundingBox

        // Iterate through each room tile
        for (y in maxOf(0, (bB.y / room.tileSize).toInt() - 1)
                until minOf(room.height, ((bB.y + bB.height) / room.tileSize).toInt() + 1))
            for (x in maxOf(0, (bB.x / room.tileSize).toInt() - 1)
                    until minOf(room.width, ((bB.x + bB.width) / room.tileSize).toInt() + 1))
            {
                // Check if the tile is blocking; default to false
                val blocking = room.tiles[x][y]?.blocking ?: false
                // Ignore tile if it is not blocking
                if (!blocking)
                    continue

                // Resolve the bounding box against each tile
                entity.boundingBox.resolve(
                    BoundingBox(x * room.tileSize + 0.01, y * room.tileSize, room.tileSize - 0.02, room.tileSize),
                    entity.deltaPosition,
                    entity
                )
            }
    }

    /**
     * Detects collisions with portals in the world.
     */
    private fun portalResolution()
    {
        for ((box, world) in portals)
            if (box.overlaps(player!!.boundingBox))
            {
                loadRoom(world)

                visible = true
            }
    }

    /**
     * Updates physics, position, and gravity for entities.
     */
    private fun preResetUpdate(entity: Entity)
    {
        // Get delta time since last frame
        val delta = entity.deltaTimer.delta

        // Apply gravity to the velocity
        if (!entity.movementRestrictions.down && room!!.isSideScroller && !entity.static)
            entity.velocity.y -= 9.8 * delta

        // Apply the velocity to the delta position
        entity.deltaPosition.x += entity.velocity.x * delta
        entity.deltaPosition.y += entity.velocity.y * delta

        entity.deltaPosition.x = Math.clamp(-0.07, 0.07, entity.deltaPosition.x)
        entity.deltaPosition.y = Math.clamp(-0.07, 0.07, entity.deltaPosition.y)

        // Apply the delta position to the position
        entity.x += entity.deltaPosition.x
        entity.y += entity.deltaPosition.y

        // Friction.
        if (entity.movementRestrictions.down)
        {
            if (entity.velocity.x != 0.0)
                entity.velocity.x -= (abs(entity.velocity.x) / entity.velocity.x) * 4.0 * delta
            if (abs(entity.velocity.x) < 0.05)
                entity.velocity.x = 0.0
        }
    }

    override fun update()
    {
        room ?: return

        lightingShader.perform { render() }

        for (entity in entities)
            entity.update()

        for (entity in entities)
        {
            preResetUpdate(entity)

            entity.movementRestrictions.reset()
            entity.deltaPosition.set(0.0, 0.0)

            tileResolution(entity)
        }

        quadTreeResolution()

        portalResolution()
    }

    /**
     * Loads the given room from the program resources and spawns the
     * entities as described in the level metadata.
     *
     * @param name Level resource folder relative to file location
     *     'worlds/'.
     */
    @Deprecated("This room file format has been replaced by the JSON world file")
    fun loadRoom(name: String): Map<Int, Tile>
    {
        reset()

        val roomImageFile = Resource.fromFile("worlds/$name/$name.png").stream
        val roomImage = ImageIO.read(roomImageFile)

        val colors = IntArray(roomImage.width * roomImage.height)
        roomImage.getRGB(0, 0, roomImage.width, roomImage.height, colors, 0, roomImage.width)

        val colorIndices = mutableListOf<Int>()
        for (x in 0 until roomImage.width)
            colorIndices += colors[x]

        val roomMetaFile = Resource.fromFile("worlds/$name/$name.txt").stream
        val roomMetaReader = roomMetaFile.bufferedReader()

        val roomTiles = mutableMapOf<Int, Tile>()

        var isSideScroller = false

        val loadMacros = mutableListOf<Macro>()

        roomMetaReader.forEachLine {
            val split = it.split(" ")

            when (split[0])
            {
                "game_type" ->
                {
                    isSideScroller = when (split[1])
                    {
                        "top_down" -> false

                        "side_scroller" -> true

                        else -> throw IllegalStateException("Invalid game type entry in meta file '${split[1]}'")
                    }
                }

                "run_macro" ->
                {

                    val macro = EngineObjectsImpl.get<Macro>()
                        .find { element -> element::class.simpleName == split[1] }

                    if (macro == null)
                        log.error("Could not find engine object '${split[1]}'")
                    else
                        loadMacros += macro
                }

                "tile" ->
                {
                    val index = split[1].toInt()

                    val texture = when (split[2])
                    {
                        "untextured" -> BitmapTextureImpl(2, 2)

                        else -> Texture.load(Resource.fromClasspath("tiles/${split[2]}"))
                    }

                    val blocking = when(split[3])
                    {
                        "blocking" -> true

                        "nonblocking" -> false

                        "nlblocking" -> true

                        else -> throw IllegalStateException("Invalid blocking entry in meta file '${split[3]}'")
                    }

                    val nlBlocking = when(split[3])
                    {
                        "blocking" -> true

                        "nonblocking" -> false

                        "nlblocking" -> false

                        else -> throw IllegalStateException("Invalid nlblocking entry in meta file '${split[3]}'")
                    }

                    roomTiles[colorIndices[index]] = Tile(texture, blocking, nlBlocking)
                }

                "spawn" ->
                {
                    when (split[1])
                    {
                        "player" ->
                        {
                            // TODO SUPPORT PLAYING AS MULTIPLE CHARACTERS
                            if (player == null)
                                player = Player(
                                    Texture.load(Resource.fromClasspath("characters/${split[2]}/${split[2]}_stand_right.gif")),
                                    Texture.load(Resource.fromClasspath("characters/${split[2]}/${split[2]}_stand_left.gif")),

                                    Texture.load(Resource.fromClasspath("characters/${split[2]}/${split[2]}_walk_right.gif")),
                                    Texture.load(Resource.fromClasspath("characters/${split[2]}/${split[2]}_walk_left.gif"))
                                )

                            player!!.x = split[3].toDouble() * 0.2
                            player!!.y = split[4].toDouble() * 0.2

                            if (!entities.contains(player!!))
                                entities += player!!
                        }

                        "character" ->
                        {
                            val standRight = Texture.load(
                                Resource.fromClasspath("characters/${split[2]}/${split[2]}_stand_right.gif")
                            )

                            val walkRight = try
                            {
                                Texture.load(Resource.fromClasspath("characters/${split[2]}/${split[2]}_walk_right.gif"))
                            } catch (ex: FileNotFoundException)
                            {
                                standRight
                            }

                            val entity = NonPlayer(
                                standRight,

                                try
                                {
                                    Texture.load(Resource.fromClasspath("characters/${split[2]}/${split[2]}_stand_left.gif"))
                                } catch (ex: FileNotFoundException)
                                {
                                    standRight.flip()
                                },

                                walkRight,

                                try
                                {
                                    Texture.load(Resource.fromClasspath("characters/${split[2]}/${split[2]}_walk_left.gif"))
                                } catch (ex: FileNotFoundException)
                                {
                                    walkRight.flip()
                                }
                            )

                            if (split.size > 5)
                            {
                                val behavior = EngineObjectsImpl.get<EntityBehavior>()
                                    .find { element -> element::class.simpleName == split[5] }

                                if (behavior == null)
                                    log.error("Could not find engine object '${split[5]}'")
                                else
                                    entity.behavior = behavior
                            }

                            entity.x = split[3].toDouble() * 0.2
                            entity.y = split[4].toDouble() * 0.2

                            entities += entity
                        }

                        "entity" ->
                        {
                            when (split[2])
                            {
                                "platform" ->
                                {
                                    val behavior =
                                        if (split.size > 6)
                                            EngineObjectsImpl.get<EntityBehavior>()
                                                .find { element -> element::class.simpleName == split[6] }!!
                                        else
                                            null

                                    entities += Platform(
                                        split[3].toDouble() * 0.2f,
                                        split[4].toDouble() * 0.2f,

                                        split[5].toDouble() * 0.2f,

                                        behavior
                                    )
                                }

                                else -> throw IllegalStateException("Invalid entity type ${split[2]} in world meta file")
                            }
                        }
                    }
                }

                "portal" ->
                {
                    portals[BoundingBox(
                        split[2].toDouble() * 0.2,
                        split[3].toDouble() * 0.2,

                        split[4].toDouble() * 0.2,
                        split[5].toDouble() * 0.2
                    )] = split[1]
                }

                "light" ->
                {
                    lightingShader.lights += Light(
                        split[1].toFloat(),
                        split[2].toFloat(),

                        split[3].toFloat(),
                        split[4].toFloat(),
                        split[5].toFloat()
                    )
                }
            }
        }

        roomMetaReader.close()

        room = Room(roomImage.width, roomImage.height - 1, 0.2, isSideScroller)
        quadTree = QuadTree(
            0.0,
            0.0,
            maxOf(room!!.width, room!!.height) * 0.2,
            maxOf(room!!.width, room!!.height) * 0.2
        )
        for (entity in entities)
            quadTree.place(entity)

        for (y in 1 until roomImage.height)
            for (x in 0 until roomImage.width)
            {
                val color = colors[y * roomImage.width + x]

                room!!.tiles[x][room!!.height - y] = roomTiles[color]
            }

        for (macro in loadMacros)
            try
            {
                macro.action()
            } catch (ex: Exception)
            {
                log.error("Failed to run world post-load macro '${macro::class.simpleName}'", ex)
            }

        room!!.rebuildMeshes()

        return roomTiles
    }

    /**
     * Removes all entities, portals, lights, and world data.
     */
    fun reset()
    {
        entities.clear()
        portals.clear()

        visible = false

        lightingShader.lights.clear()

        player = null
    }
}