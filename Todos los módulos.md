# Catálogo de Proyectos - Bloque II: Integración IoT Escalable (REST + MQTT)

## 🌐 Requisito Arquitectónico Global: Escalabilidad (Multi-Tenancy)
Todos los proyectos deben diseñarse para soportar múltiples instalaciones simultáneas (ej. controlar 50 viviendas, 5 parques o 3 centrales nucleares a la vez desde el mismo servidor Vert.x). 
*   **Gestión de Estado:** La lógica avanzada (temporizadores, derivadas, histéresis) debe calcularse y almacenarse en memoria **por cada instalación individual**. No se puede usar una única variable global `temperaturaActual`; se debe usar un `Map<String, Double>` cuya clave sea, por ejemplo, `homeId + "_" + sensorId`. La misma lógica aplica a la estructura de la base de datos, por lo que cada sensor o actuador deberá llevar asociado el identificador del grupo al que pertenece (vivienda, reactor, atracción...) y el identificador propio del sensor.
*   **Hardware:** 1 ESP32 actuará como Nodo Sensor (DHT11) y 1 ESP32 actuará como Nodo Actuador (Relé).

---

## Temática 1: Vivienda Domotizada (Smart Home)

### Módulo 1.1: Climatización con Histéresis Multizona
*   **Justificación Analítica:** En termodinámica y control de maquinaria, encender y apagar un compresor por fluctuaciones mínimas destruye el hardware y genera picos de consumo eléctrico. La banda muerta (histéresis) garantiza la eficiencia mecánica.
*   **Descripción de Alto Nivel:** El algoritmo de control en Vert.x mantendrá un estado aislado por `homeId`. Se registrará la transición de estado y se aplicará un bloqueo temporal mediante temporizadores para impedir reencendidos inmediatos.
*   **MQTT Sensor:** `home/{homeId}/living/{sensorId}/ambient`
*   **MQTT Actuador:** `home/{homeId}/living/{actuatorId}/command`
*   **Lógica Vert.x (Histéresis AISLADA):** Objetivo 24°C. Encender si T > 25°C. Apagar si T < 23°C. Temporizador de protección de 2 mins.
*   **API REST:**
    *   `GET /api/v1/homes/:homeId/living/ac/:actuatorId/cycles`
    *   `PUT /api/v1/homes/:homeId/living/ac/:actuatorId/target`
        *   **Cuerpo de la petición (Payload):** JSON que contenga un campo numérico indicando la nueva temperatura objetivo en grados Celsius (ej. `{"target_temperature": 25.5}`).

### Módulo 1.2: Extractor de Baño por Derivada
*   **Justificación Analítica:** Un umbral estático de humedad falla con los cambios estacionales. Medir la velocidad de cambio (la derivada) aísla el evento real (la ducha) del ruido ambiental.
*   **Descripción de Alto Nivel:** Diseño de un búfer circular en memoria para almacenar lecturas temporizadas. El algoritmo calculará el diferencial de humedad respecto a los últimos 30 segundos, activando el relé si la pendiente supera el límite.
*   **MQTT Sensor:** `home/{homeId}/bathroom/{sensorId}/humidity`
*   **MQTT Actuador:** `home/{homeId}/bathroom/{actuatorId}/command`
*   **Lógica Vert.x:** Si la humedad sube > 10% en 30s, encender extractor. Apagar al estabilizarse la media móvil de 2 mins.
*   **API REST:**
    *   `GET /api/v1/homes/:homeId/bathroom/baseline`
    *   `POST /api/v1/homes/:homeId/bathroom/:actuatorId/boost`
        *   **Cuerpo de la petición (Payload):** JSON que especifique un valor entero correspondiente a los minutos que el extractor debe funcionar al máximo de su capacidad (ej. `{"duration_minutes": 15}`).

### Módulo 1.3: Riego por Déficit de Presión de Vapor (DPV)
*   **Justificación Analítica:** La transpiración de las plantas no depende linealmente de la humedad, sino de la presión de vapor real del aire. Evaluar ambas variables de forma conjunta determina la necesidad real de riego.
*   **Descripción de Alto Nivel:** Función matemática que cruce las variables del DHT11 para calcular el DPV. La actuación inyectará agua mediante pulsos del relé, implementando un periodo refractario en Vert.x para permitir la absorción.
*   **MQTT Sensor:** `home/{homeId}/greenhouse/{sensorId}/ambient`
*   **MQTT Actuador:** `home/{homeId}/greenhouse/{actuatorId}/command`
*   **Lógica Vert.x:** Calcular DPV. Si > 1.2 kPa, activar bomba 5s y bloquear comprobaciones 5 minutos.
*   **API REST:**
    *   `GET /api/v1/homes/:homeId/greenhouse/:sensorId/dpv_history`
    *   `DELETE /api/v1/homes/:homeId/greenhouse/:actuatorId/lock`

