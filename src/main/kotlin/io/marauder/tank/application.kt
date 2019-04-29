package io.marauder.tank

import com.datastax.driver.core.Cluster
import com.datastax.driver.core.policies.RoundRobinPolicy
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
import io.marauder.supercharged.Clipper
import io.marauder.supercharged.Encoder
import io.marauder.supercharged.Projector
import io.marauder.supercharged.models.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.json.JSON
import kotlinx.serialization.json.JsonParsingException
import kotlinx.serialization.parse
import org.slf4j.LoggerFactory
import com.datastax.driver.core.ConsistencyLevel
import com.datastax.driver.core.LocalDate
import com.datastax.driver.core.QueryOptions
import com.google.gson.Gson


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
        val dbDatacenter = environment.config.propertyOrNull("ktor.application.db_datacenter")?.getString() ?: "datacenter1"
        val dbStrategy = environment.config.propertyOrNull("ktor.application.db_strategy")?.getString() ?: "SimpleStrategy"
        val dbKeyspace = environment.config.propertyOrNull("ktor.application.db_keyspace")?.getString() ?: "geo"
        val dbTable = environment.config.propertyOrNull("ktor.application.db_table")?.getString() ?: "features"
        val dbReplFactor = environment.config.propertyOrNull("ktor.application.replication_factor")?.getString()?.toInt() ?: 1

        val qo = QueryOptions().setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
        val clusterBuilder = Cluster.builder().apply {
            dbHosts.forEach {
                addContactPoint(it)
            }
            withLoadBalancingPolicy(RoundRobinPolicy())
            withQueryOptions(qo)
        }

        var isConnected = false
        var attempts = 10
        while (!isConnected && attempts >= 0) {
            try {
                initCassandra(clusterBuilder, dbStrategy, dbReplFactor, dbKeyspace, dbTable, dbDatacenter)
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
        val tiler = Tyler(session, minZoom, maxZoom, extend, buffer, dbTable)
        val projector = Projector()

//        val q = session.prepare("SELECT geometry, id FROM features WHERE z=? AND x=? AND y=?;")

        val query = """SELECT geometry, vector_id, img_date, variety_code, crop_descr FROM $dbTable WHERE img_date = ? AND expr(geo_idx, ?);""".trimMargin()

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

            post("/{layer?}") {
                val importLayer = call.parameters["layer"] ?: ""
                if (baseLayer == "" && importLayer == "") {
                    call.respondText("Import layer must not be an empty string", status = HttpStatusCode.BadRequest)
                } else {
                    val layer = "$baseLayer${if (baseLayer != "" && importLayer != "") "." else ""}$importLayer"
                    if (call.parameters["geojson"] == "true") {
                        val input = JSON.plain.parse<GeoJSON>(call.receiveText())
                        GlobalScope.launch {
                            tiler.tiler(projector.projectFeatures(input))
                        }
                    } else {
                        val features = mutableListOf<Feature>()
                        call.receiveStream().bufferedReader().useLines { lines ->
                            lines.forEach { features.add(JSON.plain.parse(it)) }
                        }
                        val geojson = GeoJSON(features = features)
                        GlobalScope.launch {
                            val neu = projector.projectFeatures(geojson)
                            tiler.tiler(neu)
                        }
                    }

                    call.respondText("Features Accepted", contentType = ContentType.Text.Plain, status = HttpStatusCode.Accepted)
                }
            }

            get("/tile/{z}/{x}/{y}") {

                var endLog = marker.startLogDuration("prepare query")
                val z = call.parameters["z"]?.toInt()?:-1
                val x = call.parameters["x"]?.toInt()?:-1
                val y = call.parameters["y"]?.toInt()?:-1

                val gson = Gson()
                val filters = gson.fromJson<Map<String,Any>>(call.parameters["filter"]?:"{}", Map::class.java)

                val img_date = (filters["img_date"] ?: "2016-08-05").toString().split('-')
                log.info(call.parameters["filter"])

                val box = projector.tileBBox(z, x, y)

                val poly = Geometry.Polygon(coordinates = listOf(listOf(
                        listOf(box[0], box[1]),
                        listOf(box[2], box[1]),
                        listOf(box[2], box[3]),
                        listOf(box[0], box[3]),
                        listOf(box[0], box[1])
                )))

                val jsonQuery = """
                    {
                        filter: {
                         type: "geo_shape",
                         field: "geometry",
                         operation: "intersects",
                         shape: {
                            type: "wkt",
                            value: "${projector.projectFeature(Feature(geometry = poly)).geometry.toWKT()}"
                         }
                        }
                    }
                """.trimIndent()

//                println(jsonQuery)

                val bound = q.bind()
                        .setString(1, jsonQuery)
                        .setDate(0, LocalDate.fromYearMonthDay(img_date[0].toInt(), img_date[1].toInt(), img_date[2].toInt()))

                endLog()

                endLog = marker.startLogDuration("CQL statement execution")
                val res = session.execute(bound)
                endLog()


                endLog = marker.startLogDuration("fetch features")
                val features = res.map { row ->
//                    println(row.getString(1))
                    Feature(
                            geometry = Geometry.fromWKT(row.getString(0))!!,
                            properties = mapOf(
                                    "vector_id" to Value.IntValue(row.getInt(1).toLong()),
                                    "img_date" to Value.StringValue(row.getDate(2).toString()),
                                    "variety_code" to Value.IntValue(row.getInt(3).toLong()),
                                    "crop_descr" to Value.StringValue(row.getString(4))
                            ),
                            id = row.getInt(1).toString()
                    )

                }
                endLog()
                endLog = marker.startLogDuration("prepare features for encoding")
                val geojson = GeoJSON(features = features)
//                val tile = projector.transformTile(Tile(geojson, z, x, y))

                val z2 = 1 shl (if (z == 0) 0 else z)

                val k1 = 0.5 * buffer / extend
                val k3 = 1 + k1

                projector.calcBbox(geojson)

                val clipper = Clipper()
                val clipped = clipper.clip(geojson, z2.toDouble(), x - k1, x + k3, y - k1, y + k3)
                val tile = projector.transformTile(Tile(clipped, (1 shl z), x, y))

                val encoder = Encoder()

                endLog()
                endLog = marker.startLogDuration("encode and transmit")
                val encoded = encoder.encode(tile.geojson.features, baseLayer)

                call.respondBytes(encoded.toByteArray())
                endLog()
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

    private fun initCassandra(clusterBuilder: Cluster.Builder, strategy: String, replication: Int, keyspace: String, table: String, datacenter: String): Boolean {
        val cluster = clusterBuilder.build()
        val session = cluster.connect()
        if (strategy == "SimpleStrategy") {
            session.execute("CREATE  KEYSPACE IF NOT EXISTS $keyspace " +
                    "WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : $replication };")
        } else {
            session.execute("CREATE  KEYSPACE IF NOT EXISTS $keyspace " +
                    "WITH REPLICATION = {'class' : 'NetworkTopologyStrategy', '$datacenter' : $replication};")
        }

        session.execute("USE $keyspace;")
        session.execute("CREATE TABLE IF NOT EXISTS $keyspace.$table (img_date date, vector_id int, variet_code int, crop_descr text, geometry text, PRIMARY KEY (img_date, vector_id));")
        session.execute("CREATE CUSTOM INDEX IF NOT EXISTS geo_idx ON $keyspace.$table (geometry) USING 'com.stratio.cassandra.lucene.Index' WITH OPTIONS = {'refresh_seconds': '1', 'schema': '{fields: { geometry: {type: \"geo_shape\", max_levels: 3, transformations: [{type: \"bbox\"}]}}}'}")
        session.close()
        cluster.close()
        return true
    }