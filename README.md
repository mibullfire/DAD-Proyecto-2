# Proyecto DAD 2 — Módulo 1.7: Índice de Calor Computado (Despacho)

## Descripción del módulo

Sistema de control climático para un despacho domótico. El servidor Vert.x recibe telemetría de temperatura y humedad desde un sensor DHT11 (ESP32) vía MQTT, calcula el **Índice de Calor** con la ecuación de Rothfusz, y si el valor supera 32°C activa automáticamente el extractor vía MQTT. Soporta múltiples viviendas simultáneas (multi-tenancy) usando `homeId` como clave de aislamiento.

### Topics MQTT
- **Sensor (entrante):** `home/{homeId}/office/{sensorId}/ambient`
- **Actuador (saliente):** `home/{homeId}/office/{actuatorId}/command`

### Endpoints REST
- `GET  /api/v1/homes/:homeId/office/:sensorId/heat_index` — último índice de calor calculado
- `DELETE /api/v1/homes/:homeId/office/alarms` — limpia las alarmas de esa vivienda

### Fórmula del Índice de Calor (Rothfusz, en Celsius)
```
HI = -8.78 + 1.611*T + 2.338*H - 0.146*T*H
     - 0.0123*T² - 0.0164*H²
     + 0.00221*T²*H + 0.000725*T*H²
     - 0.00000358*T²*H²
```
donde T = temperatura (°C) y H = humedad relativa (%).

---

## Arquitectura del proyecto

```
Proyecto backend/
├── pom.xml                          # Dependencias Maven
├── conf/
│   ├── node1.json                   # Config nodo 1 (puerto P2P 6000)
│   └── node2.json                   # Config nodo 2 (puerto P2P 6001)
└── src/main/java/es/us/dad/vertx/
    ├── BituscoinLauncher.java        # Launcher principal
    ├── SensorApiVerticle.java        # Verticle principal (REST + MQTT + lógica)
    └── models/
        ├── Sensor.java
        ├── Actuator.java
        └── ActuatorType.java
```

---

## Trabajos realizados

---

### Trabajo 1 — Dependencias Maven (`pom.xml`)

**Estado: COMPLETADO**

Se añadieron dos dependencias al `pom.xml`. Como el proyecto usa `vertx-stack-depchain` como BOM, no hace falta especificar versión (se hereda de `vertx.version = 5.0.7`).

```xml
<dependency>
    <groupId>io.vertx</groupId>
    <artifactId>vertx-mqtt</artifactId>
</dependency>
<dependency>
    <groupId>io.vertx</groupId>
    <artifactId>vertx-mysql-client</artifactId>
</dependency>
```

---

### Trabajo 2 — Base de datos MariaDB + integración MySQL en el código

**Estado: COMPLETADO**

#### 2A — Crear las tablas en HeidiSQL

Conectar a MariaDB local y ejecutar el siguiente SQL:

```sql
CREATE DATABASE IF NOT EXISTS iot_project;
USE iot_project;

CREATE TABLE IF NOT EXISTS sensors (
    id        INT AUTO_INCREMENT PRIMARY KEY,
    sensorId  VARCHAR(50) NOT NULL,
    timestamp BIGINT      NOT NULL,
    type      VARCHAR(50) NOT NULL,
    value     DOUBLE      NOT NULL,
    idGroup   INT         NOT NULL
);

CREATE TABLE IF NOT EXISTS actuators (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    actuatorId  VARCHAR(50) NOT NULL,
    type        VARCHAR(50) NOT NULL,
    status      DOUBLE      NOT NULL,
    idGroup     INT         NOT NULL
);

CREATE TABLE IF NOT EXISTS heat_index_records (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    homeId      VARCHAR(50) NOT NULL,
    sensorId    VARCHAR(50) NOT NULL,
    temperature DOUBLE      NOT NULL,
    humidity    DOUBLE      NOT NULL,
    heat_index  DOUBLE      NOT NULL,
    timestamp   BIGINT      NOT NULL,
    INDEX idx_home_sensor (homeId, sensorId)
);

CREATE TABLE IF NOT EXISTS office_alarms (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    homeId      VARCHAR(50) NOT NULL,
    sensorId    VARCHAR(50) NOT NULL,
    heat_index  DOUBLE      NOT NULL,
    timestamp   BIGINT      NOT NULL,
    message     VARCHAR(255)
);
```

#### 2B — Integración MySQL en `SensorApiVerticle.java`

Se eliminaron los `HashMap` en memoria y se sustituyeron por un pool de conexiones reactivo.

Clase usada: `MySQLBuilder.pool()` de `io.vertx.mysqlclient`.

```java
MySQLConnectOptions connectOptions = new MySQLConnectOptions()
        .setPort(3306)
        .setHost("127.0.0.1")
        .setDatabase("iot_project")
        .setUser("root")        // cambiar si es necesario
        .setPassword("...");    // poner la contraseña de MariaDB

PoolOptions poolOptions = new PoolOptions().setMaxSize(5);

client = MySQLBuilder.pool()
        .with(poolOptions)
        .connectingTo(connectOptions)
        .using(vertx)
        .build();
```

