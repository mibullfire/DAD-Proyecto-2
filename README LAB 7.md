***

# Tareas de desarrollo de API REST para Dispositivos IoT

## Introducción
El objetivo de este ejercicio es diseñar e implementar la capa de dominio y la interfaz de red (API REST) para la gestión de dispositivos IoT dentro del ecosistema del proyecto. Se trabajará sobre dos entidades fundamentales en la adquisición y control de datos: **Sensores** y **Actuadores**.

Se exige un diseño estricto que respete los principios de la arquitectura REST y garantice la correcta serialización de la información.

---

## Tarea 1: Modelado de la Entidad `Sensor`

### Conceptos Arquitectónicos
* **Entidad de Dominio:** Representa un dispositivo pasivo cuya función es medir variables del entorno físico y transmitirlas al sistema.
* **Encapsulamiento:** El estado interno del objeto debe estar protegido, permitiendo su mutación únicamente a través de interfaces definidas.
* **Serialización:** El objeto debe ser fácilmente transformable a un formato de intercambio de datos (JSON) para su transmisión por la red.

### Tips de Implementación
* Definid una clase que actúe como un *Plain Old Java Object* (POJO).
* Estableced los atributos mínimos necesarios para definir inequívocamente la lectura de un sensor. Es fundamental incluir un identificador único, una marca temporal (*timestamp*), el tipo de magnitud física que mide (temperatura, humedad, presencia) y el valor numérico registrado.
* Garantizad la correcta implementación de constructores (incluyendo el constructor vacío por defecto requerido por las librerías de serialización), *getters* y *setters*.

---

## Tarea 2: Modelado de la Entidad `Actuador`

### Conceptos Arquitectónicos
* **Entidad de Dominio:** Representa un dispositivo activo capaz de ejecutar una acción sobre el entorno físico (encender una luz, mover un motor, abrir una válvula) basándose en las órdenes del sistema.
* **Gestión de Estado:** A diferencia del sensor, el actuador requiere persistir su estado operativo actual.

### Tips de Implementación
* Siguiendo el patrón de la Tarea 1, definid la clase correspondiente.
* Los atributos deben reflejar su naturaleza operativa: identificador único, tipo de dispositivo y, de manera crítica, una variable que represente su estado lógico actual (ej. encendido/apagado, nivel de apertura).
* Considerad el uso de enumerados (`Enum`) para acotar los tipos de dispositivos y evitar errores de tipado en la fase de ejecución.

---

## Tarea 3: Definición de Endpoints GET para `Sensor`

### Conceptos Arquitectónicos
* **Semántica REST:** El verbo HTTP `GET` es estricto; debe ser idempotente y seguro. Su única responsabilidad es la recuperación de información, sin alterar el estado del servidor.
* **Manejo de Colecciones vs. Recursos Únicos:** La topología de la URI define el alcance de la consulta.

### Tips de Implementación
* Proyectad dos rutas distintas:
    1.  Una ruta genérica para solicitar el listado completo de sensores disponibles.
    2.  Una ruta parametrizada para solicitar la información de un sensor específico mediante su identificador.
* Asegurad que la respuesta del servidor establece explícitamente el tipo de contenido (`Content-Type`) a `application/json`.
* Gestionad los códigos de estado HTTP con precisión: retornad un `200 OK` cuando la operación sea exitosa, y un `404 Not Found` cuando se consulte un identificador inexistente.

---

## Tarea 4: Definición de Endpoints GET para `Actuador`

### Conceptos Arquitectónicos
* Se aplican las mismas restricciones semánticas definidas en la Tarea 3. La interfaz de lectura debe ser simétrica para todas las entidades del sistema.

### Tips de Implementación
* Definid el enrutamiento para recuperar tanto el catálogo completo de actuadores como el estado individual de uno de ellos.
* Mantened la coherencia en la nomenclatura de las URIs con respecto a los sensores (ej. uso de sustantivos en plural para las colecciones).

---

## Tarea 5: Definición de Endpoints POST para `Sensor`

### Conceptos Arquitectónicos
* **Semántica REST:** El verbo HTTP `POST` se utiliza para la creación de nuevos recursos subordinados en el servidor. No es idempotente.
* **Procesamiento de Carga Útil (Payload):** El servidor debe ser capaz de interceptar, decodificar y validar el cuerpo de la petición enviada por el cliente.