### Módulo 1.4: Media Móvil Anti-Heladas (Sótano)
*   **Justificación Analítica:** Los sensores físicos sufren interferencias electromagnéticas o miden corrientes de aire transitorias. El filtrado de paso bajo elimina este ruido.
*   **Descripción de Alto Nivel:** Cola FIFO (First-In-First-Out) que almacene las últimas N lecturas. El servidor publicará el comando MQTT de encendido si la media aritmética del conjunto cae por debajo del umbral crítico.
*   **MQTT Sensor:** `home/{homeId}/basement/{sensorId}/ambient`
*   **MQTT Actuador:** `home/{homeId}/basement/{actuatorId}/command`
*   **Lógica Vert.x:** Búfer de 10 lecturas. Activar calefactor solo si el promedio < 4°C.
*   **API REST:**
    *   `GET /api/v1/homes/:homeId/basement/frost_risk`
    *   `PUT /api/v1/homes/:homeId/basement/buffer_size`
        *   **Cuerpo de la petición (Payload):** JSON con un valor entero que defina la cantidad exacta de lecturas que conformarán la ventana de la media móvil (ej. `{"size": 15}`).

### Módulo 1.5: Deshumidificador por Meseta (Lavadero)
*   **Justificación Analítica:** La estabilización de la curva de secado (pendiente cero o meseta) es el único indicador matemático fiable del fin de la evaporación de las prendas.
*   **Descripción de Alto Nivel:** Se comparará el valor actual con un valor guardado X minutos en el pasado. Si la diferencia es inferior al margen de error del DHT11, se deduce una asíntota y se envía la orden de apagado.
*   **MQTT Sensor:** `home/{homeId}/laundry/{sensorId}/ambient`
*   **MQTT Actuador:** `home/{homeId}/laundry/{actuatorId}/command`
*   **Lógica Vert.x:** Si T actual - T(hace 3 mins) < 1%, la ropa está seca. Apagar relé.
*   **API REST:**
    *   `GET /api/v1/homes/:homeId/laundry/:actuatorId/progress`
    *   `POST /api/v1/homes/:homeId/laundry/:actuatorId/start`
        *   **Cuerpo de la petición (Payload):** JSON booleano que actúe como bandera (flag) para forzar el inicio del ciclo ignorando la humedad ambiental de base (ej. `{"force_start": true}`).

### Módulo 1.6: Alarma de Gradiente Térmico (Bodega)
*   **Justificación Analítica:** La conservación bioquímica tolera variaciones lentas, pero un choque térmico abrupto destruye el producto. El control debe centrarse en la aceleración térmica.
*   **Descripción de Alto Nivel:** Calcular la tasa de cambio en grados por hora, extrapolando lecturas de alta frecuencia a proyecciones horarias mediante el control estricto de las marcas temporales.
*   **MQTT Sensor:** `home/{homeId}/winecellar/{sensorId}/ambient`
*   **MQTT Actuador:** `home/{homeId}/winecellar/{actuatorId}/command`
*   **Lógica Vert.x:** Activar enfriador si T sube a un ritmo > 1.5°C/hora.
*   **API REST:**
    *   `GET /api/v1/homes/:homeId/winecellar/stability`
    *   `PUT /api/v1/homes/:homeId/winecellar/gradient_limit`
        *   **Cuerpo de la petición (Payload):** JSON con un valor numérico de punto flotante que establezca la tasa máxima de grados por hora permitida (ej. `{"max_gradient": 2.0}`).

### Módulo 1.7: Índice de Calor Computado (Despacho)
*   **Justificación Analítica:** La capacidad de disipación térmica decrece exponencialmente con la alta humedad. El control de refrigeración debe basarse en un índice compuesto.
*   **Descripción de Alto Nivel:** Traducción de fórmulas psicrométricas. El Verticle interceptará ambas magnitudes del JSON de telemetría, aplicará la ecuación del Índice de Calor e inferirá la activación del extractor basándose en este valor virtual.
*   **MQTT Sensor:** `home/{homeId}/office/{sensorId}/ambient`
*   **MQTT Actuador:** `home/{homeId}/office/{actuatorId}/command`
*   **Lógica Vert.x:** Si Índice > 32, encender extractor específico.
*   **API REST:**
    *   `GET /api/v1/homes/:homeId/office/:sensorId/heat_index`
    *   `DELETE /api/v1/homes/:homeId/office/alarms`

### Módulo 1.8: Máquinas de Estado Conyugales (Sauna)
*   **Justificación Analítica:** El uso de lógica condicional plana para controlar procesos secuenciales produce código inestable. Un autómata finito formaliza las transiciones de las máquinas termodinámicas.
*   **Descripción de Alto Nivel:** Programación de un autómata finito (FSM). El servidor alterará el comportamiento de evaluación del DHT11 dependiendo del estado actual del sistema (Precalentamiento ignora límites menores, Mantenimiento impone bandas ajustadas).
*   **MQTT Sensor:** `home/{homeId}/sauna/{sensorId}/ambient`
*   **MQTT Actuador:** `home/{homeId}/sauna/{actuatorId}/command`
*   **Lógica Vert.x:** Instancia de State Machine (APAGADO, PRECALENTANDO, LISTO, MANTENIMIENTO) por `homeId`.
*   **API REST:**
    *   `GET /api/v1/homes/:homeId/sauna/state`
    *   `POST /api/v1/homes/:homeId/sauna/abort`
        *   **Cuerpo de la petición (Payload):** JSON que contenga una cadena de texto (String) documentando la justificación del apagado forzoso para su registro en auditoría (ej. `{"reason": "Usuario canceló reserva"}`).

