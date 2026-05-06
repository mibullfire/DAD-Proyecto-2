# Guía de Pruebas — Módulo 1.7: Índice de Calor Computado

## Requisitos previos
- **Mosquitto** instalado como servicio de Windows
- **MariaDB** corriendo con la base de datos `iot_project` y las 4 tablas creadas
- **MQTT Explorer** instalado y conectado a `localhost:1883`
- **Postman** instalado
- **Vert.x** compilado y listo para arrancar

---

## Paso 1 — Arrancar los tres servicios

Abre **tres terminales distintas**:

**Terminal 1 — Mosquitto:**
```powershell
net start mosquitto
```

**Terminal 2 — MariaDB:**
Verificar en HeidiSQL que la conexión funciona y que existen las tablas:
```sql
USE iot_project;
SHOW TABLES;
-- Debe mostrar: actuators, heat_index_records, office_alarms, sensors
```

**Terminal 3 — Vert.x:**
```powershell
cd "C:\Users\mibul\Desktop\Proyecto DAD 2\Proyecto backend"
mvn exec:java
```

**Resultado esperado en la consola de Vert.x:**
```
✅ API REST escuchando en el puerto 8080
✅ Cliente MQTT conectado al broker
📡 Suscrito a home/+/office/+/ambient
```

Si no aparecen las tres líneas, no continúes — hay un problema de conexión.

---

## Paso 2 — Prueba con Heat Index ALTO (extractor se enciende)

### 2.1 Publicar en MQTT Explorer
- **Topic:** `home/casa1/office/sensor1/ambient`
- **Payload:**
```json
{"temperature": 35.0, "humidity": 80.0}
```
- **QoS:** 1
- Pulsar **Publish**

### 2.2 Verificar en la consola de Vert.x
```
📩 MQTT [home/casa1/office/sensor1/ambient]: {"temperature":35.0,"humidity":80.0}
🌡️  [casa1/sensor1] T=35.0°C H=80.0% → HI=52.63°C
🔥 [casa1] Índice de Calor 52.63 > 32 → Encendiendo extractor
📤 Comando enviado [home/casa1/office/extractor/command]: {"state":"ON"}
```

### 2.3 Verificar en MQTT Explorer
Suscríbete al topic `home/casa1/office/extractor/command`.
Debe llegar el mensaje:
```json
{"state": "ON"}
```

### 2.4 Verificar en HeidiSQL
```sql
SELECT * FROM heat_index_records ORDER BY id DESC LIMIT 5;
SELECT * FROM office_alarms ORDER BY id DESC LIMIT 5;
```
Deben aparecer filas nuevas en **ambas** tablas.

---

## Paso 3 — Prueba con Heat Index BAJO (extractor se apaga)

### 3.1 Publicar en MQTT Explorer
- **Topic:** `home/casa1/office/sensor1/ambient`
- **Payload:**
```json
{"temperature": 20.0, "humidity": 40.0}
```

### 3.2 Verificar en la consola de Vert.x
```
📩 MQTT [home/casa1/office/sensor1/ambient]: {"temperature":20.0,"humidity":40.0}
🌡️  [casa1/sensor1] T=20.0°C H=40.0% → HI=17.6°C
✅ [casa1] Índice de Calor 17.6 ≤ 32 → Extractor apagado
📤 Comando enviado [home/casa1/office/extractor/command]: {"state":"OFF"}
```

### 3.3 Verificar en MQTT Explorer
Debe llegar en `home/casa1/office/extractor/command`:
```json
{"state": "OFF"}
```

### 3.4 Verificar en HeidiSQL
```sql
SELECT * FROM heat_index_records ORDER BY id DESC LIMIT 5;
SELECT * FROM office_alarms ORDER BY id DESC LIMIT 5;
```
Solo debe haber fila nueva en `heat_index_records`. En `office_alarms` **no** debe aparecer nada nuevo.

---

## Paso 4 — Prueba multi-tenancy (dos viviendas simultáneas)

### 4.1 Publicar dos mensajes seguidos en MQTT Explorer

**Mensaje 1** (vivienda con calor):
- Topic: `home/casa1/office/sensor1/ambient`
- Payload: `{"temperature": 35.0, "humidity": 80.0}`

