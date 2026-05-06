package es.us.dad.vertx;

import es.us.dad.vertx.models.ActuatorType;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.mqtt.MqttClient;
import io.vertx.mysqlclient.MySQLBuilder;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;

public class SensorApiVerticle extends AbstractVerticle {

    private Pool client;
    private MqttClient mqttClient;

    @Override
    public void start(Promise<Void> startPromise) {

        // ── Base de datos ─────────────────────────────────────────────────────
        MySQLConnectOptions connectOptions = new MySQLConnectOptions()
                .setPort(3306)
                .setHost("127.0.0.1")
                .setDatabase("iot_project")
                .setUser("root")         // <-- cambia si tu usuario es distinto
                .setPassword("contraseña"); // <-- pon tu contraseña aquí

        PoolOptions poolOptions = new PoolOptions().setMaxSize(5);

        client = MySQLBuilder.pool()
                .with(poolOptions)
                .connectingTo(connectOptions)
                .using(vertx)
                .build();

        // ── Cliente MQTT ──────────────────────────────────────────────────────
        setupMqttClient();

        // ── Router HTTP ───────────────────────────────────────────────────────
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        // Health check
        router.get("/api/health").handler(ctx ->
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("status", "ok").encode())
        );

        // ── GET /api/sensors ──────────────────────────────────────────────────
        router.get("/api/sensors").handler(ctx ->
                client.query("SELECT * FROM sensors").execute()
                        .onSuccess(rows -> {
                            var list = new JsonArray();
                            rows.forEach(row -> list.add(new JsonObject()
                                    .put("id", row.getInteger("id"))
                                    .put("sensorId", row.getString("sensorId"))
                                    .put("timestamp", row.getLong("timestamp"))
                                    .put("type", row.getString("type"))
                                    .put("value", row.getDouble("value"))
                                    .put("idGroup", row.getInteger("idGroup"))));
                            ctx.response()
                                    .putHeader("content-type", "application/json")
                                    .end(list.encode());
                        })
                        .onFailure(err -> ctx.response().setStatusCode(500)
                                .end(new JsonObject().put("error", err.getMessage()).encode()))
        );

