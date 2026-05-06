# Laboratorio: Integración de Persistencia con MariaDB/MySQL en Vert.x 5

## Introducción
Hasta ahora, nuestra API utiliza estructuras de datos en memoria (`Maps`) para almacenar la información. Esto tiene un problema crítico: **si el servidor se detiene o reinicia, todos los datos se pierden.**

En este laboratorio, aprenderéis a integrar un motor de base de datos relacional (MariaDB o MySQL) utilizando el **Reactive MySQL Client** de Vert.x 5. Esto nos permitirá persistir la información de forma segura y eficiente.

---

## Prerrequisitos
1. Tener instalado **MariaDB** o **MySQL** en vuestro equipo.
2. Disponer de un cliente SQL (como HeidiSQL, DBeaver, o la consola de comandos).
3. Partir del proyecto actual con los modelos `Sensor` y `Actuator` ya implementados.

---

## Paso 1: Diseño y Creación de la Base de Datos

Lo primero es definir dónde guardaremos los datos. Debemos crear una base de datos y las tablas que correspondan a nuestros objetos Java.

### 1.1 Crear la Base de Datos
Ejecutad el siguiente comando en vuestro cliente SQL:
```sql
CREATE DATABASE iot_project;
USE iot_project;
```

### 1.2 Crear las Tablas
Debemos mapear los atributos de nuestras clases Java a tipos de datos SQL. Además, introduciremos el concepto de **Grupo (`idGroup`)**. 

> **El concepto de Grupo:** En un sistema IoT real, los dispositivos no están aislados. Un sensor de temperatura y un radiador (actuador) pueden pertenecer al mismo "Salón" (Grupo 1). Al incluir `idGroup` en ambas tablas, permitimos que el sistema sepa qué dispositivos están vinculados entre sí, facilitando futuras lógicas de automatización (ej. si el sensor del Grupo 1 baja de 20ºC, encender el actuador del Grupo 1).

> **¡Reto!** Pensad qué tipos de datos SQL son los más adecuados para cada campo (ej. `VARCHAR` para IDs, `BIGINT` para marcas de tiempo, `DOUBLE` para valores numéricos).

```sql
CREATE TABLE sensors (
    id INT AUTO_INCREMENT PRIMARY KEY, -- ID único del dato
    sensorId VARCHAR(50) NOT NULL,    -- ID del dispositivo físico (ej: "S1")
    timestamp BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    value DOUBLE NOT NULL,
    idGroup INT NOT NULL
);

CREATE TABLE actuators (
    id INT AUTO_INCREMENT PRIMARY KEY, -- ID único del dato
    actuatorId VARCHAR(50) NOT NULL,  -- ID del dispositivo físico (ej: "A1")
    type VARCHAR(50) NOT NULL,
    status DOUBLE NOT NULL,
    idGroup INT NOT NULL
);
```

---

## Paso 2: Configuración del Proyecto (Maven)

Para que Vert.x pueda hablar con MySQL, necesitamos añadir la librería cliente reactiva en el archivo `pom.xml`.

Buscad la sección `<dependencies>` y añadid lo siguiente:

```xml
<dependency>
    <groupId>io.vertx</groupId>
    <artifactId>vertx-mysql-client</artifactId>
    <version>5.0.11</version> <!-- Aseguraos de usar la versión compatible con Vert.x 5 -->
</dependency>
```

---

## Paso 3: Inicialización del Cliente SQL en el Verticle

En lugar de crear mapas, ahora crearemos un **Pool de conexiones**. Un Pool gestiona múltiples conexiones a la base de datos de forma eficiente.

### 3.1 Configuración de Conexión
Necesitaréis un objeto `MySQLConnectOptions` para definir:
- Host (normalmente `localhost`)
- Puerto (por defecto `3306`)
- Base de datos (`iot_project`)
- Usuario y Contraseña.

### 3.2 Creación del Pool
En Vert.x 5, se utiliza el patrón *Builder* para crear el cliente:

```java
// Ejemplo de estructura (No copiar literalmente, adaptad a vuestro código)
MySQLConnectOptions connectOptions = new MySQLConnectOptions()
    .setPort(3306)
    .setHost("127.0.0.1")
    .setDatabase("iot_project")
    .setUser("vuestro_usuario")
    .setPassword("vuestra_password");

PoolOptions poolOptions = new PoolOptions().setMaxSize(5);

// Crear el cliente (usad la interfaz Pool de io.vertx.sqlclient)
Pool client = MySQLBuilder
    .client()
    .with(poolOptions)
    .connectingTo(connectOptions)
    .using(vertx)
    .build();
```