### Módulo 1.9: PWM Simulado Asíncrono (Rack de Red)
*   **Justificación Analítica:** Al carecer de salidas analógicas directas por red, se modula el tiempo de encendido del relé (Modulación por Ancho de Pulso) para lograr un control proporcional de la temperatura sin exceder el estrés acústico.
*   **Descripción de Alto Nivel:** El servidor calculará un ciclo de trabajo (Duty Cycle) y publicará ráfagas MQTT de encendido y apagado de forma autónoma empleando planificadores (`setPeriodic`).
*   **MQTT Sensor:** `home/{homeId}/network/{sensorId}/ambient`
*   **MQTT Actuador:** `home/{homeId}/network/{actuatorId}/command`
*   **Lógica Vert.x:** T=30°C (Duty 50%). T=40°C (Duty 100%).
*   **API REST:**
    *   `GET /api/v1/homes/:homeId/network/:actuatorId/duty`
    *   `PUT /api/v1/homes/:homeId/network/:actuatorId/override`
        *   **Cuerpo de la petición (Payload):** JSON con dos campos: un booleano para desactivar el PWM automático, y una cadena especificando el estado físico forzado del relé (ej. `{"pwm_active": false, "relay_state": "ON"}`).

### Módulo 1.10: Relojes Circadianos Independientes (Terrario)
*   **Justificación Analítica:** La automatización estática falla ante procesos biológicos. El objetivo térmico debe variar dinámicamente en función de los horarios locales configurados.
*   **Descripción de Alto Nivel:** Lógica que consulte la hora del sistema en cada iteración del mensaje MQTT. La actuación dependerá de la intersección entre la lectura y la ventana temporal activa.
*   **MQTT Sensor:** `home/{homeId}/pets/{sensorId}/ambient`
*   **MQTT Actuador:** `home/{homeId}/pets/{actuatorId}/command`
*   **Lógica Vert.x:** Aplicar lógica de temperatura objetivo según el horario local.
*   **API REST:**
    *   `GET /api/v1/homes/:homeId/pets/schedule`
    *   `PUT /api/v1/homes/:homeId/pets/schedule`
        *   **Cuerpo de la petición (Payload):** JSON con cadenas de texto (formato HH:MM) que definan los umbrales de inicio del ciclo diurno y nocturno (ej. `{"day_start": "08:00", "night_start": "20:00"}`).

---

## Temática 2: Parque de Atracciones

### Módulo 2.1: Zonas de Alerta Térmica (Frenos Montaña Rusa)
*   **Justificación Analítica:** Un diseño tolerante a fallos evalúa zonas de degradación continua, permitiendo intervenciones de mantenimiento proactivas antes de ordenar una parada de emergencia de la atracción de forma binaria.
*   **Descripción de Alto Nivel:** El sistema clasificará el estado en una matriz. Si la zona roja es alcanzada, se bloqueará el estado de forma persistente, forzando a que la atracción solo se reinicie vía intervención manual autenticada en la API REST.
*   **MQTT Sensor:** `park/{parkId}/rollercoaster/brakes/{sensorId}/temp`
*   **MQTT Actuador:** `park/{parkId}/rollercoaster/water/{actuatorId}/command`
*   **Lógica Vert.x:** Verde (<40), Amarilla (40-55, aspersores ON), Roja (>55, bloqueo estricto).
*   **API REST:**
    *   `GET /api/v1/parks/:parkId/rollercoaster/brakes/:sensorId/zones`
    *   `POST /api/v1/parks/:parkId/rollercoaster/reset`
        *   **Cuerpo de la petición (Payload):** JSON estricto que exija el identificador alfanumérico del operador de mantenimiento que asume la responsabilidad del reinicio tras la inspección (ej. `{"operator_id": "OP-774", "reason": "Inspección superada"}`).