> **Nota:** `.pool()` devuelve un `Pool` (múltiples conexiones). `.client()` devuelve una conexión única — error que tuvimos y corregimos.

Todos los endpoints GET y POST ahora usan `client.query(...)` y `client.preparedQuery(...)` de forma reactiva (`.onSuccess()` / `.onFailure()`). Los POST devuelven el `id` autoincremental con `MySQLClient.LAST_INSERTED_ID`.

---

### Trabajo 3 — Cliente MQTT (LAB 9)

**Estado: COMPLETADO**

#### 3A — Arrancar el broker Mosquitto

Crear el archivo `mosquitto.conf` en cualquier carpeta con este contenido:
```conf
listener 1883 0.0.0.0
allow_anonymous true
```

Arrancar con Docker (sustituir la ruta):
```bash
docker run -it -p 1883:1883 -v C:/ruta/mosquitto.conf:/mosquitto/config/mosquitto.conf eclipse-mosquitto
```

Debe aparecer en la consola: `Opening ipv4 listen socket on port 1883`

#### 3B — Lo que se implementó en `SensorApiVerticle.java`

- Variable de clase `private MqttClient mqttClient`
- Método `setupMqttClient()`: conecta a `localhost:1883`. Si falla o se pierde la conexión, reintenta automáticamente cada 5 segundos con `vertx.setTimer()`.
- Suscripción al topic `home/+/office/+/ambient` (el `+` captura cualquier `homeId` y `sensorId`).
- Método `setupMqttHandlers()`: parsea el topic para extraer `homeId` y `sensorId`, y deserializa el JSON del payload buscando los campos `temperature` y `humidity`.
- Método `publishCommand(homeId, actuatorId, state)`: publica `{"state": "ON/OFF"}` al topic `home/{homeId}/office/{actuatorId}/command`.
- `stop()` desconecta el cliente MQTT limpiamente.

#### 3C — Verificar que funciona con MQTT Explorer

1. Abrir MQTT Explorer y conectar a `localhost:1883`
2. Arrancar el servidor Vert.x — debe aparecer en consola:
   ```
   ✅ Cliente MQTT conectado al broker
   📡 Suscrito a home/+/office/+/ambient
   ```
3. En MQTT Explorer, publicar en el topic `home/casa1/office/sensor1/ambient`:
   ```json
   {"temperature": 35.0, "humidity": 80.0}
   ```
4. En la consola de Vert.x debe aparecer:
   ```
   📩 MQTT [home/casa1/office/sensor1/ambient]: {"temperature":35.0,"humidity":80.0}
   🌡️  [casa1/sensor1] T=35.0°C H=80.0%
   ```

---

## Trabajos pendientes

---

### Trabajo 4 — Lógica del Módulo 1.7 (el núcleo)

**Estado: COMPLETADO**

Implementado dentro de `setupMqttHandlers()` en `SensorApiVerticle.java`:

1. **Cálculo del Índice de Calor** con método `calculateHeatIndex(T, H)` usando la fórmula de Rothfusz en Celsius.
2. **Persistencia** del resultado en `heat_index_records` (MySQL) con cada lectura recibida.
3. **Si `heatIndex > 32.0`:**
   - Guarda una fila en `office_alarms` con el mensaje descriptivo.
   - Publica `{"state": "ON"}` al topic `home/{homeId}/office/extractor/command`.
4. **Si `heatIndex <= 32.0`:**
   - Publica `{"state": "OFF"}`.

**Convención del actuador:** el `actuatorId` del extractor es siempre `"extractor"`, por lo que el topic de comando queda `home/{homeId}/office/extractor/command`. Esto garantiza el aislamiento multi-tenancy: cada `homeId` controla su propio extractor de forma independiente.

---

### Trabajo 5 — Endpoints REST del Módulo 1.7

**Estado: COMPLETADO**

**`GET /api/v1/homes/:homeId/office/:sensorId/heat_index`**
Consulta la última fila de `heat_index_records` para ese `homeId` + `sensorId` ordenando por `timestamp DESC LIMIT 1`.
- `200 OK` con JSON: `homeId`, `sensorId`, `temperature`, `humidity`, `heat_index`, `timestamp`
- `404 Not Found` si no hay lecturas todavía para esa combinación

**`DELETE /api/v1/homes/:homeId/office/alarms`**
Borra todas las filas de `office_alarms` para ese `homeId`.
- `204 No Content` siempre que la query no falle (aunque no hubiera alarmas)

---

### Trabajo 6 — Pruebas con MQTT Explorer y Postman

**Estado: COMPLETADO**

Todos los pasos detallados están documentados en **`PRUEBAS.md`**. Incluye:
- Arranque de los tres servicios (Mosquitto, MariaDB, Vert.x)
- Prueba con Heat Index alto (HI > 32 → extractor ON)
- Prueba con Heat Index bajo (HI ≤ 32 → extractor OFF)
- Prueba multi-tenancy (casa1 y casa2 independientes)
- Prueba de los dos endpoints REST con Postman (GET y DELETE)
- Prueba de reconexión automática MQTT
- Checklist final y tabla de valores de referencia
