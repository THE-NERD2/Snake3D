@file:JvmName("Launcher")
package org.snake3d

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.utils.GdxRuntimeException
import com.badlogic.gdx.utils.Timer.Task
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ktx.async.KtxAsync
import ktx.async.interval
import ktx.async.newSingleThreadAsyncContext
import java.util.Collections.max
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
    Measurements:
        - Game area is 49x49x49 game pixels (center with 24 in every direction)
        - Each game pixel is 1x1x1 units
    TODO:
        * Fix window name (2.0.1)
        * UI (2.1)
        * Highscores (2.1)
        * Different camera option/control (stays close to the front of the snake) (2.1)
        - Multiplayer (3.0)
        - Random default username (3.1)
            - If you get the Alfonzo the Fat, something good happens (3.1)
        - Leaderboard (3.1)
        - Custom difficulties/difficulty files (3.2)
        - Add position markers (some object at each of the coordinates of the fruit and the snake) (3.3)
        (Intermediate versions allowed if necessary)
    Version numbering:
        a.b.c-d
        d: UI changes that cannot fit into any other category
        c: bug fix/minor improvements, e.g. graphics improvements or minor UI changes
        b: new features
        a: major reworking/migration/changes
 */
class Snake3D(listener: ApplicationListener): ApplicationListener {
    var listener = listener
        set(newValue) {
            newValue.create()
            field = newValue
        }
    override fun create() = listener.create()
    override fun render() = listener.render()
    override fun dispose() = listener.dispose()
    override fun resize(width: Int, height: Int) = listener.resize(width, height)
    override fun pause() = listener.pause()
    override fun resume() = listener.resume()
}
class Snake3DGame: ApplicationListener {
    private val env = Environment()
    private lateinit var modelBatch: ModelBatch
    lateinit var cam: PerspectiveCamera
    private lateinit var camController: CameraInputController

    lateinit var nonPlayerTiles: ArrayList<Tile>
    lateinit var player: Player
    private lateinit var stage: Stage
    private lateinit var scoreLabel: Label
    val arrowI: Int
        get() = nonPlayerTiles.indexOf(nonPlayerTiles.filter { it.type == EntityType.ARROW }[0])
    val fruitI: Int
        get() = nonPlayerTiles.indexOf(nonPlayerTiles.filter { it.type == EntityType.FRUIT }[0])
    val fruitArrowI: Int
        get() = nonPlayerTiles.indexOf(nonPlayerTiles.filter { it.type == EntityType.FRUIT_ARROW }[0])
    var restarting = false
    var dead = false
    var score = 0
    val turning = ConcurrentHashMap<String, Boolean>()