### Módulo 2.2: Temporizador de Recuperación (Niebla Casa Terror)
*   **Justificación Analítica:** Los actuadores industriales de alta carga resistiva requieren periodos estrictos de enfriamiento para evitar la fusión de los componentes internos debida a disparos consecutivos sin interrupción.
*   **Descripción de Alto Nivel:** Implementación de "Enfriamiento Forzado" (Cooldown). El servidor denegará peticiones de encendido si la diferencia temporal respecto a la última activación registrada no supera el margen de seguridad.
*   **MQTT Sensor:** `park/{parkId}/hauntedhouse/{sensorId}/ambient`
*   **MQTT Actuador:** `park/{parkId}/hauntedhouse/{actuatorId}/command`
*   **Lógica Vert.x:** Disparar humo 3s. Ignorar peticiones para esa máquina durante 2 mins (Cooldown).
*   **API REST:**
    *   `GET /api/v1/parks/:parkId/hauntedhouse/:actuatorId/cooldown`
    *   `PUT /api/v1/parks/:parkId/hauntedhouse/:actuatorId/duration`
        *   **Cuerpo de la petición (Payload):** JSON que contenga un valor numérico entero estipulando los segundos exactos de duración del pulso electromecánico (ej. `{"pulse_seconds": 5}`).

### Módulo 2.3: Filtro de Desescarche (Congeladores Franquicias)
*   **Justificación Analítica:** La monitorización debe poseer inteligencia temporal para discernir una avería real frente a un ciclo operativo normal de mantenimiento térmico (desescarche).
*   **Descripción de Alto Nivel:** Vert.x rastreará la ruptura del umbral inferior. Solo si la anomalía térmica persiste más allá de una ventana de tiempo predefinida (20 minutos), la lógica lo clasificará como fallo mecánico y actuará.
*   **MQTT Sensor:** `park/{parkId}/food/freezer/{sensorId}/temp`
*   **MQTT Actuador:** `park/{parkId}/food/compressor/{actuatorId}/command`
*   **Lógica Vert.x:** Cronometrar subida > -10°C. Si pasan 20 mins y sigue a 0°C, activar alarma y compresor.
*   **API REST:**
    *   `GET /api/v1/parks/:parkId/food/freezer/:sensorId/defrost_logs`
    *   `DELETE /api/v1/parks/:parkId/food/alarms`

### Módulo 2.4: Ocupación Simulada por Variación (Salón Arcade)
*   **Justificación Analítica:** Detectar variaciones rápidas de humedad causadas por la transpiración humana masiva permite adelantarse predictivamente a la carga térmica generada por una multitud en recintos cerrados.
*   **Descripción de Alto Nivel:** Cuando la tasa de cambio de humedad cruce el umbral, el servidor sobrescribirá dinámicamente las constantes paramétricas de activación del sistema de aire acondicionado, reduciendo el límite de disparo para pre-enfriar.
*   **MQTT Sensor:** `park/{parkId}/arcade/{sensorId}/ambient`
*   **MQTT Actuador:** `park/{parkId}/arcade/{actuatorId}/command`
*   **Lógica Vert.x:** Salto de H > 5% -> Bajar umbral AC de 25°C a 22°C temporalmente.
*   **API REST:**
    *   `GET /api/v1/parks/:parkId/arcade/occupancy`
    *   `PUT /api/v1/parks/:parkId/arcade/thresholds`
        *   **Cuerpo de la petición (Payload):** JSON estructurado con los nuevos valores numéricos absolutos que gobernarán el punto de arranque y detención del sistema HVAC (ej. `{"ac_on_temp": 23.0, "ac_off_temp": 21.0}`).

### Módulo 2.5: Cálculo Punto de Rocío Dinámico (Noria)
*   **Justificación Analítica:** El desempañado de superficies de observación requiere el cálculo físico del punto de rocío para asegurar que el actuador térmico solo consuma energía en el instante de condensación inminente.
*   **Descripción de Alto Nivel:** Integración analítica de la ecuación de Magnus-Tetens, comparando su resultado dinámico con el valor absoluto del sensor en tiempo real para autorizar la ignición del relé antivaho.
*   **MQTT Sensor:** `park/{parkId}/ferriswheel/cabin/{sensorId}/ambient`
*   **MQTT Actuador:** `park/{parkId}/ferriswheel/defroster/{actuatorId}/command`
*   **Lógica Vert.x:** Activar antivaho si T ambiente se acerca a 2°C del punto de rocío calculado.
*   **API REST:**
    *   `GET /api/v1/parks/:parkId/ferriswheel/cabin/:sensorId/dew_point`
    *   `POST /api/v1/parks/:parkId/ferriswheel/cabin/:actuatorId/defrost`
        *   **Cuerpo de la petición (Payload):** JSON que indique de manera numérica (entero) el tiempo total en minutos que durará la fase de desempañado térmico forzoso (ej. `{"duration_minutes": 10}`).

### Módulo 2.6: Desviación de Mediana (Motores Rápidos)
*   **Justificación Analítica:** El análisis de desviaciones estadísticas respecto a la firma térmica histórica de la máquina revela fricciones operativas indetectables mediante umbrales absolutos predefinidos.
*   **Descripción de Alto Nivel:** Cálculo de la mediana sobre la telemetría almacenada. Si el flujo persistente rompe la banda de tolerancia estadística, se generará el comando de alerta y se instruirá al relé.
*   **MQTT Sensor:** `park/{parkId}/rapids/pump/{sensorId}/temp`
*   **MQTT Actuador:** `park/{parkId}/rapids/pump/{actuatorId}/command`
*   **Lógica Vert.x:** Si T actual supera la Mediana 24h en un 15% sostenido, encender piloto desgaste.
*   **API REST:**
    *   `GET /api/v1/parks/:parkId/rapids/pump/:sensorId/stats`
    *   `POST /api/v1/parks/:parkId/rapids/pump/:sensorId/calibrate`
        *   **Cuerpo de la petición (Payload):** JSON validando la sustitución física del componente, exigiendo el identificador del técnico para trazar el evento de calibración (ej. `{"technician_id": "TECH-99"}`).

