#include <Arduino.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <DHT.h>
#include <ArduinoJson.h>

// ── CONFIGURACIÓN ─────────────────────────────────────
const char* WIFI_SSID     = "oppo";
const char* WIFI_PASSWORD = "pacopaco12";
const char* MQTT_BROKER   = "10.145.16.203";
// ──────────────────────────────────────────────────────

const int   MQTT_PORT  = 1883;
const char* PARK_ID    = "PARK_01";
const char* SENSOR_ID  = "TEMP_01";
const char* ACTUATOR_ID = "RELAY_01";
const char* CLIENT_ID  = "ESP32_Node01";

#define DHTPIN   4
#define DHTTYPE  DHT11
#define RELAY_PIN 26

DHT          dht(DHTPIN, DHTTYPE);
WiFiClient   wifiClient;
PubSubClient mqtt(wifiClient);

// ── Callback: mensajes MQTT entrantes (comandos al relé) ──────────────────
void onMessage(char* topic, byte* payload, unsigned int length) {
    String msg = "";
    for (int i = 0; i < length; i++) msg += (char)payload[i];

    Serial.println("📩 Comando recibido: " + msg);

    StaticJsonDocument<128> doc;
    deserializeJson(doc, msg);
    const char* relay = doc["relay"];

    if (strcmp(relay, "ON") == 0) {
        digitalWrite(RELAY_PIN, HIGH);
        Serial.println("✅ Relé ENCENDIDO");
    } else if (strcmp(relay, "OFF") == 0 || strcmp(relay, "SHUTDOWN") == 0) {
        digitalWrite(RELAY_PIN, LOW);
        Serial.println("🔴 Relé APAGADO");
    }
}

void connectWifi() {
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    Serial.print("Conectando WiFi");
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }
    Serial.println("\n✅ WiFi conectado: " + WiFi.localIP().toString());
}

void connectMqtt() {
    String cmdTopic = "park/" + String(PARK_ID) + "/datacenter/" + String(ACTUATOR_ID) + "/command";
    while (!mqtt.connected()) {
        Serial.print("Conectando MQTT...");
        if (mqtt.connect(CLIENT_ID)) {
            mqtt.subscribe(cmdTopic.c_str());
            Serial.println("✅ MQTT conectado y suscrito a: " + cmdTopic);
        } else {
            Serial.println("❌ Fallo, reintentando en 5s");
            delay(5000);
        }
    }
}

void setup() {
    delay(1000);  
    Serial.begin(115200);
    delay(500);   // 
    Serial.println("🚀 Arrancando...");
    pinMode(RELAY_PIN, OUTPUT);
    digitalWrite(RELAY_PIN, LOW);
    dht.begin();
    connectWifi();
    mqtt.setServer(MQTT_BROKER, MQTT_PORT);
    mqtt.setCallback(onMessage);
    connectMqtt();
}

void loop() {
    if (!mqtt.connected()) connectMqtt();
    mqtt.loop();

    float temp = dht.readTemperature();

    if (!isnan(temp)) {
        String topic   = "park/" + String(PARK_ID) + "/datacenter/" + String(SENSOR_ID) + "/temp";
        String payload = "{\"value\": " + String(temp, 1) + "}";
        mqtt.publish(topic.c_str(), payload.c_str());
        Serial.println("📤 Temperatura publicada: " + payload);
    } else {
        Serial.println("⚠️ Error leyendo DHT11, comprueba la conexión");
    }

    delay(10000); // Publicar cada 10 segundos
}