**Mensaje 2** (vivienda sin calor):
- Topic: `home/casa2/office/sensor1/ambient`
- Payload: `{"temperature": 20.0, "humidity": 40.0}`

### 4.2 Verificar comandos independientes en MQTT Explorer
- `home/casa1/office/extractor/command` → `{"state": "ON"}`
- `home/casa2/office/extractor/command` → `{"state": "OFF"}`

### 4.3 Verificar en HeidiSQL que los homeId están separados
```sql
SELECT * FROM heat_index_records ORDER BY id DESC LIMIT 10;
SELECT * FROM office_alarms ORDER BY id DESC LIMIT 10;
```

---

## Paso 5 — Prueba de los endpoints REST con Postman

### 5.1 GET — Último índice de calor (dato existente)
```
GET http://localhost:8080/api/v1/homes/casa1/office/sensor1/heat_index
```
**Respuesta esperada `200 OK`:**
```json
{
  "homeId": "casa1",
  "sensorId": "sensor1",
  "temperature": 35.0,
  "humidity": 80.0,
  "heat_index": 52.63,
  "timestamp": 1746443200000
}
```

### 5.2 GET — Sensor inexistente (debe dar 404)
```
GET http://localhost:8080/api/v1/homes/casaFalsa/office/sensor99/heat_index
```
**Respuesta esperada `404 Not Found`:**
```json
{
  "error": "No hay lecturas para homeId=casaFalsa sensorId=sensor99"
}
```

### 5.3 DELETE — Limpiar alarmas
```
DELETE http://localhost:8080/api/v1/homes/casa1/office/alarms
```
**Respuesta esperada: `204 No Content`** (sin cuerpo).

En la consola de Vert.x aparece:
```
🗑️  Alarmas eliminadas para homeId=casa1 (N filas)
```

Verificar en HeidiSQL que `office_alarms` quedó vacía para `casa1`:
```sql
SELECT * FROM office_alarms WHERE homeId = 'casa1';
-- Debe devolver 0 filas
```

### 5.4 DELETE — Alarmas de vivienda sin alarmas (debe dar 204 igualmente)
```
DELETE http://localhost:8080/api/v1/homes/casaSinAlarmas/office/alarms
```
**Respuesta esperada: `204 No Content`**

---

## Paso 6 — Prueba de reconexión automática MQTT

### 6.1 Simular caída del broker
```powershell
net stop mosquitto
```

**En la consola de Vert.x debe aparecer:**
```
⚠️ Conexión MQTT perdida. Reintentando en 5s...
```

### 6.2 Restaurar el broker
```powershell
net start mosquitto
```

**En unos segundos, Vert.x debe reconectarse automáticamente:**
```
✅ Cliente MQTT conectado al broker
📡 Suscrito a home/+/office/+/ambient
```

### 6.3 Verificar que vuelve a funcionar
Publica de nuevo en MQTT Explorer y comprueba que el mensaje se procesa con normalidad.

---

## Valores de referencia del Índice de Calor

| Temperatura | Humedad | Heat Index | Resultado |
|---|---|---|---|
| 35.0°C | 80.0% | ~52.6°C | Extractor **ON** |
| 30.0°C | 60.0% | ~33.4°C | Extractor **ON** |
| 28.0°C | 70.0% | ~29.9°C | Extractor **OFF** |
| 25.0°C | 50.0% | ~24.8°C | Extractor **OFF** |
| 20.0°C | 40.0% | ~17.6°C | Extractor **OFF** |

---

## Checklist final

- [ ] Consola muestra las 3 líneas de arranque
- [ ] Heat Index alto → `{"state":"ON"}` llega al topic del extractor
- [ ] Heat Index bajo → `{"state":"OFF"}` llega al topic del extractor
- [ ] `heat_index_records` se rellena en cada mensaje recibido
- [ ] `office_alarms` solo se rellena cuando HI > 32
- [ ] GET REST devuelve el último índice calculado
- [ ] GET REST devuelve 404 para sensores sin datos
- [ ] DELETE REST borra las alarmas y devuelve 204
- [ ] Multi-tenancy: casa1 y casa2 se controlan de forma independiente
- [ ] Reconexión automática al broker funciona