### Módulo 2.7: Respuesta Proporcional Escalable (Mariposario)
*   **Justificación Analítica:** En ecosistemas estabilizados, la aplicación del 100% de la potencia correctiva genera una inestabilidad cíclica. La modulación debe ser matemáticamente proporcional a la magnitud del desvío detectado.
*   **Descripción de Alto Nivel:** Parametrización de los temporizadores del PWM del Relé basándose directamente en el cálculo del diferencial continuo entre la humedad captada y el objetivo prefijado en memoria.
*   **MQTT Sensor:** `park/{parkId}/butterfly/{sensorId}/ambient`
*   **MQTT Actuador:** `park/{parkId}/butterfly/{actuatorId}/command`
*   **Lógica Vert.x:** Modulación de tiempos de encendido según el porcentaje de error de humedad.
*   **API REST:**
    *   `GET /api/v1/parks/:parkId/butterfly/:actuatorId/effort`
    *   `PUT /api/v1/parks/:parkId/butterfly/target`
        *   **Cuerpo de la petición (Payload):** JSON que transmita de forma explícita el porcentaje numérico de humedad relativa objetivo exigida para el recinto (ej. `{"target_humidity": 85}`).

### Módulo 2.8: Lógica de Fallo en Cascada (Datacenter Taquillas)
*   **Justificación Analítica:** La emisión de una orden electrónica no garantiza la efectividad termodinámica. El servidor debe evaluar a posteriori si el sistema de enfriamiento primario ha fracasado en disipar el calor para activar redundancias o bloqueos defensivos.
*   **Descripción de Alto Nivel:** Si al finalizar el temporizador de verificación la temperatura no presenta decremento, el algoritmo inferirá un fallo mecatrónico y ejecutará un protocolo de desconexión lógica, interrumpiendo el canal de API estándar.
*   **MQTT Sensor:** `park/{parkId}/datacenter/{sensorId}/temp`
*   **MQTT Actuador:** `park/{parkId}/datacenter/{actuatorId}/command`
*   **Lógica Vert.x:** T>25°C -> Relé ON. Si en 5 mins T>28°C, shutdown y estado CRÍTICO.
*   **API REST:**
    *   `GET /api/v1/parks/:parkId/datacenter/health`
    *   `POST /api/v1/parks/:parkId/datacenter/override`
        *   **Cuerpo de la petición (Payload):** JSON con un booleano confirmatorio que fuerce la interrupción de la maniobra automática de apagado de seguridad del centro de datos (ej. `{"cancel_shutdown": true}`).

### Módulo 2.9: Bloqueo UV Externo (Colas Exteriores)
*   **Justificación Analítica:** La actuación eficiente requiere cruzar la telemetría microclimática local con factores macroambientales externos (radiación solar) que alteran la justificación real del disparo de los actuadores de refrigeración líquida.
*   **Descripción de Alto Nivel:** El *handler* de MQTT validará simultáneamente el valor local del sensor y el estado de la variable de entorno global (modificada vía REST) antes de emitir la señal eléctrica al relé, aplicando un patrón de compuerta lógica AND.
*   **MQTT Sensor:** `park/{parkId}/queue/{sensorId}/ambient`
*   **MQTT Actuador:** `park/{parkId}/queue/{actuatorId}/command`
*   **Lógica Vert.x:** Aspersores activos solo si T > 35°C Y UV_Index_Asignado > 7.
*   **API REST:**
    *   `GET /api/v1/parks/:parkId/queue/sprinkler_metrics`
    *   `PUT /api/v1/parks/:parkId/queue/uv_index`
        *   **Cuerpo de la petición (Payload):** JSON compuesto por un valor numérico (entero o punto flotante) que inyecte en el sistema el índice de radiación ultravioleta actual (ej. `{"current_uv": 8.5}`).

