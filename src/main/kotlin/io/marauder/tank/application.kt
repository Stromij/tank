package io.marauder.tank

import com.datastax.driver.core.Cluster
import io.ktor.application.*
import io.ktor.response.*
import io.ktor.request.*
import io.ktor.routing.*
import io.ktor.http.*
import io.ktor.features.*
import org.slf4j.event.*
import java.time.*
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.util.InternalAPI
import io.ktor.util.decodeString
import io.marauder.supercharged.Clipper
import io.marauder.supercharged.Encoder
import io.marauder.supercharged.Projector
import io.marauder.supercharged.models.*
import io.marauder.tank.Tiler
import kotlinx.coroutines.*
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.json.JSON
import kotlinx.serialization.json.JsonParsingException
import kotlinx.serialization.parse
import kotlinx.serialization.parseList
import org.slf4j.LoggerFactory
import vector_tile.VectorTile


fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

    @InternalAPI
    @ImplicitReflectionSerializer
    fun Application.module() {

        val marker = Benchmark(LoggerFactory.getLogger(this::class.java))

        val minZoom = environment.config.propertyOrNull("ktor.application.min_zoom")?.getString()?.toInt() ?: 2
        val maxZoom = environment.config.propertyOrNull("ktor.application.max_zoom")?.getString()?.toInt() ?: 15
        val baseLayer = environment.config.propertyOrNull("ktor.application.base_layer")?.getString()
                ?: "io.marauder.tank"
        val extend = environment.config.propertyOrNull("ktor.application.extend")?.getString()?.toInt() ?: 4096
        val attrFields = environment.config.propertyOrNull("ktor.application.attr_field")?.getList() ?: emptyList()
        val buffer = environment.config.propertyOrNull("ktor.application.buffer")?.getString()?.toInt() ?: 64
        val dbHosts = environment.config.propertyOrNull("ktor.application.db_hosts")?.getString()?.split(",")?.map { it.trim() } ?: listOf("localhost")

        val clusterBuilder = Cluster.builder().apply {
            dbHosts.forEach {
                addContactPoint(it)
            }
        }

        var isConnected = false
        var attempts = 10
        while (!isConnected && attempts >= 0) {
            try {
                initCassandra(clusterBuilder)
                isConnected = true
            } catch (e: RuntimeException) {
                attempts--
                runBlocking {
                    delay(3_000)
                }
            }
        }

        val cluster = clusterBuilder.build()
        val session = cluster.connect("geo")
        val tiler = Tiler(session, minZoom, maxZoom, extend, buffer)
        val projector = Projector()

//        val q = session.prepare("SELECT geometry, id FROM features WHERE z=? AND x=? AND y=?;")

        val query = """SELECT geometry, id FROM features WHERE expr(test_idx, '{
            |   filter: {
            |       type: "geo_bbox",
            |       field: "geometry",
            |       min_latitude: 0,
            |       max_latitude: 0,
            |       min_longitude: 0,
            |       max_longitude: 0
            |   }
            |}');""".trimMargin()

        println(query)

        val q = session.prepare(query)


        install(Compression) {
            gzip {
                priority = 1.0
            }
            deflate {
                priority = 10.0
                minimumSize(1024) // condition
            }
        }

        install(CallLogging) {
            level = Level.INFO
            filter { call -> call.request.path().startsWith("/") }
        }

        install(DefaultHeaders) {
            header("X-Engine", "Ktor") // will send this header with each response
        }

        install(io.ktor.websocket.WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(15)
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }

        routing {
            get("/") {
                call.respondText("Tank Tyle Database is running")
            }

            post("/file") {
                val geojson = JSON.plain.parse<GeoJSON>(call.receiveText())
                GlobalScope.launch {
//                    val neu = projector.projectFeatures(geojson)
                    tiler.tiler(geojson)
                }


                call.respondText("Features Accepted", contentType = ContentType.Text.Plain, status = HttpStatusCode.Accepted)
            }

            post("/") {
                val features = mutableListOf<Feature>()
                call.receiveStream().bufferedReader().useLines { lines ->
                    lines.forEach { features.add(JSON.plain.parse(it)) }
                }
                val geojson = GeoJSON(features = features)
                GlobalScope.launch {
                    val neu = projector.projectFeatures(geojson)
                    tiler.tiler(neu)
                }

                call.respondText("Features Accepted", contentType = ContentType.Text.Plain, status = HttpStatusCode.Accepted)
            }

            get("/tile/{z}/{x}/{y}") {

                val z = call.parameters["z"]?.toInt()?:-1
                val x = call.parameters["x"]?.toInt()?:-1
                val y = call.parameters["y"]?.toInt()?:-1

                val box = projector.tileBBox(z, x, y)
                println(box)
                val bound = q.bind()
                        .setDouble(0, box[0])
                        .setDouble(1, box[1])
                        .setDouble(2, box[2])
                        .setDouble(3, box[3])

                var endLog = marker.startLogDuration("CQL statement execution - query={} z={} x={} y={}",
                        bound.preparedStatement().queryString, bound.getInt(0), bound.getInt(1), bound.getInt(2))
                val res = session.execute(bound)

                val features = res.map { row ->
                    Feature(
                            geometry = Geometry.fromWKT(row.getBytes(0).decodeString())!!
                    )

                }

                val geojson = projector.projectFeatures(GeoJSON(features = features))
                val tile = projector.transformTile(Tile(geojson, z, x, y))

                val z2 = 1 shl (if (z == 0) 0 else z)

                val k1 = 0.5 * buffer / extend
                val k3 = 1 + k1

                projector.calcBbox(tile.geojson)

                val clipper = Clipper()
                val clipped = clipper.clip(tile.geojson, z2.toDouble(), x - k1, x + k3, y - k1, y + k3)


                val encoder = Encoder()

                val encoded = encoder.encode(clipped.features, baseLayer)






                call.respondBytes(encoded.toByteArray())
            }

            static("/static") {
                resources("static")
            }

            install(StatusPages) {
                exception<OutOfMemoryError> {
                    call.respond(status = HttpStatusCode.InternalServerError, message = "Out of memory: reduce file/bulk size")
                }

                exception<JsonParsingException> {
                    call.respond(status = HttpStatusCode.InternalServerError, message = "Json Parsing Issue: Check file format")
                }

            }
        }
    }

    private fun initCassandra(clusterBuilder: Cluster.Builder): Boolean {
        val cluster = clusterBuilder.build()
        val session = cluster.connect()
        session.execute("CREATE  KEYSPACE IF NOT EXISTS geo " +
                "WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 };")
        session.execute("USE geo;")
        session.execute("CREATE TABLE IF NOT EXISTS features " +
                "(z int, x int, y int, id text, geometry blob, PRIMARY KEY (z, x, y, id));")
        session.close()
        cluster.close()
        return true
    }