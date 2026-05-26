/*
 * ESP32 #1 — Nodo Sensor (DHT11)
 * Módulo 1.7: Índice de Calor Computado (Despacho)
 *
 * Librerías necesarias (instalar desde el gestor de librerías de Arduino IDE):
 *   - PubSubClient  (Nick O'Leary)
 *   - DHT sensor library (Adafruit)
 *   - Adafruit Unified Sensor (Adafruit)
 *
 * Conexión DHT11:
 *   VCC  → 3.3V
 *   GND  → GND
 *   DATA → GPIO4  +  resistencia 10kΩ entre DATA y 3.3V
 */

#include <Arduino.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <DHT.h>

// ── Configuración — MODIFICA ESTOS VALORES ────────────────────────────────────
const char* WIFI_SSID     = "iPhone de Mibu";
const char* WIFI_PASSWORD = "xdxdxdxd";

const char* MQTT_BROKER   = "172.20.10.3"; // IP del PC en la red del hotspot
const int   MQTT_PORT     = 1883;

const char* HOME_ID       = "casa1";
const char* SENSOR_ID     = "sensor1";

#define DHT_PIN  4
#define DHT_TYPE DHT11

// Intervalo de publicación en milisegundos (10 segundos)
const unsigned long INTERVALO_MS = 10000;
// ─────────────────────────────────────────────────────────────────────────────

DHT dht(DHT_PIN, DHT_TYPE);
WiFiClient   wifiClient;
PubSubClient mqttClient(wifiClient);

char topicSensor[100];
unsigned long ultimaPublicacion = 0;

void conectarWifi() {
    Serial.print("Conectando a WiFi: ");
    Serial.println(WIFI_SSID);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }
    Serial.println("\n✅ WiFi conectado. IP: " + WiFi.localIP().toString());
}

void conectarMqtt() {
    mqttClient.setServer(MQTT_BROKER, MQTT_PORT);
    while (!mqttClient.connected()) {
        Serial.println("Conectando al broker MQTT...");
        String clientId = "ESP32Sensor_" + String(HOME_ID);
        if (mqttClient.connect(clientId.c_str())) {
            Serial.println("✅ MQTT conectado");
        } else {
            Serial.print("❌ Fallo MQTT, rc=");
            Serial.println(mqttClient.state());
            delay(3000);
        }
    }
}

void setup() {
    Serial.begin(115200);
    dht.begin();

    // Construir el topic una sola vez
    snprintf(topicSensor, sizeof(topicSensor),
             "home/%s/office/%s/ambient", HOME_ID, SENSOR_ID);

    conectarWifi();
    conectarMqtt();

    Serial.print("📡 Publicando en: ");
    Serial.println(topicSensor);
}

void loop() {
    // Mantener conexión MQTT activa
    if (!mqttClient.connected()) conectarMqtt();
    mqttClient.loop();

    // Publicar cada INTERVALO_MS milisegundos
    unsigned long ahora = millis();
    if (ahora - ultimaPublicacion >= INTERVALO_MS) {
        ultimaPublicacion = ahora;

        float temperatura = dht.readTemperature();
        float humedad     = dht.readHumidity();

        if (isnan(temperatura) || isnan(humedad)) {
            Serial.println("⚠️ Error leyendo el DHT11");
            return;
        }

        // Construir payload JSON
        char payload[80];
        snprintf(payload, sizeof(payload),
                 "{\"temperature\": %.1f, \"humidity\": %.1f}",
                 temperatura, humedad);

        mqttClient.publish(topicSensor, payload);

        Serial.print("📤 Publicado: ");
        Serial.println(payload);
    }
}