### Módulo 2.10: Acumulación Puntos de Moho (Vestuarios Espectáculos)
*   **Justificación Analítica:** El riesgo biológico fúngico no ocurre por eventos transitorios de humedad alta, sino que es el resultado integral de la persistencia ambiental por encima de la franja de secado térmico a lo largo de las horas.
*   **Descripción de Alto Nivel:** Por cada paquete entrante anómalo, se incrementará de forma aditiva un registro persistente. La orden de ignición de las resistencias de secado se subordinará matemáticamente a la superación del límite de volumen acumulado, no al valor absoluto de la transmisión presente.
*   **MQTT Sensor:** `park/{parkId}/wardrobe/{sensorId}/humidity`
*   **MQTT Actuador:** `park/{parkId}/wardrobe/{actuatorId}/command`
*   **Lógica Vert.x:** Sumar "Puntos" si H > 70%, restar si < 50%. Activar relé calefactor si Puntos > 100.
*   **API REST:**
    *   `GET /api/v1/parks/:parkId/wardrobe/mold_points`
    *   `POST /api/v1/parks/:parkId/wardrobe/shock_treatment`
        *   **Cuerpo de la petición (Payload):** JSON de confirmación manual que acredite la saturación artificial de los contadores para iniciar la deshumidificación forzada sin dilación temporal (ej. `{"confirm_shock_start": true}`).

---

## Temática 3: Central Nuclear

### Módulo 3.1: Redundancia Distribuida (Piscina Uranio)
*   **Justificación Analítica:** Las interacciones de la radiación ionizante inducen corrupciones binarias (*Single-Event Upsets*) en la instrumentación digital. Se requiere el diseño de un algoritmo de consenso de muestreo temporal para avalar la veracidad del salto térmico notificado.
*   **Descripción de Alto Nivel:** El sistema descartará lecturas límite solitarias. Vert.x retendrá en la pila el recuento de superaciones sucesivas, reseteando la cuenta a cero si un paquete posterior emite un valor nominal. Se exigirá la recepción de $N$ paquetes anómalos contiguos para detonar la rutina de bombeo auxiliar.
*   **MQTT Sensor:** `nuclear/{plantId}/pool/{sensorId}/temp`
*   **MQTT Actuador:** `nuclear/{plantId}/pool/{actuatorId}/command`
*   **Lógica Vert.x:** Exigir 3 lecturas consecutivas > 35°C de un mismo `sensorId` para actuar.
*   **API REST:**
    *   `GET /api/v1/plants/:plantId/pool/:sensorId/reliability`
    *   `PUT /api/v1/plants/:plantId/pool/consecutive_checks`
        *   **Cuerpo de la petición (Payload):** JSON que contenga el parámetro numérico entero que determine el factor riguroso de comprobaciones iterativas previas a la validación física del evento (ej. `{"required_checks": 5}`).

### Módulo 3.2: Integración de Saturación (Filtros Contención)
*   **Justificación Analítica:** La permeabilidad residual de los filtros HEPA es inversamente proporcional a la masa acumulada de condensación hidrológica a lo largo del tiempo de operación activo. El modelo requiere un seguimiento analítico integral.
*   **Descripción de Alto Nivel:** Vert.x computará la suma iterativa sobre cada paquete. Si la magnitud resultante de esta integral rebasa la cota máxima del filtro, el sistema emitirá el comando de actuación para ejecutar la fase de deshumidificación de choque profundo.
*   **MQTT Sensor:** `nuclear/{plantId}/containment/{sensorId}/humidity`
*   **MQTT Actuador:** `nuclear/{plantId}/containment/{actuatorId}/command`
*   **Lógica Vert.x:** Sumatoria total de humedad. Si supera capacidad, activar secado 2 horas.
*   **API REST:**
    *   `GET /api/v1/plants/:plantId/containment/saturation`
    *   `DELETE /api/v1/plants/:plantId/containment/purge`

### Módulo 3.3: Secuencia de Arranque (Generadores Diésel)
*   **Justificación Analítica:** Iniciar maquinaria de respaldo en estado seco desencadena roturas catastróficas del bloque motor por la alta fricción metalmecánica generada en el primer ciclo cinemático. Las órdenes pre-operacionales de inyección lubricante son imperativas.
*   **Descripción de Alto Nivel:** Orquestación de una máquina de estados finita asíncrona temporal. La invocación inicial ejecutará el bombeo preparatorio. Un temporizador interno de Vert.x resolverá a posteriori el paro de este estadio y comandará el arranque efectivo sin ocasionar bloqueos de hilo en la arquitectura reactiva.
*   **MQTT Sensor:** `nuclear/{plantId}/diesel/{sensorId}/temp`
*   **MQTT Actuador:** `nuclear/{plantId}/diesel/{actuatorId}/command`
*   **Lógica Vert.x:** Máquina asíncrona: Relé ON (Pre-lubricante) -> espera 5s -> Relé OFF, Relé ON (Arranque).
*   **API REST:**
    *   `GET /api/v1/plants/:plantId/diesel/:actuatorId/sequence`
    *   `POST /api/v1/plants/:plantId/diesel/:actuatorId/simulate_loop`
        *   **Cuerpo de la petición (Payload):** JSON estandarizado especificando en un campo de texto la tipología de la maniobra de test o simulacro de pérdida de tensión a ejecutar (ej. `{"test_type": "FULL_LOOP_SEQUENCE"}`).

