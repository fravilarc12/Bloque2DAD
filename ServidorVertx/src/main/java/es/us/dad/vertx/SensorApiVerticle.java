package es.us.dad.vertx;

import es.us.dad.vertx.models.Actuator;
import es.us.dad.vertx.models.Sensor;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.mqtt.MqttClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SensorApiVerticle extends AbstractVerticle {

    // ── Persistencia en memoria ──────────────────────────────────────────────
    private final Map<String, Sensor>             sensors          = new HashMap<>();
    private final Map<String, Actuator>           actuators        = new HashMap<>();
    private final Map<String, List<JsonObject>>   telemetryHistory = new HashMap<>();

    private final Map<String, Double>  lastTempPerPark  = new HashMap<>();
    private final Map<String, String>  statusPerPark    = new HashMap<>();
    private final Map<String, Boolean> shutdownPerPark  = new HashMap<>();
    // ── Cliente MQTT ─────────────────────────────────────────────────────────
    private MqttClient mqttClient;

    // ── Configuración ────────────────────────────────────────────────────────
    private static final int    MQTT_PORT           = 1883;
    private static final String MQTT_HOST           = "localhost";
    private static final long   RECONNECT_DELAY_MS  = 5_000;
    private static final String TELEMETRY_WILDCARD  = "park/+/datacenter/+/temp";
    private static final String ACTUATOR_CMD_TOPIC  = "devices/actuators/%s/command";

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void start(Promise<Void> startPromise) {

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        // ── Health check ──────────────────────────────────────────────────────
        router.get("/api/health").handler(ctx ->
            ctx.response()
               .putHeader("content-type", "application/json")
               .end(new JsonObject().put("status", "ok").encode())
        );

        // ── GET /api/sensors ──────────────────────────────────────────────────
        router.get("/api/sensors").handler(ctx ->
            ctx.response()
               .putHeader("content-type", "application/json")
               .end(Json.encodePrettily(sensors.values()))
        );

        // ── GET /api/sensors/:id ──────────────────────────────────────────────
        router.get("/api/sensors/:id").handler(ctx -> {
            String id     = ctx.pathParam("id");
            Sensor sensor = sensors.get(id);
            if (sensor != null) {
                ctx.response()
                   .putHeader("content-type", "application/json")
                   .end(Json.encodePrettily(sensor));
            } else {
                ctx.response().setStatusCode(404)
                   .end(new JsonObject().put("error", "Sensor not found").encode());
            }
        });

        // ── POST /api/sensors ─────────────────────────────────────────────────
        router.post("/api/sensors").handler(ctx -> {
            try {
                JsonObject json = ctx.body().asJsonObject();
                if (json == null) {
                    ctx.response().setStatusCode(400)
                       .end(new JsonObject().put("error", "Empty body").encode());
                    return;
                }
                Sensor sensor = json.mapTo(Sensor.class);
                if (sensor.getId() == null || sensor.getType() == null) {
                    ctx.response().setStatusCode(400)
                       .end(new JsonObject().put("error", "Missing required fields").encode());
                    return;
                }
                sensors.put(sensor.getSensorId(), sensor);
                ctx.response().setStatusCode(201)
                   .putHeader("content-type", "application/json")
                   .end(Json.encodePrettily(sensor));
            } catch (Exception e) {
                ctx.response().setStatusCode(400)
                   .end(new JsonObject().put("error", "Invalid JSON: " + e.getMessage()).encode());
            }
        });

        // ── GET /api/actuators ────────────────────────────────────────────────
        router.get("/api/actuators").handler(ctx ->
            ctx.response()
               .putHeader("content-type", "application/json")
               .end(Json.encodePrettily(actuators.values()))
        );

        // ── GET /api/actuators/:id ────────────────────────────────────────────
        router.get("/api/actuators/:id").handler(ctx -> {
            String   id       = ctx.pathParam("id");
            Actuator actuator = actuators.get(id);
            if (actuator != null) {
                ctx.response()
                   .putHeader("content-type", "application/json")
                   .end(Json.encodePrettily(actuator));
            } else {
                ctx.response().setStatusCode(404)
                   .end(new JsonObject().put("error", "Actuator not found").encode());
            }
        });

        // ── POST /api/actuators ───────────────────────────────────────────────
        router.post("/api/actuators").handler(ctx -> {
            try {
                JsonObject json = ctx.body().asJsonObject();
                if (json == null) {
                    ctx.response().setStatusCode(400)
                       .end(new JsonObject().put("error", "Empty body").encode());
                    return;
                }
                Actuator actuator = json.mapTo(Actuator.class);
                if (actuator.getId() == null || actuator.getType() == null) {
                    ctx.response().setStatusCode(400)
                       .end(new JsonObject().put("error", "Missing required fields").encode());
                    return;
                }
                actuators.put(actuator.getActuatorId(), actuator);
                ctx.response().setStatusCode(201)
                   .putHeader("content-type", "application/json")
                   .end(Json.encodePrettily(actuator));
            } catch (Exception e) {
                ctx.response().setStatusCode(400)
                   .end(new JsonObject().put("error", "Invalid JSON: " + e.getMessage()).encode());
            }
        });

        // ── PUT /api/actuators/:id/command  (Paso 5: REST -> MQTT) ───────────
        //
        //  Recibe un JSON con el comando y lo publica en el topic
        //  devices/actuators/{id}/command via MQTT (QoS AT_LEAST_ONCE).
        //  Responde 202 si el broker está disponible, 503 si no lo está.
        // ─────────────────────────────────────────────────────────────────────
        router.put("/api/actuators/:id/command").handler(ctx -> {
            String actuatorId = ctx.pathParam("id");

            if (mqttClient == null || !mqttClient.isConnected()) {
                ctx.response().setStatusCode(503)
                   .putHeader("content-type", "application/json")
                   .end(new JsonObject().put("error", "MQTT Broker unavailable").encode());
                return;
            }

            try {
                JsonObject commandJson = ctx.body().asJsonObject();
                if (commandJson == null) {
                    ctx.response().setStatusCode(400)
                       .end(new JsonObject().put("error", "Empty body").encode());
                    return;
                }

                String topic = String.format(ACTUATOR_CMD_TOPIC, actuatorId);

                mqttClient.publish(
                    topic,
                    commandJson.toBuffer(),
                    io.netty.handler.codec.mqtt.MqttQoS.AT_LEAST_ONCE,
                    false,  // duplicate flag
                    false   // retain flag
                ).onSuccess(messageId -> {
                    System.out.printf("📤 Comando publicado en [%s]: %s%n", topic, commandJson.encode());
                    ctx.response().setStatusCode(202)
                       .putHeader("content-type", "application/json")
                       .end(new JsonObject()
                               .put("status",  "Command dispatched")
                               .put("topic",   topic)
                               .put("payload", commandJson)
                               .encode());
                }).onFailure(err -> {
                    System.err.println("❌ Error publicando comando MQTT: " + err.getMessage());
                    ctx.response().setStatusCode(500)
                       .end(new JsonObject().put("error", err.getMessage()).encode());
                });

            } catch (Exception e) {
                ctx.response().setStatusCode(400)
                   .end(new JsonObject().put("error", "Invalid JSON: " + e.getMessage()).encode());
            }
        });

        // ── GET /api/sensors/:id/telemetry  (historial de telemetría MQTT) ───
        router.get("/api/sensors/:id/telemetry").handler(ctx -> {
            String id = ctx.pathParam("id");
            List<JsonObject> history = telemetryHistory.getOrDefault(id, List.of());
            ctx.response()
               .putHeader("content-type", "application/json")
               .end(Json.encodePrettily(history));
        });
        // GET /api/v1/parks/:parkId/datacenter/health
        router.get("/api/v1/parks/:parkId/datacenter/health").handler(ctx -> {
            String parkId = ctx.pathParam("parkId");
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject()
                            .put("parkId",   parkId)
                            .put("status",   statusPerPark.getOrDefault(parkId, "OK"))
                            .put("lastTemp", lastTempPerPark.getOrDefault(parkId, 0.0))
                            .put("shutdown", shutdownPerPark.getOrDefault(parkId, false))
                            .encode());
        });

// POST /api/v1/parks/:parkId/datacenter/override
        router.post("/api/v1/parks/:parkId/datacenter/override").handler(ctx -> {
            String parkId   = ctx.pathParam("parkId");
            JsonObject body = ctx.body().asJsonObject();
            boolean cancel  = body != null && Boolean.TRUE.equals(body.getBoolean("cancel_shutdown"));

            if (cancel) {
                shutdownPerPark.put(parkId, false);
                statusPerPark.put(parkId, "OK");
                System.out.println("🔓 [" + parkId + "] Shutdown cancelado vía REST.");
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("status", "Shutdown cancelled").encode());
            } else {
                ctx.response().setStatusCode(400)
                        .end(new JsonObject().put("error", "cancel_shutdown must be true").encode());
            }
        });
        // ── Arrancar el servidor HTTP ─────────────────────────────────────────
        vertx.createHttpServer()
             .requestHandler(router)
             .listen(8080)
             .onComplete(http -> {
                 if (http.succeeded()) {
                     System.out.println("✅ API REST escuchando en el puerto 8080");
                     setupMqttClient();
                     startPromise.complete();
                 } else {
                     startPromise.fail(http.cause());
                 }
             });
    }

    //  Paso 3: Inicialización del cliente MQTT

    /**
     * Crea el cliente MQTT, registra el closeHandler para reconexión automática
     * y se conecta al broker.
     */
    private void setupMqttClient() {
        mqttClient = MqttClient.create(vertx);

        // Gestión de desconexiones (Tarea Sugerida):
        // Si se pierde la conexión con el broker, reintentamos automáticamente
        // usando vertx.setTimer() para no bloquear el event loop.
        mqttClient.closeHandler(v -> {
            System.err.println("⚠️  Conexión con el Broker MQTT perdida. "
                    + "Reintentando en " + RECONNECT_DELAY_MS / 1000 + "s...");
            vertx.setTimer(RECONNECT_DELAY_MS, id -> setupMqttClient());
        });

        mqttClient.connect(MQTT_PORT, MQTT_HOST)
            .onSuccess(connAck -> {
                System.out.println("✅ Cliente Vert.x conectado al Broker MQTT");
                setupMqttHandlers();
                mqttClient.subscribe(TELEMETRY_WILDCARD, 1)
                    .onSuccess(granted ->
                        System.out.println("📡 Servidor suscrito a: " + TELEMETRY_WILDCARD))
                    .onFailure(err ->
                        System.err.println("❌ Error suscribiéndose: " + err.getMessage()));
            })
            .onFailure(err -> {
                System.err.println("❌ Fallo conectando al Broker MQTT: " + err.getMessage()
                        + ". Reintentando en " + RECONNECT_DELAY_MS / 1000 + "s...");
                vertx.setTimer(RECONNECT_DELAY_MS, id -> setupMqttClient());
            });
    }

    //  Paso 4: Recepción de telemetría (ESP32 / MQTT Explorer -> Servidor)

    /**
     * Registra el handler que procesa cada mensaje MQTT entrante.
     *
     * Formato esperado del topic  : devices/sensors/{sensorId}/telemetry
     * Formato esperado del payload: {"value": 24.5, "type": "temperature"}
     */
    private void setupMqttHandlers() {
        mqttClient.publishHandler(message -> {
            String topic         = message.topicName();
            String payloadString = message.payload().toString();

            if (topic.startsWith("park/") && topic.endsWith("/temp")) {
                try {
                    // park/{parkId}/datacenter/{sensorId}/temp
                    String[] parts  = topic.split("/");
                    String parkId   = parts[1];
                    JsonObject data = new JsonObject(payloadString);
                    double temp     = data.getDouble("value");

                    System.out.println("📩 [" + parkId + "] Temperatura: " + temp + "°C");

                    lastTempPerPark.put(parkId, temp);

                    // Si está en SHUTDOWN bloqueamos
                    if (Boolean.TRUE.equals(shutdownPerPark.get(parkId))) {
                        System.err.println("🔒 [" + parkId + "] Sistema BLOQUEADO por fallo en cascada.");
                        return;
                    }

                    String actuatorTopic = "park/" + parkId + "/datacenter/RELAY_01/command";

                    if (temp > 25.0) {
                        // Solo actuar si no estaba ya en COOLING
                        if (!"COOLING".equals(statusPerPark.get(parkId))) {
                            statusPerPark.put(parkId, "COOLING");

                            // Encender relé
                            mqttClient.publish(actuatorTopic,
                                    new JsonObject().put("relay", "ON").toBuffer(),
                                    io.netty.handler.codec.mqtt.MqttQoS.AT_LEAST_ONCE,
                                    false, false);

                            System.out.println("🔥 [" + parkId + "] T > 25°C → Relé ON. Verificando en 5 mins...");

                            // Temporizador de verificación: 5 minutos
                            // Para pruebas cambia 300000 por 30000 (30 segundos)
                            vertx.setTimer(30000, id -> {
                                double currentTemp = lastTempPerPark.getOrDefault(parkId, 0.0);
                                if (currentTemp > 28.0) {
                                    statusPerPark.put(parkId, "CRITICAL");
                                    shutdownPerPark.put(parkId, true);
                                    mqttClient.publish(actuatorTopic,
                                            new JsonObject().put("relay", "SHUTDOWN").toBuffer(),
                                            io.netty.handler.codec.mqtt.MqttQoS.AT_LEAST_ONCE,
                                            false, false);
                                    System.err.println("🚨 [" + parkId + "] FALLO EN CASCADA. T=" + currentTemp + "°C → SHUTDOWN.");
                                } else {
                                    System.out.println("✅ [" + parkId + "] Verificación OK. T=" + currentTemp + "°C");
                                }
                            });
                        }

                    } else {
                        // Temperatura normal
                        if ("COOLING".equals(statusPerPark.get(parkId))) {
                            statusPerPark.put(parkId, "OK");
                            mqttClient.publish(actuatorTopic,
                                    new JsonObject().put("relay", "OFF").toBuffer(),
                                    io.netty.handler.codec.mqtt.MqttQoS.AT_LEAST_ONCE,
                                    false, false);
                            System.out.println("❄️ [" + parkId + "] T normal → Relé OFF.");
                        }
                        statusPerPark.putIfAbsent(parkId, "OK");
                    }

                } catch (Exception e) {
                    System.err.println("⚠️ Error: " + e.getMessage());
                }
            }
        });
    }
}