Este `client` debe ser una variable de clase en vuestro `SensorApiVerticle` para que todos los manejadores puedan usarlo.

---

## Paso 4: Refactorización de las Operaciones CRUD

Ahora viene el cambio principal: sustituir el acceso a los `Maps` por consultas SQL asíncronas.

### 4.1 Recuperar todos los datos (GET)
Para obtener el listado completo, usaremos `client.query("SELECT * FROM ...").execute()`.
- El resultado es un `RowSet<Row>`.
- Debéis iterar sobre las filas y convertirlas de nuevo a vuestros objetos Java (`Sensor` o `Actuator`).

### 4.2 Recuperar por ID (GET con parámetro)
Para evitar ataques de inyección SQL, usaremos **Prepared Queries**:
`client.preparedQuery("SELECT * FROM sensors WHERE id = ?").execute(Tuple.of(id))`

### 4.3 Crear nuevos recursos (POST) y Retorno de ID
Cuando un cliente envía un JSON para crear un recurso, la base de datos es la encargada de generar el identificador único mediante `AUTO_INCREMENT`. El objetivo es que la API responda confirmando la creación y **devuelva el objeto completo**, incluyendo ese nuevo ID generado.

#### El flujo de trabajo:
1.  **Diferenciación de IDs:** Es vital no confundir el `id` con el `sensorId` (o `actuatorId`):
    -   `id`: Es la clave primaria, autoincremental y única para cada **entrada de datos** en la tabla.
    -   `sensorId/actuatorId`: Es el identificador del **dispositivo físico**. Un mismo `sensorId` puede aparecer en muchas filas, cada una con un `id` de dato diferente.
2.  **Inserción sin ID de dato:** En vuestra sentencia `INSERT`, omitid la columna `id`, pero incluid el `sensorId` y el `idGroup`.
    `INSERT INTO sensors (sensorId, timestamp, type, value, idGroup) VALUES (?, ?, ?, ?, ?)`
3.  **Ejecución:** Usad `client.preparedQuery(...).execute(Tuple.of(...))`.
4.  **Recuperar el ID del dato:** Podéis obtener el `id` autoincremental generado tras el insert:
    ```java
    Long generatedId = rows.property(MySQLClient.LAST_INSERTED_ID);
    ```
5.  **Actualizar y Responder:** Setead ese `id` en vuestro objeto y devolvedlo al cliente.

> **¿Por qué es importante?** Esta estructura permite tener un historial de valores para un mismo dispositivo físico (`sensorId`), manteniendo cada registro identificado de forma única por su `id`.

---

## Paso 5: Gestión de la Asincronía

Recordad que Vert.x es **no bloqueante**. Las operaciones de base de datos devuelven un `Future`. Nunca debéis esperar a que la base de datos termine de forma síncrona.

Esquema de un manejador reactivo:
1. Recibís la petición.
2. Lanzáis la consulta SQL.
3. Definís qué hacer en el `.onSuccess()` (enviar JSON con los datos) y en el `.onFailure()` (enviar un código de error 500).

---

## Paso 6: Verificación y Pruebas

1. **Reiniciar el servidor:** Detened y arrancad vuestra aplicación.
2. **Crear datos (POST):** Enviad peticiones POST con Postman.
3. **Comprobar la BBDD:** Verificad con vuestro cliente SQL que las filas aparecen en las tablas.
4. **Reiniciar el servidor de nuevo:** Comprobad que, al arrancar, las peticiones GET siguen devolviendo los datos anteriores. ¡Felicidades, ya tenéis persistencia real!

---

## Tareas Sugeridas para el Alumno
1. **Manejo de Errores:** ¿Qué ocurre si intentáis insertar un sensor con un ID que ya existe? Implementad lógica para detectar el error de clave duplicada y devolver un código HTTP adecuado.
2. **Validación de Datos:** Aseguraos de que los tipos de datos enviados en el JSON coinciden con los que espera la tabla (ej. no insertar texto en un campo `DOUBLE`).
3. **Cierre de Conexiones:** Investigad cómo cerrar el pool de conexiones cuando el Verticle se detenga (`stop` method).