### Módulo 3.4: Análisis de Pendiente Exotérmica (Residuos)
*   **Justificación Analítica:** La física de los eventos exotérmicos radiológicos obedece a curvas de calentamiento exponenciales. La detección preventiva requiere identificar la aceleración térmica matemática (la segunda derivada) en vez de evaluar cruces de temperaturas de consigna absolutas.
*   **Descripción de Alto Nivel:** Vert.x efectuará un cálculo comparativo de dos pendientes diferenciales adyacentes. Si la evaluación demuestra una aceleración escalar positiva en el ascenso térmico, emitirá la orden al sistema de tiro forzado de gases y sellará criptográficamente la base de datos de control.
*   **MQTT Sensor:** `nuclear/{plantId}/waste/{sensorId}/ambient`
*   **MQTT Actuador:** `nuclear/{plantId}/waste/{actuatorId}/command`
*   **Lógica Vert.x:** Calcular pendientes térmicas. Si P1 > P2 (aceleración), disparar extractor y bloqueo.
*   **API REST:**
    *   `GET /api/v1/plants/:plantId/waste/:sensorId/acceleration`
    *   `PUT /api/v1/plants/:plantId/waste/lockdown`
        *   **Cuerpo de la petición (Payload):** JSON estructural que delimite el grado de severidad del confinamiento exigido y registre explícitamente el perfil administrativo que instiga la cuarentena total (ej. `{"severity": "CRITICAL", "authorized_by": "SYSADMIN"}`).

### Módulo 3.5: Esquema de Lastre por Fases (Transformadores)
*   **Justificación Analítica:** La protección de subestaciones transformadoras exige rutinas de degradación de servicio operativas. La amputación gradual del suministro eléctrico evita alcanzar el estado de fusión del núcleo ferromagnético y el colapso global por cortocircuito de red.
*   **Descripción de Alto Nivel:** Construcción algorítmica de niveles críticos solapados. La superación incremental de las cotas impondrá transformaciones en la frecuencia del actuador hasta desembocar en el corte absoluto simulado para proteger el hardware por estrés dieléctrico.
*   **MQTT Sensor:** `nuclear/{plantId}/grid/transformer/{sensorId}/temp`
*   **MQTT Actuador:** `nuclear/{plantId}/grid/transformer/{actuatorId}/command`
*   **Lógica Vert.x:** T>50 (Nivel 1 PWM), T>65 (Nivel 2 PWM). T>85 (Apagado forzoso simulando corte).
*   **API REST:**
    *   `GET /api/v1/plants/:plantId/grid/transformer/:sensorId/tier`
    *   `POST /api/v1/plants/:plantId/grid/transformer/:sensorId/bypass`
        *   **Cuerpo de la petición (Payload):** JSON que requiera obligatoriamente un parámetro numérico codificado para puentear el cierre térmico e forzar la operación ininterrumpida de las bobinas sobre los 85°C (ej. `{"override_pin": 9942}`).

### Módulo 3.6: Estimador Computacional de H2 (Sala Baterías)
*   **Justificación Analítica:** El riesgo de detonación derivado de la desgasificación de hidrógeno en bancos de baterías de ciclo profundo es directamente proporcional al incremento calórico del ácido electrolítico. La mitigación del riesgo se aborda deduciendo las concentraciones gaseosas a partir del vector térmico.
*   **Descripción de Alto Nivel:** Vert.x resolverá en cada iteración una transformación lineal algorítmica que proyectará la telemetría del sensor hacia una concentración volumétrica estimada en Partes Por Millón (PPM). La lógica de encendido o apagado operará estrictamente sobre el resultado matemático derivado.
*   **MQTT Sensor:** `nuclear/{plantId}/ups/{sensorId}/temp`
*   **MQTT Actuador:** `nuclear/{plantId}/ups/{actuatorId}/command`
*   **Lógica Vert.x:** `Tasa_H2 = (Temp - 25) * 1.5`. Acumular PPM. Si > 4000, encender ventilación hasta vaciar.
*   **API REST:**
    *   `GET /api/v1/plants/:plantId/ups/:sensorId/h2_ppm`
    *   `PUT /api/v1/plants/:plantId/ups/:sensorId/h2_multiplier`
        *   **Cuerpo de la petición (Payload):** JSON numérico (punto flotante) destinado a calibrar y actualizar la constante de conversión algorítmica para ajustarse a las curvas de degradación por edad de las celdas físicas (ej. `{"multiplier": 1.8}`).

### Módulo 3.7: Factor de Rendimiento Humano (Sala Control)
*   **Justificación Analítica:** Las fluctuaciones microclimáticas severas en centros de control de misiones críticas incurren directamente en el colapso del nivel de alerta biológico. Mantener el desempeño neurocognitivo del operador depende del control preciso de las presiones de confort del sistema de soporte vital.
*   **Descripción de Alto Nivel:** Vert.x computará una penalización dinámica cruzando desviaciones de humedad y temperatura contra el vector base ideal. Al rebasar el límite permisible de deterioro operacional numérico, se accionará un modo de recuperación climática inmediata forzosa.
*   **MQTT Sensor:** `nuclear/{plantId}/control_room/{sensorId}/ambient`
*   **MQTT Actuador:** `nuclear/{plantId}/control_room/{actuatorId}/command`
*   **Lógica Vert.x:** Rango óptimo 22°C y 45%. Penalizar desviaciones. Score < 70 fuerza AC.
*   **API REST:**
    *   `GET /api/v1/plants/:plantId/control_room/hu_score`
    *   `POST /api/v1/plants/:plantId/control_room/shift_change`
        *   **Cuerpo de la petición (Payload):** JSON documental que requiera la identificación en cadena de texto del destacamento de operarios entrantes con objeto de normalizar los contadores y restaurar el factor humano nominal (ej. `{"shift_team": "TEAM-B-GAMMA"}`).

