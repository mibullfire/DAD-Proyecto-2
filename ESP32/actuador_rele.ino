/*
 * ESP32 #2 — Nodo Actuador (Relé)
 * Módulo 1.7: Índice de Calor Computado (Despacho)
 *
 * Librerías necesarias (instalar desde el gestor de librerías de Arduino IDE):
 *   - PubSubClient  (Nick O'Leary)
 *   - ArduinoJson   (Benoit Blanchon)
 *
 * Conexión módulo relé:
 *   VCC → 5V  (o 3.3V según el módulo)
 *   GND → GND
 *   IN  → GPIO5
 *
 * La mayoría de módulos relé son activos en LOW:
 *   LOW  → relé cerrado (extractor ON)
 *   HIGH → relé abierto (extractor OFF)
 * Si tu módulo es activo en HIGH, cambia RELAY_ON y RELAY_OFF abajo.
 */

#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>

// ── Configuración — MODIFICA ESTOS VALORES ────────────────────────────────────
const char* WIFI_SSID     = "NombreDeTuRed";
const char* WIFI_PASSWORD = "ContraseñaWifi";

const char* MQTT_BROKER   = "192.168.1.100"; // IP del PC donde corre Mosquitto
const int   MQTT_PORT     = 1883;

const char* HOME_ID       = "casa1";
const char* ACTUATOR_ID   = "extractor";

#define RELAY_PIN  5
#define RELAY_ON   LOW   // Cambiar a HIGH si el módulo es activo en HIGH
#define RELAY_OFF  HIGH
// ─────────────────────────────────────────────────────────────────────────────

WiFiClient   wifiClient;
PubSubClient mqttClient(wifiClient);

char topicComando[100];

void activarExtractor() {
    digitalWrite(RELAY_PIN, RELAY_ON);
    Serial.println("🔴 Extractor ENCENDIDO");
}

void apagarExtractor() {
    digitalWrite(RELAY_PIN, RELAY_OFF);
    Serial.println("⚫ Extractor APAGADO");
}

void callbackMqtt(char* topic, byte* payload, unsigned int length) {
    // Construir el string del payload recibido
    char mensaje[length + 1];
    memcpy(mensaje, payload, length);
    mensaje[length] = '\0';

    Serial.print("📩 Comando recibido [");
    Serial.print(topic);
    Serial.print("]: ");
    Serial.println(mensaje);

    // Parsear el JSON
    StaticJsonDocument<64> doc;
    DeserializationError error = deserializeJson(doc, mensaje);
    if (error) {
        Serial.println("⚠️ JSON malformado");
        return;
    }

    const char* state = doc["state"];
    if (state == nullptr) {
        Serial.println("⚠️ Campo 'state' no encontrado");
        return;
    }

    if (strcmp(state, "ON") == 0) {
        activarExtractor();
    } else if (strcmp(state, "OFF") == 0) {
        apagarExtractor();
    } else {
        Serial.print("⚠️ Estado desconocido: ");
        Serial.println(state);
    }
}

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
    mqttClient.setCallback(callbackMqtt);

    while (!mqttClient.connected()) {
        Serial.println("Conectando al broker MQTT...");
        String clientId = "ESP32Actuador_" + String(HOME_ID);
        if (mqttClient.connect(clientId.c_str())) {
            Serial.println("✅ MQTT conectado");
            // Suscribirse al topic de comandos
            mqttClient.subscribe(topicComando);
            Serial.print("📡 Suscrito a: ");
            Serial.println(topicComando);
        } else {
            Serial.print("❌ Fallo MQTT, rc=");
            Serial.println(mqttClient.state());
            delay(3000);
        }
    }
}

void setup() {
    Serial.begin(115200);
    pinMode(RELAY_PIN, OUTPUT);
    apagarExtractor(); // Estado inicial: apagado

    // Construir el topic una sola vez
    snprintf(topicComando, sizeof(topicComando),
             "home/%s/office/%s/command", HOME_ID, ACTUATOR_ID);

    conectarWifi();
    conectarMqtt();
}

void loop() {
    // Mantener conexión MQTT activa y reconectar si se pierde
    if (!mqttClient.connected()) {
        Serial.println("⚠️ MQTT desconectado. Reconectando...");
        conectarMqtt();
    }
    mqttClient.loop();
}