### Tips de Implementación
* La URI receptora debe apuntar a la colección base.
* Es requisito indispensable habilitar el manejador correspondiente en el framework (ej. `BodyHandler` en Vert.x) antes de intentar leer el cuerpo de la petición, para asegurar que los datos asíncronos han sido ensamblados completamente.
* Implementad validación de entrada: no asumáis que el cliente envía un JSON bien formado. Si faltan campos críticos, el sistema debe rechazar la petición con un código `400 Bad Request`.
* Al crear el recurso exitosamente, responded con un código `201 Created` y devolved la representación en JSON del objeto recién instanciado.

---

## Tarea 6: Definición de Endpoints POST para `Actuador`

### Conceptos Arquitectónicos
* Aplicación de la lógica de creación de recursos definida en la tarea anterior, adaptada a la estructura de control del actuador.

### Tips de Implementación
* Asegurad que el JSON entrante puede ser mapeado correctamente a la clase `Actuador` diseñada en la Tarea 2.
* Si el identificador es generado por el servidor, aseguraos de inyectarlo en el objeto antes de enviarlo de vuelta en el cuerpo de la respuesta HTTP.

---

## Tarea 8: Gestión del Estado y Persistencia en Memoria

### Conceptos Arquitectónicos
* **Capa de Persistencia (Simulada):** Una API REST carece de estado (*stateless*) por definición; no obstante, el servidor necesita un mecanismo para retener los datos creados a través de las peticiones `POST` y servirlos en las peticiones `GET`. En ausencia de un motor de base de datos real, la memoria de la aplicación asumirá este rol.
* **Estructuras de Datos Eficientes:** La elección de la estructura de datos impacta directamente en la complejidad algorítmica de las operaciones de búsqueda. Un diccionario o mapa hash permite búsquedas por identificador con una complejidad temporal de $O(1)$, siendo la opción óptima para arquitecturas de red.

### Tips de Implementación
* Cread dos estructuras de datos a nivel de clase (dentro de vuestro *Verticle* o en una clase repositorio dedicada): una para almacenar los objetos `Sensor` y otra para los objetos `Actuador`.
* **Recomendación Principal:** Utilizad la interfaz `Map` (por ejemplo, un `HashMap`). La clave (*key*) debe ser el identificador único del dispositivo y el valor (*value*) será la instancia del objeto completo.
* **Integración con POST:** Cuando recibáis un `POST` válido, instanciad el objeto y guardadlo en el mapa correspondiente utilizando su identificador como clave.
* **Integración con GET:** * Para la consulta general (todos los dispositivos), extraed y devolved la colección de valores del mapa.
    * Para la consulta específica por ID, utilizad la clave para recuperar el objeto de forma directa. Si la clave no existe en el mapa, es el momento exacto para lanzar el error `404 Not Found`.
* **Alternativa Opcional:** Si lo preferís, podéis prescindir de los mapas y utilizar una colección secuencial como un `List` (ej. `ArrayList`). Tened en cuenta que, desde un punto de vista analítico, esta decisión implica que para buscar un dispositivo por su identificador tendréis que iterar sobre la lista elemento por elemento (complejidad $O(N)$). Ambas soluciones son válidas para los requisitos de este laboratorio.

---

## Tarea 7: Pruebas y Validación con Postman

### Conceptos Arquitectónicos
* **Auditoría de Interfaces:** Ningún desarrollo de backend está completo sin una validación empírica independiente del código cliente. Postman actúa como un agente externo para verificar el contrato de la API.

### Tips de Implementación
* Cread una Colección en Postman dedicada exclusivamente a este laboratorio para mantener el entorno de pruebas organizado.
* Configurad adecuadamente las cabeceras (`Headers`) en las peticiones de escritura (POST), declarando explícitamente `Content-Type: application/json`.
* Para las pruebas de creación, redactad los payloads en la pestaña *Body* seleccionando el formato *raw* -> *JSON*.
* Se exige probar los "caminos felices" (datos correctos y recursos existentes) así como forzar los errores (enviar JSON malformados, solicitar IDs erróneos) para comprobar la robustez del manejo de errores implementado.