        // ── GET /api/sensors/:id ──────────────────────────────────────────────
        router.get("/api/sensors/:id").handler(ctx -> {
            int id;
            try { id = Integer.parseInt(ctx.pathParam("id")); }
            catch (NumberFormatException e) {
                ctx.response().setStatusCode(400).end(new JsonObject().put("error", "id must be integer").encode());
                return;
            }
            client.preparedQuery("SELECT * FROM sensors WHERE id = ?").execute(Tuple.of(id))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            ctx.response().setStatusCode(404)
                                    .end(new JsonObject().put("error", "Sensor not found").encode());
                        } else {
                            var row = rows.iterator().next();
                            ctx.response()
                                    .putHeader("content-type", "application/json")
                                    .end(new JsonObject()
                                            .put("id", row.getInteger("id"))
                                            .put("sensorId", row.getString("sensorId"))
                                            .put("timestamp", row.getLong("timestamp"))
                                            .put("type", row.getString("type"))
                                            .put("value", row.getDouble("value"))
                                            .put("idGroup", row.getInteger("idGroup"))
                                            .encode());
                        }
                    })
                    .onFailure(err -> ctx.response().setStatusCode(500)
                            .end(new JsonObject().put("error", err.getMessage()).encode()));
        });

        // ── GET /api/actuators ────────────────────────────────────────────────
        router.get("/api/actuators").handler(ctx ->
                client.query("SELECT * FROM actuators").execute()
                        .onSuccess(rows -> {
                            var list = new JsonArray();
                            rows.forEach(row -> list.add(new JsonObject()
                                    .put("id", row.getInteger("id"))
                                    .put("actuatorId", row.getString("actuatorId"))
                                    .put("type", row.getString("type"))
                                    .put("status", row.getDouble("status"))
                                    .put("idGroup", row.getInteger("idGroup"))));
                            ctx.response()
                                    .putHeader("content-type", "application/json")
                                    .end(list.encode());
                        })
                        .onFailure(err -> ctx.response().setStatusCode(500)
                                .end(new JsonObject().put("error", err.getMessage()).encode()))
        );

        // ── GET /api/actuators/:id ────────────────────────────────────────────
        router.get("/api/actuators/:id").handler(ctx -> {
            int id;
            try { id = Integer.parseInt(ctx.pathParam("id")); }
            catch (NumberFormatException e) {
                ctx.response().setStatusCode(400).end(new JsonObject().put("error", "id must be integer").encode());
                return;
            }
            client.preparedQuery("SELECT * FROM actuators WHERE id = ?").execute(Tuple.of(id))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            ctx.response().setStatusCode(404)
                                    .end(new JsonObject().put("error", "Actuator not found").encode());
                        } else {
                            var row = rows.iterator().next();
                            ctx.response()
                                    .putHeader("content-type", "application/json")
                                    .end(new JsonObject()
                                            .put("id", row.getInteger("id"))
                                            .put("actuatorId", row.getString("actuatorId"))
                                            .put("type", row.getString("type"))
                                            .put("status", row.getDouble("status"))
                                            .put("idGroup", row.getInteger("idGroup"))
                                            .encode());
                        }
                    })
                    .onFailure(err -> ctx.response().setStatusCode(500)
                            .end(new JsonObject().put("error", err.getMessage()).encode()));
        });

        // ── POST /api/sensors ─────────────────────────────────────────────────
        router.post("/api/sensors").handler(ctx -> {
            JsonObject json = ctx.body().asJsonObject();
            if (json == null) {
                ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Empty body").encode());
                return;
            }
            String sensorId = json.getString("sensorId");
            String type     = json.getString("type");
            Double value    = json.getDouble("value");
            Long   ts       = json.getLong("timestamp", System.currentTimeMillis());
            Integer idGroup = json.getInteger("idGroup");

            if (sensorId == null || type == null || value == null || idGroup == null) {
                ctx.response().setStatusCode(400)
                        .end(new JsonObject().put("error", "Missing required fields: sensorId, type, value, idGroup").encode());
                return;
            }

            client.preparedQuery(
                    "INSERT INTO sensors (sensorId, timestamp, type, value, idGroup) VALUES (?, ?, ?, ?, ?)"
            ).execute(Tuple.of(sensorId, ts, type, value, idGroup))
                    .onSuccess(rows -> {
                        long generatedId = rows.property(io.vertx.mysqlclient.MySQLClient.LAST_INSERTED_ID);
                        ctx.response()
                                .setStatusCode(201)
                                .putHeader("content-type", "application/json")
                                .end(new JsonObject()
                                        .put("id", generatedId)
                                        .put("sensorId", sensorId)
                                        .put("timestamp", ts)
                                        .put("type", type)
                                        .put("value", value)
                                        .put("idGroup", idGroup)
                                        .encode());
                    })
                    .onFailure(err -> ctx.response().setStatusCode(500)
                            .end(new JsonObject().put("error", err.getMessage()).encode()));
        });

        // ── POST /api/actuators ───────────────────────────────────────────────
        router.post("/api/actuators").handler(ctx -> {
            JsonObject json = ctx.body().asJsonObject();
            if (json == null) {
                ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Empty body").encode());
                return;
            }
            String actuatorId = json.getString("actuatorId");
            String typeStr    = json.getString("type");
            Double status     = json.getDouble("status");
            Integer idGroup   = json.getInteger("idGroup");

            if (actuatorId == null || typeStr == null || status == null || idGroup == null) {
                ctx.response().setStatusCode(400)
                        .end(new JsonObject().put("error", "Missing required fields: actuatorId, type, status, idGroup").encode());
                return;
            }

            try { ActuatorType.valueOf(typeStr); }
            catch (IllegalArgumentException e) {
                ctx.response().setStatusCode(400)
                        .end(new JsonObject().put("error", "Invalid actuator type: " + typeStr).encode());
                return;
            }

            client.preparedQuery(
                    "INSERT INTO actuators (actuatorId, type, status, idGroup) VALUES (?, ?, ?, ?)"
            ).execute(Tuple.of(actuatorId, typeStr, status, idGroup))
                    .onSuccess(rows -> {
                        long generatedId = rows.property(io.vertx.mysqlclient.MySQLClient.LAST_INSERTED_ID);
                        ctx.response()
                                .setStatusCode(201)
                                .putHeader("content-type", "application/json")
                                .end(new JsonObject()
                                        .put("id", generatedId)
                                        .put("actuatorId", actuatorId)
                                        .put("type", typeStr)
                                        .put("status", status)
                                        .put("idGroup", idGroup)
                                        .encode());
                    })
                    .onFailure(err -> ctx.response().setStatusCode(500)
                            .end(new JsonObject().put("error", err.getMessage()).encode()));
        });

        // ── GET /api/v1/homes/:homeId/office/:sensorId/heat_index ────────────────
        router.get("/api/v1/homes/:homeId/office/:sensorId/heat_index").handler(ctx -> {
            String homeId   = ctx.pathParam("homeId");
            String sensorId = ctx.pathParam("sensorId");

            client.preparedQuery(
                    "SELECT * FROM heat_index_records WHERE homeId = ? AND sensorId = ? ORDER BY timestamp DESC LIMIT 1"
            ).execute(Tuple.of(homeId, sensorId))
                    .onSuccess(rows -> {
                        if (rows.size() == 0) {
                            ctx.response().setStatusCode(404)
                                    .end(new JsonObject()
                                            .put("error", "No hay lecturas para homeId=" + homeId + " sensorId=" + sensorId)
                                            .encode());
                        } else {
                            var row = rows.iterator().next();
                            ctx.response()
                                    .putHeader("content-type", "application/json")
                                    .end(new JsonObject()
                                            .put("homeId", row.getString("homeId"))
                                            .put("sensorId", row.getString("sensorId"))
                                            .put("temperature", row.getDouble("temperature"))
                                            .put("humidity", row.getDouble("humidity"))
                                            .put("heat_index", row.getDouble("heat_index"))
                                            .put("timestamp", row.getLong("timestamp"))
                                            .encode());
                        }
                    })
                    .onFailure(err -> ctx.response().setStatusCode(500)
                            .end(new JsonObject().put("error", err.getMessage()).encode()));
        });

        // ── DELETE /api/v1/homes/:homeId/office/alarms ────────────────────────
        router.delete("/api/v1/homes/:homeId/office/alarms").handler(ctx -> {
            String homeId = ctx.pathParam("homeId");

            client.preparedQuery("DELETE FROM office_alarms WHERE homeId = ?")
                    .execute(Tuple.of(homeId))
                    .onSuccess(rows -> {
                        System.out.println("🗑️  Alarmas eliminadas para homeId=" + homeId + " (" + rows.rowCount() + " filas)");
                        ctx.response().setStatusCode(204).end();
                    })
                    .onFailure(err -> ctx.response().setStatusCode(500)
                            .end(new JsonObject().put("error", err.getMessage()).encode()));
        });

        // ── Servidor HTTP ─────────────────────────────────────────────────────
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(8080).onComplete(http -> {
                    if (http.succeeded()) {
                        System.out.println("✅ API REST escuchando en el puerto 8080");
                        startPromise.complete();
                    } else {
                        startPromise.fail(http.cause());
                    }
                });
    }

    // ── MQTT ──────────────────────────────────────────────────────────────────

    private void setupMqttClient() {
        mqttClient = MqttClient.create(vertx);

        mqttClient.connect(1883, "localhost")
                .onSuccess(connAck -> {
                    System.out.println("✅ Cliente MQTT conectado al broker");
                    setupMqttHandlers();
                    mqttClient.subscribe("home/+/office/+/ambient", 1)
                            .onSuccess(granted -> System.out.println("📡 Suscrito a home/+/office/+/ambient"))
                            .onFailure(err -> System.err.println("❌ Error al suscribirse: " + err.getMessage()));
                })
                .onFailure(err -> {
                    System.err.println("❌ No se pudo conectar al broker MQTT: " + err.getMessage());
                    // Reintentar en 5 segundos
                    vertx.setTimer(5000, id -> setupMqttClient());
                });

        mqttClient.closeHandler(v -> {
            System.err.println("⚠️ Conexión MQTT perdida. Reintentando en 5s...");
            vertx.setTimer(5000, id -> setupMqttClient());
        });
    }

    private void setupMqttHandlers() {
        mqttClient.publishHandler(message -> {
            String topic   = message.topicName();
            String payload = message.payload().toString();

            System.out.println("📩 MQTT [" + topic + "]: " + payload);

            // topic: home/{homeId}/office/{sensorId}/ambient
            String[] parts = topic.split("/");
            if (parts.length != 5) return;

            String homeId   = parts[1];
            String sensorId = parts[3];

            try {
                JsonObject data = new JsonObject(payload);
                Double temperature = data.getDouble("temperature");
                Double humidity    = data.getDouble("humidity");

                if (temperature == null || humidity == null) {
                    System.err.println("⚠️ Payload sin temperature o humidity: " + payload);
                    return;
                }

                double heatIndex = calculateHeatIndex(temperature, humidity);
                long   timestamp = System.currentTimeMillis();

                System.out.printf("🌡️  [%s/%s] T=%.1f°C H=%.1f%% → HI=%.2f°C%n",
                        homeId, sensorId, temperature, humidity, heatIndex);

                // Guardar lectura en heat_index_records
                client.preparedQuery(
                        "INSERT INTO heat_index_records (homeId, sensorId, temperature, humidity, heat_index, timestamp) VALUES (?, ?, ?, ?, ?, ?)"
                ).execute(Tuple.of(homeId, sensorId, temperature, humidity, heatIndex, timestamp))
                        .onFailure(err -> System.err.println("❌ Error guardando heat_index_record: " + err.getMessage()));

                // ActuatorId por convención: "extractor" por vivienda
                String actuatorId = "extractor";

                if (heatIndex > 32.0) {
                    System.out.printf("🔥 [%s] Índice de Calor %.2f > 32 → Encendiendo extractor%n", homeId, heatIndex);

                    // Guardar alarma
                    String msg = String.format("HI=%.2f supera umbral 32°C en sensor %s", heatIndex, sensorId);
                    client.preparedQuery(
                            "INSERT INTO office_alarms (homeId, sensorId, heat_index, timestamp, message) VALUES (?, ?, ?, ?, ?)"
                    ).execute(Tuple.of(homeId, sensorId, heatIndex, timestamp, msg))
                            .onFailure(err -> System.err.println("❌ Error guardando alarma: " + err.getMessage()));

                    publishCommand(homeId, actuatorId, "ON");
                } else {
                    System.out.printf("✅ [%s] Índice de Calor %.2f ≤ 32 → Extractor apagado%n", homeId, heatIndex);
                    publishCommand(homeId, actuatorId, "OFF");
                }

            } catch (Exception e) {
                System.err.println("⚠️ Payload JSON malformado: " + e.getMessage());
            }
        });
    }

    // ── Fórmula del Índice de Calor (Rothfusz, en Celsius) ───────────────────

    private double calculateHeatIndex(double T, double H) {
        return -8.78469475556
                + 1.61139411   * T
                + 2.33854883889 * H
                - 0.14611605   * T * H
                - 0.01230809   * T * T
                - 0.01642482   * H * H
                + 0.00221173   * T * T * H
                + 0.00072546   * T * H * H
                - 0.000003582  * T * T * H * H;
    }

    // ── Publicar comando al actuador ──────────────────────────────────────────

    private void publishCommand(String homeId, String actuatorId, String state) {
        if (mqttClient == null || !mqttClient.isConnected()) {
            System.err.println("⚠️ MQTT no disponible, no se puede publicar comando");
            return;
        }
        String topic   = "home/" + homeId + "/office/" + actuatorId + "/command";
        String payload = new JsonObject().put("state", state).encode();

        mqttClient.publish(topic, io.vertx.core.buffer.Buffer.buffer(payload),
                MqttQoS.AT_LEAST_ONCE, false, false)
                .onSuccess(id -> System.out.println("📤 Comando enviado [" + topic + "]: " + payload))
                .onFailure(err -> System.err.println("❌ Error publicando comando: " + err.getMessage()));
    }

    @Override
    public void stop() {
        if (mqttClient != null) mqttClient.disconnect();
        if (client != null) client.close();
    }
}