### Módulo 3.8: Envolvente Térmica Estricta (Laboratorio Dosimetría)
*   **Justificación Analítica:** La alteración molecular en los detectores sólidos generada por la dilatación térmica descarrila los factores de calibración radiológicos. Rebasar milimétricamente la cota térmica exige la purga y anulación retroactiva de las muestras analizadas en ese bloque temporal.
*   **Descripción de Alto Nivel:** Si el diferencial captado cruza el margen de tolerancia predefinido del setpoint, además de actuar sobre los actuadores climáticos, el servidor emitirá sentencias que introducirán indicadores lógicos de corrupción en todos los registros SQL de calibración asociados a dicho tramo.
*   **MQTT Sensor:** `nuclear/{plantId}/dosimetry/{sensorId}/temp`
*   **MQTT Actuador:** `nuclear/{plantId}/dosimetry/{actuatorId}/command`
*   **Lógica Vert.x:** Setpoint 20.0°C. Si escapa del rango 18-22°C, invalidar lógicamente el laboratorio el resto del día.
*   **API REST:**
    *   `GET /api/v1/plants/:plantId/dosimetry/calibration`
    *   `PUT /api/v1/plants/:plantId/dosimetry/setpoint`
        *   **Cuerpo de la petición (Payload):** JSON que incorpore explícitamente el valor numérico en precisión flotante que determinará el centro exacto de la nueva banda de oscilación térmica autorizada (ej. `{"center_temp": 20.5}`).

### Módulo 3.9: Psicrometría Básica Evaporativa (Torre Refrigeración)
*   **Justificación Analítica:** La termodinámica de las torres húmedas carece de efectividad frente a atmósferas con un déficit de presión de vapor próximo a cero. Un sistema de control eficiente conmuta a disipación mecánica activa en el momento que la saturación de humedad bloquea el intercambio calórico evaporativo.
*   **Descripción de Alto Nivel:** El algoritmo subyacente de control estará supeditado a los límites analíticos de saturación de aire. Las oscilaciones del sensor interceptarán y cancelarán mandatos contrapuestos, aplicando tiro forzado ininterrumpidamente hasta asegurar el quiebre de la barrera de condensación.
*   **MQTT Sensor:** `nuclear/{plantId}/cooling_tower/{sensorId}/ambient`
*   **MQTT Actuador:** `nuclear/{plantId}/cooling_tower/{actuatorId}/command`
*   **Lógica Vert.x:** Si H > 95% el enfriamiento pasivo falla -> Encender forzados. Si H < 60% -> Apagar.
*   **API REST:**
    *   `GET /api/v1/plants/:plantId/cooling_tower/psychrometrics`
    *   `POST /api/v1/plants/:plantId/cooling_tower/:actuatorId/test`
        *   **Cuerpo de la petición (Payload):** JSON con un parámetro numérico entero que determine los segundos temporizados que durará el protocolo de chequeo auditivo de arranque de los generadores axiales (ej. `{"duration_seconds": 2}`).

### Módulo 3.10: Rate of Rise - RoR (Incendios en Galerías)
*   **Justificación Analítica:** El retardo inercial que requiere un fuego subterráneo por arco eléctrico para calentar la sonda hasta la consigna estática convencional es fatal. La metodología *Rate of Rise* garantiza el disparo prematuro evaluando exclusivamente la pendiente de crecimiento anómala al inicio de la ignición.
*   **Descripción de Alto Nivel:** Algoritmia avanzada de monitorización de gradientes de emergencia con comparativas de muestreo rodante. La evaluación positiva de una pendiente vertiginosa emitirá una ráfaga al relé de los tanques supresores y forzará en el código fuente la detención absoluta del bucle de intercepción, impidiendo cancelaciones inducidas por rebotes MQTT.
*   **MQTT Sensor:** `nuclear/{plantId}/tunnels/{sensorId}/temp`
*   **MQTT Actuador:** `nuclear/{plantId}/tunnels/{actuatorId}/command`
*   **Lógica Vert.x:** Detectar subida > 8°C por minuto. Activar Halón y forzar bloqueo de software.
*   **API REST:**
    *   `GET /api/v1/plants/:plantId/tunnels/:sensorId/ror`
    *   `DELETE /api/v1/plants/:plantId/tunnels/:sensorId/extinguisher`