    private val events = ConcurrentHashMap<Int, () -> Unit>()
    // Initialize things that don't depend on an instance of Gdx
    init {
        turning["turning"] = false
    }
    // Initialize things that do depend on an instance of Gdx
    override fun create() {
        player = Player()
        stage = Stage()
        scoreLabel = Label("Score: 0", Label.LabelStyle(BitmapFont(), Color.BLACK))
        scoreLabel.x = 10f
        scoreLabel.y = 10f
        stage.addActor(scoreLabel)
        nonPlayerTiles = arrayListOf(
            Tile(1f, 0f, 0f, EntityType.WALL),
            Tile(-1f, 0f, 0f, EntityType.WALL),
            Tile(0f, 1f, 0f, EntityType.WALL),
            Tile(0f, -1f, 0f, EntityType.WALL),
            Tile(0f, 0f, 1f, EntityType.WALL),
            Tile(0f, 0f, -1f, EntityType.WALL),
            Tile(0f, 0f, 0f, EntityType.ARROW), // Arrow constructor not dependent on x, y, or z
            Tile((-24..24).random().toFloat(), (-24..24).random().toFloat(), (-24..24).random().toFloat(), EntityType.FRUIT)
        )
        nonPlayerTiles.add(Tile(0f, 0f, 0f, EntityType.FRUIT_ARROW)) // This constructor depends on existing nonPlayerTiles
        modelBatch = ModelBatch() // Apparently this constructor depends on Gdx.gl
        env.set(ColorAttribute(ColorAttribute.AmbientLight, 0.4f, 0.4f, 0.4f, 1f))
        env.add(DirectionalLight().set(0.8f, 0.8f, 0.8f, -1f, -0.8f, -0.2f))
        env.add(DirectionalLight().set(0.45f, 0.45f, 0.45f, 0.5f, 0.8f, 0.1f))
        cam = PerspectiveCamera(67f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        cam.position.set(0f, 0f, 3f)
        cam.lookAt(0f, 0f, 0f)
        cam.near = 0.01f
        cam.far = 100f
        cam.update()
        camController = object: CameraInputController(cam) {
            override fun keyDown(keyCode: Int): Boolean {
                when(keyCode) {
                    Keys.UP, Keys.W -> {
                        this@Snake3DGame.schedule {
                            this@Snake3DGame.player.processKey(Direction.UP)
                        }
                    }
                    Keys.DOWN, Keys.S -> {
                        this@Snake3DGame.schedule {
                            this@Snake3DGame.player.processKey(Direction.DOWN)
                        }
                    }
                    Keys.LEFT, Keys.A -> {
                        this@Snake3DGame.schedule {
                            this@Snake3DGame.player.processKey(Direction.LEFT)
                        }
                    }
                    Keys.RIGHT, Keys.D -> {
                        this@Snake3DGame.schedule {
                            this@Snake3DGame.player.processKey(Direction.RIGHT)
                        }
                    }
                    Keys.R -> {
                        // On next rendering iteration, the render loop will throw an exception since we have disposed
                        // of all the objects. This stops the LibGDX game loop. The exception is caught by the coroutine
                        // to remove the stack trace, but the Lwjgl3Application has been stopped, opening the way for
                        // main() to be called again.
                        this@Snake3DGame.restarting = true
                        this@Snake3DGame.dead = true
                        this@Snake3DGame.dispose()
                        main()
                    }
                }
                return super.keyDown(keyCode)
            }
        }
        camController.forwardKey = -1
        camController.backwardKey = -1
        camController.rotateLeftKey = -1
        camController.rotateRightKey = -1
        Gdx.input.inputProcessor = camController
        Gdx.gl.glClearColor(1f, 1f, 1f, 1f)
        open["open"] = true
    }
    override fun render() {
        camController.update()
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        stage.act()
        stage.draw()
        modelBatch.begin(cam)
        nonPlayerTiles.forEach {
            modelBatch.render(it.instance, env)
        }
        player.parts.forEach {
            modelBatch.render(it.instance, env)
        }
        modelBatch.end()
        resolveScheduledEvents()
        scoreLabel.setText("Score: $score")
    }
    override fun dispose() {
        modelBatch.dispose()
        nonPlayerTiles.forEach {
            it.model.dispose()
        }
        player.parts.forEach {
            it.model.dispose()
        }
        if (!restarting) {
            exitProcess(0)
        }
    }
    override fun resize(width: Int, height: Int) {
        Gdx.gl.glViewport(0, 0, width, height)
        // This makes the stage disappear
        //stage.viewport = FitViewport(width.toFloat(), height.toFloat(), OrthographicCamera())
    }
    override fun pause() {}
    override fun resume() {}
    fun die() {
        dead = true
    }
    fun schedule(event: () -> Unit) {
        val keyList = events.keys().toList()
        if(keyList.isEmpty()) {
            events[0] = event
        } else {
            events[max(keyList) + 1] = event
        }
    }
    private fun resolveScheduledEvents() {
        events.forEach {
            run(it.value)
        }
        events.clear()
    }
    inner class Player {
        val parts = arrayListOf(Tile(1f, 0f, 0f, EntityType.PLAYER), Tile(0f, 0f, 0f, EntityType.PLAYER), Tile(-1f, 0f, 0f, EntityType.PLAYER))
        private var direction = Direction.RIGHT
        val directionVector: Vector3
            get() = when(direction) {
                Direction.UP -> Vector3(0f, 1f, 0f)
                Direction.DOWN -> Vector3(0f, -1f, 0f)
                Direction.LEFT -> Vector3(-1f, 0f, 0f)
                Direction.RIGHT -> Vector3(1f, 0f, 0f)
                Direction.FRONT -> Vector3(0f, 0f, -1f)
                Direction.BACK -> Vector3(0f, 0f, 1f)
            }
        private var upDirection = Direction.UP
        val upDirectionVector: Vector3
            get() = when(upDirection) {
                Direction.UP -> Vector3(0f, 1f, 0f)
                Direction.DOWN -> Vector3(0f, -1f, 0f)
                Direction.LEFT -> Vector3(-1f, 0f, 0f)
                Direction.RIGHT -> Vector3(1f, 0f, 0f)
                Direction.FRONT -> Vector3(0f, 0f, -1f)
                Direction.BACK -> Vector3(0f, 0f, 1f)
            }
        fun processKey(dir: Direction) {
            fun impossibleCase(): Nothing = throw Exception("Impossible case; dir: $direction, upDir: $upDirection")
            fun inv(dir: Direction) = when(dir) {
                Direction.UP -> Direction.DOWN
                Direction.DOWN -> Direction.UP
                Direction.LEFT -> Direction.RIGHT
                Direction.RIGHT -> Direction.LEFT
                Direction.FRONT -> Direction.BACK
                Direction.BACK -> Direction.FRONT
            }
            fun left(dir: Direction, upDir: Direction): Direction { // The direction of the key hit
                fun up(dir: Direction) = when(dir) { // The direction we're moving
                    /*
                    vvvvvvvvvvvvvv The up direction */
                    Direction.LEFT -> Direction.FRONT
                    Direction.RIGHT -> Direction.BACK
                    Direction.FRONT -> Direction.RIGHT
                    Direction.BACK -> Direction.LEFT
                    else -> impossibleCase()
                }
                fun down(dir: Direction) = when(dir) {
                    Direction.LEFT -> Direction.BACK
                    Direction.RIGHT -> Direction.FRONT
                    Direction.FRONT -> Direction.LEFT
                    Direction.BACK -> Direction.RIGHT
                    else -> impossibleCase()
                }
                fun left(dir: Direction) = when(dir) {
                    Direction.UP -> Direction.BACK
                    Direction.DOWN -> Direction.FRONT
                    Direction.FRONT -> Direction.UP
                    Direction.BACK -> Direction.DOWN
                    else -> impossibleCase()
                }
                fun right(dir: Direction) = when(dir) {
                    Direction.UP -> Direction.FRONT
                    Direction.DOWN -> Direction.BACK
                    Direction.FRONT -> Direction.DOWN
                    Direction.BACK -> Direction.UP
                    else -> impossibleCase()
                }
                fun front(dir: Direction) = when(dir) {
                    Direction.UP -> Direction.LEFT
                    Direction.DOWN -> Direction.RIGHT
                    Direction.LEFT -> Direction.DOWN
                    Direction.RIGHT -> Direction.UP
                    else -> impossibleCase()
                }
                fun back(dir: Direction) = when(dir) {
                    Direction.UP -> Direction.RIGHT
                    Direction.DOWN -> Direction.LEFT
                    Direction.LEFT -> Direction.UP
                    Direction.RIGHT -> Direction.DOWN
                    else -> impossibleCase()
                }
                return when(dir) {
                    Direction.UP -> up(upDir)
                    Direction.DOWN -> down(upDir)
                    Direction.LEFT -> left(upDir)
                    Direction.RIGHT -> right(upDir)
                    Direction.FRONT -> front(upDir)
                    Direction.BACK -> back(upDir)
                }
            }
            fun right(dir: Direction, upDir: Direction): Direction {
                fun up(dir: Direction) = when(dir) {
                    Direction.LEFT -> Direction.BACK
                    Direction.RIGHT -> Direction.FRONT
                    Direction.FRONT -> Direction.LEFT
                    Direction.BACK -> Direction.RIGHT
                    else -> impossibleCase()
                }
                fun down(dir: Direction) = when(dir) {
                    Direction.LEFT -> Direction.FRONT
                    Direction.RIGHT -> Direction.BACK
                    Direction.FRONT -> Direction.RIGHT
                    Direction.BACK -> Direction.LEFT
                    else -> impossibleCase()
                }
                fun left(dir: Direction) = when(dir) {
                    Direction.UP -> Direction.FRONT
                    Direction.DOWN -> Direction.BACK
                    Direction.FRONT -> Direction.DOWN
                    Direction.BACK -> Direction.UP
                    else -> impossibleCase()
                }
                fun right(dir: Direction) = when(dir) {
                    Direction.UP -> Direction.BACK
                    Direction.DOWN -> Direction.FRONT
                    Direction.FRONT -> Direction.UP
                    Direction.BACK -> Direction.DOWN
                    else -> impossibleCase()
                }
                fun front(dir: Direction) = when(dir) {
                    Direction.UP -> Direction.RIGHT
                    Direction.DOWN -> Direction.LEFT
                    Direction.LEFT -> Direction.UP
                    Direction.RIGHT -> Direction.DOWN
                    else -> impossibleCase()
                }
                fun back(dir: Direction) = when(dir) {
                    Direction.UP -> Direction.LEFT
                    Direction.DOWN -> Direction.RIGHT
                    Direction.LEFT -> Direction.DOWN
                    Direction.RIGHT -> Direction.UP
                    else -> impossibleCase()
                }
                return when(dir) {
                    Direction.UP -> up(upDir)
                    Direction.DOWN -> down(upDir)
                    Direction.LEFT -> left(upDir)
                    Direction.RIGHT -> right(upDir)
                    Direction.FRONT -> front(upDir)
                    Direction.BACK -> back(upDir)
                }
            }
            fun performOperation(fromDir: Direction, toDir: Direction, op: Direction): Direction {
                val axisAndDirection = when(fromDir to toDir) {
                    Direction.UP to Direction.FRONT -> Axis.X_AXIS to RotationDirection.CCW
                    Direction.UP to Direction.RIGHT -> Axis.Z_AXIS to RotationDirection.CCW
                    Direction.UP to Direction.BACK -> Axis.X_AXIS to RotationDirection.CW
                    Direction.UP to Direction.LEFT -> Axis.Z_AXIS to RotationDirection.CW
                    Direction.DOWN to Direction.FRONT -> Axis.X_AXIS to RotationDirection.CW
                    Direction.DOWN to Direction.RIGHT -> Axis.Z_AXIS to RotationDirection.CW
                    Direction.DOWN to Direction.BACK -> Axis.X_AXIS to RotationDirection.CCW
                    Direction.DOWN to Direction.LEFT -> Axis.Z_AXIS to RotationDirection.CCW
                    Direction.LEFT to Direction.FRONT -> Axis.Y_AXIS to RotationDirection.CCW
                    Direction.LEFT to Direction.UP -> Axis.Z_AXIS to RotationDirection.CCW
                    Direction.LEFT to Direction.BACK -> Axis.Y_AXIS to RotationDirection.CW
                    Direction.LEFT to Direction.DOWN -> Axis.Z_AXIS to RotationDirection.CW
                    Direction.RIGHT to Direction.FRONT -> Axis.Y_AXIS to RotationDirection.CW
                    Direction.RIGHT to Direction.UP -> Axis.Z_AXIS to RotationDirection.CW
                    Direction.RIGHT to Direction.BACK -> Axis.Y_AXIS to RotationDirection.CCW
                    Direction.RIGHT to Direction.DOWN -> Axis.Z_AXIS to RotationDirection.CCW
                    Direction.FRONT to Direction.UP -> Axis.X_AXIS to RotationDirection.CW
                    Direction.FRONT to Direction.RIGHT -> Axis.Y_AXIS to RotationDirection.CCW
                    Direction.FRONT to Direction.DOWN -> Axis.X_AXIS to RotationDirection.CCW
                    Direction.FRONT to Direction.LEFT -> Axis.Y_AXIS to RotationDirection.CW
                    Direction.BACK to Direction.UP -> Axis.X_AXIS to RotationDirection.CCW
                    Direction.BACK to Direction.RIGHT -> Axis.Y_AXIS to RotationDirection.CW
                    Direction.BACK to Direction.DOWN -> Axis.X_AXIS to RotationDirection.CW
                    Direction.BACK to Direction.LEFT -> Axis.Y_AXIS to RotationDirection.CCW
                    else -> impossibleCase()
                }
                return Direction.rotate(axisAndDirection.first, axisAndDirection.second, op)
            }
            if(!turning["turning"]!!) {
                turning["turning"] = true
                val originalDirection = direction
                direction = when(dir) {
                    Direction.UP -> upDirection
                    Direction.DOWN -> inv(upDirection)
                    Direction.LEFT -> left(direction, upDirection)
                    Direction.RIGHT -> right(direction, upDirection)
                    else -> throw Exception("Illegal key direction: $dir")
                }
                if(direction == upDirection || direction == inv(upDirection)) {
                    upDirection = performOperation(originalDirection, direction, upDirection)
                }
            } else {
                schedule {
                    processKey(dir)
                }
            }
        }
        private fun nextInside(x: Float, y: Float, z: Float): Boolean {
            var inside = false
            for(part in parts) {
                // Floats are imprecise
                if(floor(x) == floor(part.x) && floor(y) == floor(part.y) && floor(z) == floor(part.z)) {
                    inside = true
                    break
                }
            }
            return inside
        }
        private fun isFruit(x: Float, y: Float, z: Float): Boolean {
            val fruit = nonPlayerTiles[fruitI]
            return floor(x) == floor(fruit.x) && floor(y) == floor(fruit.y) && floor(z) == floor(fruit.z)
        }
        fun move() {
            turning["turning"] = false
            val first = parts[0]
            val nextX = first.x + directionVector.x
            val nextY = first.y + directionVector.y
            val nextZ = first.z + directionVector.z
            if(abs(nextX) >= 25 || abs(nextY) >= 25 || abs(nextZ) >= 25 || nextInside(nextX, nextY, nextZ)) {
                die()
                return
            }
            val nextFirst = Tile(nextX, nextY, nextZ, first.type)
            parts.add(0, nextFirst)
            val lastI = parts.size - 1
            if(isFruit(nextX, nextY, nextZ)) {
                score++
                nonPlayerTiles[fruitI].model.dispose()
                nonPlayerTiles[fruitI] = Tile((-24..24).random().toFloat(), (-24..24).random().toFloat(), (-24..24).random().toFloat(), EntityType.FRUIT)
            } else {
                parts[lastI].model.dispose()
                parts.removeAt(parts.size - 1)
            }
        }
    }
}
class Snake3DGUI: ApplicationListener {
    override fun create() {}
    override fun render() {}
    override fun dispose() {}
    override fun resize(width: Int, height: Int) {}
    override fun pause() {}
    override fun resume() {}
}
data class Tile(val x: Float, val y: Float, val z: Float, val type: EntityType) {
    val model: Model
    val instance: ModelInstance
    init {
        val material = when(type) {
            EntityType.PLAYER -> Material(ColorAttribute.createDiffuse(Color.GREEN))
            EntityType.FRUIT, EntityType.FRUIT_ARROW -> Material(ColorAttribute.createDiffuse(Color.RED))
            EntityType.WALL -> Material(ColorAttribute.createDiffuse(Color.BLUE))
            EntityType.ARROW -> Material(ColorAttribute.createDiffuse(Color.BLACK))
        }
        if(type == EntityType.PLAYER || type == EntityType.FRUIT) {
            val modelBuilder = ModelBuilder()
            model = modelBuilder.createBox(
                1f, 1f, 1f,
                material,
                (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong()
            )
            instance = ModelInstance(model)
            instance.transform.setTranslation(Vector3(x, y, z))
        } else if(type == EntityType.WALL) {
            val modelBuilder = ModelBuilder()
            model = modelBuilder.createLineGrid(
                98, 98,
                0.5f, 0.5f,
                material,
                (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong()
            )
            instance = ModelInstance(model)
            // Floats are imprecise
            if(x != 0f) {
                instance.transform.translate(Vector3(x * 24.5f, 0f, 0f))
                instance.transform.rotate(Vector3(0f, 0f, 1f), 90f)
            } else if(y != 0f) {
                instance.transform.setTranslation(Vector3(0f, y * 24.5f, 0f))
            } else if(z != 0f) {
                instance.transform.translate(Vector3(0f, 0f, z * 24.5f))
                instance.transform.rotate(Vector3(1f, 0f, 0f), 90f)
            }
        } else if(type == EntityType.ARROW) {
            val modelBuilder = ModelBuilder()
            val frontPart = (snake3D!!.listener as Snake3DGame).player.parts[0]
            val direction = (snake3D!!.listener as Snake3DGame).player.directionVector
            val upDirection = (snake3D!!.listener as Snake3DGame).player.upDirectionVector
            model = modelBuilder.createArrow(
                Vector3(frontPart.x + direction.x / 2, frontPart.y + direction.y / 2, frontPart.z + direction.z / 2),
                Vector3(frontPart.x + direction.x / 2 + upDirection.x * 2, frontPart.y + direction.y / 2 + upDirection.y * 2, frontPart.z + direction.z / 2 + upDirection.z * 2),
                material,
                (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong()
            )
            instance = ModelInstance(model)
        } else if(type == EntityType.FRUIT_ARROW) {
            val modelBuilder = ModelBuilder()
            val frontPart = (snake3D!!.listener as Snake3DGame).player.parts[0]
            val fruit = (snake3D!!.listener as Snake3DGame).nonPlayerTiles[(snake3D!!.listener as Snake3DGame).fruitI]
            val arrowV = Vector3(fruit.x - frontPart.x, fruit.y - frontPart.y, fruit.z - frontPart.z)
            val mag = sqrt(arrowV.x * arrowV.x + arrowV.y * arrowV.y + arrowV.z * arrowV.z)
            if(mag > 1f) {
                arrowV.x /= mag
                arrowV.x *= 2
                arrowV.y /= mag
                arrowV.y *= 2
                arrowV.z /= mag
                arrowV.z *= 2
            }
            model = modelBuilder.createArrow(
                Vector3(frontPart.x, frontPart.y, frontPart.z),
                Vector3(frontPart.x + arrowV.x, frontPart.y + arrowV.y, frontPart.z + arrowV.z),
                material,
                (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong()
            )
            instance = ModelInstance(model)
        } else throw Exception("Impossible case: $type")
    }
}
enum class EntityType {
    PLAYER, FRUIT, WALL, ARROW, FRUIT_ARROW
}
enum class Direction {
    UP, DOWN, LEFT, RIGHT, FRONT, BACK;
    companion object {
        fun rotate(axis: Axis, rotDir: RotationDirection, dir: Direction): Direction {
            val X_CYCLE = listOf(FRONT, UP, BACK, DOWN)
            val Y_CYCLE = listOf(LEFT, BACK, RIGHT, FRONT)
            val Z_CYCLE = listOf(DOWN, RIGHT, UP, LEFT)
            val cycle = when(axis) {
                Axis.X_AXIS -> X_CYCLE
                Axis.Y_AXIS -> Y_CYCLE
                Axis.Z_AXIS -> Z_CYCLE
            }
            val deltaI = when(rotDir) {
                RotationDirection.CW -> 1
                RotationDirection.CCW -> -1
            }
            var i = cycle.indexOf(dir) + deltaI
            i = when(i) {
                -1 -> 3
                4 -> 0
                else -> i
            }
            return cycle[i]
        }
    }
}
enum class Axis {
    X_AXIS, Y_AXIS, Z_AXIS
}
enum class RotationDirection {
    CW, CCW
}
var snake3D: Snake3D? = null
val open = ConcurrentHashMap<String, Boolean>()
lateinit var gameLoopInterval: Task
fun main() {
    // PLACEHOLDER; will launch Snake3D as a Snake3DGUI and in that gui there will be an option that will call this function
    start()
}
fun start() {
    snake3D = Snake3D(Snake3DGame())
    open["open"] = false
    KtxAsync.initiate()
    val executor = newSingleThreadAsyncContext()
    KtxAsync.launch {
        withContext(executor) {
            println("Preparing game loop...")
            while(!open["open"]!!) continue
            println("Launching game loop...")
            gameLoop(snake3D!!.listener as Snake3DGame)
        }
    }
    KtxAsync.launch {
        val config = Lwjgl3ApplicationConfiguration()
        config.setTitle("Snake3D")
        config.setWindowIcon("org/snake3d/logo.png")
        config.setWindowedMode(800, 600)
        config.useVsync(true)
        config.setForegroundFPS(60)
        try {
            Lwjgl3Application(snake3D!!, config)
        } catch(_: GdxRuntimeException) {}
    }
}
fun gameLoop(snake3D: Snake3DGame) {
    fun tick() {
        if(snake3D.dead) {
            gameLoopInterval.cancel()
            return
        }
        snake3D.schedule {
            snake3D.player.move()
            snake3D.nonPlayerTiles[snake3D.arrowI].model.dispose()
            snake3D.nonPlayerTiles[snake3D.arrowI] = Tile(0f, 0f, 0f, EntityType.ARROW) // Arrow constructor not dependent on x, y, or z
            snake3D.nonPlayerTiles[snake3D.fruitArrowI].model.dispose()
            snake3D.nonPlayerTiles[snake3D.fruitArrowI] = Tile(0f, 0f, 0f, EntityType.FRUIT_ARROW)
            // First look at the origin so that we can correctly orient the camera
            // (otherwise the camera begins to rotate wildly)
            snake3D.cam.lookAt(0f, 0f, 0f)
            snake3D.cam.up.x = 0f
            snake3D.cam.up.y = 1f
            snake3D.cam.up.z = 0f
            // Smooth camera (look only a certain distance to the predicted spot of the snake
            val tile = snake3D.player.parts[0]
            if(Gdx.input.isKeyPressed(Keys.F)) {
                snake3D.cam.position.x = 0.6f * tile.x
                snake3D.cam.position.y = 0.6f * tile.y
                snake3D.cam.position.z = 0.6f * tile.z
            } else {
                snake3D.cam.position.x = 0f
                snake3D.cam.position.y = 0f
                snake3D.cam.position.z = 3f
            }
            snake3D.cam.lookAt(Vector3(tile.x, tile.y, tile.z))
            snake3D.cam.update()
        }
    }
    gameLoopInterval = interval(0.1f, 0f) {
        tick()
    }
}