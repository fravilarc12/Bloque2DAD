# Laboratorio: Integración de Comunicación Bidireccional con MQTT en Vert.x

## Introducción
En los laboratorios anteriores se ha consolidado una arquitectura basada en el protocolo HTTP (REST) y la persistencia de datos en bases de datos relacionales. Sin embargo, en el ecosistema IoT, la dependencia exclusiva de HTTP presenta deficiencias estructurales: es un protocolo pesado, síncrono (Request/Response) e incapaz de iniciar comunicaciones desde el servidor hacia el dispositivo (Server-to-Client) de forma natural.

Este laboratorio introduce el protocolo **MQTT (Message Queuing Telemetry Transport)**. El objetivo es dotar al sistema de una capa de mensajería asíncrona de baja latencia y comunicación bidireccional en tiempo real, permitiendo que el servidor reciba telemetría sin el *overhead* de HTTP y pueda emitir comandos asíncronos hacia los actuadores (ESP32).

---

## Prerrequisitos
1.  **Broker MQTT:** Disponer de un broker MQTT en ejecución (ej. Eclipse Mosquitto). Puede instalarse nativamente o desplegarse mediante un contenedor Docker (ver a la sección EXTRA final para más información).
2.  **Cliente de Pruebas:** Tener instalado **MQTT Explorer**. Esta herramienta gráfica actuará como un "falso ESP32" para inyectar y visualizar el tráfico de red durante la fase de desarrollo del servidor.
3.  **Código Base:** Partir del `SensorApiVerticle` desarrollado previamente, el cual ya debe integrar la conexión a MariaDB/MySQL mediante el cliente reactivo.


---

## Configuración de Red: Topología Local y Tethering

### Conceptos Arquitectónicos
*   **Visibilidad IP (Client Isolation):** En la mayoría de las redes Wi-Fi corporativas o universitarias (como *eduroam*), los administradores implementan políticas de aislamiento de clientes (AP Isolation) por motivos de seguridad. Esto significa que dos dispositivos conectados a la misma red Wi-Fi no pueden verse ni comunicarse directamente entre sí.
*   **Resolución Local (`localhost`):** Hasta ahora, los componentes (Postman, Vert.x, Mosquitto, MQTT Explorer) residían en la misma máquina física, comunicándose a través de la interfaz de *loopback* (`127.0.0.1` o `localhost`). Al introducir dispositivos físicos (ESP32) o equipos externos, `localhost` deja de ser válido, ya que cada dispositivo intentará buscar el servidor dentro de sí mismo.

### Tips de Implementación

Para garantizar una topología de red en estrella (Star Topology) donde todos los nodos tengan visibilidad bidireccional, crearemos una Red de Área Local (LAN) privada utilizando un dispositivo móvil.

1.  **Habilitar el Punto de Acceso (Tethering):** Un miembro del equipo debe activar la función de "Compartir Internet" / "Punto de Acceso Móvil" en su smartphone. Esto convierte al teléfono en el router DHCP central.
2.  **Conexión Global:** Conectad tanto el ordenador portátil (que ejecuta Vert.x y el Broker MQTT) como, en el futuro, la placa ESP32, a esta nueva red Wi-Fi generada por el móvil.
3.  **Descubrimiento de IP:** El ordenador que actúa como servidor necesita conocer su dirección IP asignada en esta nueva red.
    *   En **Windows**: Abrid la terminal (`cmd`) y ejecutad `ipconfig`. Buscad la dirección IPv4 bajo el adaptador de red inalámbrica (suele empezar por `192.168.x.x`).
    *   En **macOS/Linux**: Ejecutad `ifconfig` o `ip a` en la terminal.
4.  **Sustitución de Localhost:** A partir de este momento, en Postman, MQTT Explorer o en el código del ESP32 cuando llegue el momento, se debe **reemplazar estrictamente** `localhost` o `127.0.0.1` por la IP descubierta en el paso anterior (ej. `192.168.43.51`).

---

## Paso 1: Conceptos Arquitectónicos de MQTT

Antes de codificar, es imperativo comprender la topología de red pub/sub.

*   **El Broker:** Es el nodo central (servidor). Enruta los mensajes entre los clientes. Vert.x no actuará como broker, sino como un **Cliente MQTT** con privilegios elevados.
*   **Publicador / Suscriptor:** Los dispositivos no se comunican entre sí ni envían datos directos a una URL. Publican mensajes en un "canal" (Topic) o se suscriben a uno para escuchar.
*   **Topics (Jerarquía RESTful):** Los canales se estructuran mediante barras `/`. Para este laboratorio, definiremos un contrato estricto de topics:
    *   Telemetría de sensores (Entrante): `devices/sensors/{sensorId}/telemetry`
    *   Comandos a actuadores (Saliente): `devices/actuators/{actuatorId}/command`
*   **Comodines (Wildcards):** El carácter `+` sustituye un único nivel. Si Vert.x se suscribe a `devices/sensors/+/telemetry`, recibirá los datos de absolutamente todos los sensores.

---

## Paso 2: Configuración de Dependencias

Se requiere integrar el cliente MQTT nativo de Vert.x en el ciclo de construcción. Añadid la siguiente dependencia al archivo `pom.xml`:

```xml
<dependency>
    <groupId>io.vertx</groupId>
    <artifactId>vertx-mqtt</artifactId>
    <version>5.0.7</version> <!-- Alinear con la versión del framework base -->
</dependency>
```

---

## Paso 3: Inicialización del Cliente MQTT en Vert.x

Dentro de vuestro `SensorApiVerticle`, se debe declarar e inicializar el cliente MQTT durante el ciclo de vida de arranque (`start`).

### 3.1 Instanciación y Conexión
Cread un método auxiliar `setupMqttClient()` e invocadlo antes o después de configurar el router HTTP.

```java
// Variable a nivel de clase
private MqttClient mqttClient;

private void setupMqttClient() {
    mqttClient = MqttClient.create(vertx);

    // Conexión al broker local por el puerto estándar no seguro (1883)
    mqttClient.connect(1883, "localhost").onSuccess(s -> {
        System.out.println("✅ Cliente Vert.x conectado al Broker MQTT");
        
        // Registrar el manejador de mensajes entrantes
        setupMqttHandlers();
        
        // Suscribirse a los topics de telemetría usando el comodín '+'
        mqttClient.subscribe("devices/sensors/+/telemetry", 1).onSuccess(granted -> {
            System.out.println("📡 Servidor suscrito a la telemetría de todos los sensores.");
        });
        
    }).onFailure(err -> {
        System.err.println("❌ Fallo conectando al Broker MQTT: " + err.getMessage());
    });
}
```

---

## Paso 4: Recepción de Telemetría (ESP32 -> Servidor)

El servidor debe reaccionar de forma asíncrona cada vez que un sensor publique un nuevo valor.

### 4.1 Procesamiento del Payload
Definid la lógica dentro de `setupMqttHandlers()`. El manejador intercepta el mensaje, extrae el identificador del topic y parsea el cuerpo JSON.

```java
private void setupMqttHandlers() {
    mqttClient.publishHandler(message -> {
        String topic = message.topicName();
        String payloadString = message.payload().toString();
        
        System.out.println("📩 Mensaje MQTT recibido en [" + topic + "]: " + payloadString);

        if (topic.startsWith("devices/sensors/") && topic.endsWith("/telemetry")) {
            try {
                JsonObject data = new JsonObject(payloadString);
                
                // Extraer el ID físico desde el topic (ej. devices/sensors/S1/telemetry)
                String[] topicParts = topic.split("/");
                String sensorId = topicParts[2];
                
                // TODO: Reutilizar el Pool de MySQL del laboratorio anterior.
                // Ejecutar una consulta INSERT preparada con los datos extraídos (sensorId, data.getDouble("value"), etc.)
                // client.preparedQuery("INSERT INTO sensors...").execute(...);
                
            } catch (Exception e) {
                System.err.println("⚠️ Payload JSON malformado vía MQTT: " + e.getMessage());
            }
        }
    });
}
```

---

## Paso 5: Emisión de Comandos (Servidor -> ESP32)

Aquí se ilustra el verdadero potencial de la bidireccionalidad. Transformaremos la API REST para que, cuando un usuario envíe un comando HTTP `PUT` al servidor para accionar un dispositivo, el servidor traduzca esa orden y la dispare instantáneamente hacia el ESP32 a través de MQTT.

### 5.1 Refactorización del Endpoint REST del Actuador
Añadid un endpoint en vuestro `Router` para canalizar las órdenes de actuación.

```java
// Endpoint para enviar una orden de actuación
router.put("/api/actuators/:id/command").handler(ctx -> {
    String actuatorId = ctx.pathParam("id");
    JsonObject commandJson = ctx.body().asJsonObject();

    if (mqttClient != null && mqttClient.isConnected()) {
        String targetTopic = "devices/actuators/" + actuatorId + "/command";
        
        // Publicar el mensaje en el broker
        mqttClient.publish(
            targetTopic,
            commandJson.toBuffer(),
            io.netty.handler.codec.mqtt.MqttQoS.AT_LEAST_ONCE,
            false, // duplicate flag
            false  // retain flag
        ).onSuccess(messageId -> {
            ctx.response().setStatusCode(202).end(new JsonObject().put("status", "Command dispatched").encode());
        });
    } else {
        ctx.response().setStatusCode(503).end(new JsonObject().put("error", "MQTT Broker unavailable").encode());
    }
});
```

---

## Paso 6: Pruebas de Integración con MQTT Explorer

Para validar la arquitectura sin necesidad del hardware final (ESP32), utilizaremos MQTT Explorer.

1.  **Conectar MQTT Explorer:** Configurad una nueva conexión hacia `localhost` por el puerto `1883`.
2.  **Simular el ESP32 (Sensor):**
    *   En la caja de publicación (*Publish*), introducid el topic: `devices/sensors/TEMP_01/telemetry`
    *   Seleccionad el formato `json`.
    *   Introducid el payload: `{"value": 24.5, "type": "temperature"}`
    *   Pulsad *Publish*. Comprobad en la consola de vuestro IDE que Vert.x intercepta el mensaje y ejecuta el guardado en la base de datos de forma silenciosa.
3.  **Simular el ESP32 (Actuador):**
    *   En MQTT Explorer, suscribíos (*Subscribe*) al topic: `devices/actuators/RELAY_01/command`
    *   Abrid Postman e invocad vuestra propia API: `PUT http://localhost:8080/api/actuators/RELAY_01/command` enviando un JSON (ej. `{"state": 1}`).
    *   Observad en tiempo real cómo MQTT Explorer captura el mensaje proveniente del servidor Vert.x.

---

## Tareas Sugeridas para el Laboratorio

*   **Integración de Base de Datos:** Completad el bloque `TODO` del `publishHandler` para que la telemetría recibida por MQTT persista permanentemente en MariaDB, reutilizando la lógica del laboratorio anterior[cite: 8].
*   **Gestión de Desconexiones:** El protocolo MQTT soporta el cierre inesperado de sockets. Investigad el método `mqttClient.closeHandler()` para detectar cuándo el servidor Vert.x pierde la conexión con el Broker e implementad una política de reconexión automática mediante `vertx.setTimer()`.
*   

---

## Experimentación Cruzada: Comunicación Inter-Equipos

Para validar empíricamente la robustez de la arquitectura distribuida (REST + MQTT), vamos a realizar una prueba de integración entre dos equipos de laboratorio diferentes (Equipo A y Equipo B).

### Requisito Previo
Ambos equipos deben desconectarse de la red de la universidad y conectarse al **mismo punto de acceso móvil** (Tethering) proporcionado por uno de los alumnos. El Equipo A actuará como "Servidor Central" y el Equipo B como "Dispositivo IoT Remoto".

### Prueba 1: Inyección de Telemetría (Equipo B -> Equipo A)
1.  El **Equipo A** arranca su contenedor de Mosquitto y su aplicación Vert.x. Anota su dirección IP (ej. `192.168.43.10`).
2.  El **Equipo B** abre MQTT Explorer y configura una nueva conexión apuntando a la IP del Equipo A por el puerto `1883`.
3.  El **Equipo B** publica un payload JSON simulando un sensor en el topic correspondiente (ej. `devices/sensors/TEMP_EXTERNA_03/telemetry`).
4.  **Validación:** El Equipo A debe observar en la consola de su IDE cómo Vert.x intercepta el mensaje entrante del Equipo B y lo procesa correctamente (guardándolo en la base de datos si dicha funcionalidad está activa).

### Prueba 2: Actuación Bidireccional (Postman -> Vert.x -> MQTT)
En este escenario, el Equipo B enviará una orden REST al servidor del Equipo A, y este último se la reenviará al Equipo B vía MQTT.

1.  El **Equipo B** se suscribe en su MQTT Explorer al topic de comandos de un actuador específico (ej. `devices/actuators/MOTOR_01/command`). Al estar conectado al broker del Equipo A, ahora está "escuchando" las órdenes centrales.
2.  El **Equipo B** abre Postman y lanza una petición HTTP `PUT` a la API REST del Equipo A:
    *   **URL:** `[http://192.168.43.10:8080/api/actuators/MOTOR_01/command](http://192.168.43.10:8080/api/actuators/MOTOR_01/command)`
    *   **Body (JSON):** `{"state": "ON", "speed": 80}`
3.  **Validación:** El flujo debe completarse en milisegundos. Vert.x en el ordenador del Equipo A recibe el `PUT`, lo traduce a un mensaje MQTT y lo publica en el broker. El MQTT Explorer del Equipo B debe iluminarse inmediatamente mostrando el payload recibido. Hemos cerrado el ciclo IoT completo a través de la red física.

---

### EXTRA: Instalación de Mosquitto mediante Docker (Método Recomendado)

La instalación del broker Eclipse Mosquitto es el paso fundamental para habilitar la topología *Pub/Sub* de MQTT en el servidor. A diferencia de una base de datos tradicional, el broker es sumamente ligero y su instalación es directa.

El uso de Docker garantiza que el entorno de ejecución sea idéntico independientemente del sistema operativo subyacente de cada alumno, eliminando los problemas de dependencias locales.

#### Requisito Previo
Asegúrate de tener Docker Desktop instalado y ejecutándose en tu sistema. Puedes descargarlo desde [docker.com](https://www.docker.com/products/docker-desktop).

#### Paso 1: Ejecución del Contenedor (Sin Autenticación - Solo Desarrollo)

Para iniciar una instancia básica del broker, abre tu terminal y ejecuta el siguiente comando:

```bash
docker run -it -p 1883:1883 -p 9001:9001 eclipse-mosquitto
```

**Análisis del Comando:**
*   `docker run`: Instruye al motor de Docker a iniciar un nuevo contenedor.
*   `-it`: Mantiene la terminal interactiva para poder visualizar los registros (logs) del broker en tiempo real.
*   `-p 1883:1883`: Mapea el puerto TCP 1883 del contenedor al puerto 1883 de tu máquina local (host). Este es el puerto estándar no seguro por el que Vert.x y el ESP32 se conectarán al broker.
*   `-p 9001:9001`: Mapea el puerto estándar para *WebSockets*. Esto es útil si planeas conectar un cliente web (Frontend en JavaScript) directamente al broker MQTT.
*   `eclipse-mosquitto`: El nombre oficial de la imagen en Docker Hub.

#### Paso 2: Ejecución Persistente y Configuración

El comando anterior es ideal para pruebas rápidas, pero tiene un problema estructural: al detener el contenedor, cualquier configuración o retención de mensajes se pierde. Además, a partir de la versión 2.0 de Mosquitto, el broker viene preconfigurado para rechazar conexiones externas (anónimas) por defecto, lo que impedirá que clientes fuera del propio contenedor (como Vert.x) se conecten si no se especifica una configuración.

Para resolver esto, debemos proporcionar un archivo de configuración (`mosquitto.conf`).

1.  **Crear el archivo de configuración:** Crea un archivo llamado `mosquitto.conf` en cualquier directorio de tu equipo e introduce estas dos líneas:

    ```conf
    # mosquitto.conf
    listener 1883 0.0.0.0
    allow_anonymous true
    ```
    *   `listener 1883 0.0.0.0`: Ordena al broker escuchar por el puerto 1883 en todas las interfaces de red, no solo en `localhost`.
    *   `allow_anonymous true`: Permite que Vert.x y el ESP32 se conecten sin necesidad de enviar usuario y contraseña (exclusivo para desarrollo local).

2.  **Ejecutar inyectando la configuración:** Vuelve a la terminal y ejecuta el contenedor montando tu archivo local:

    ```bash
    # Sustituye /ruta/absoluta/a/tu/archivo/ por la ruta real donde creaste mosquitto.conf
    docker run -it -p 1883:1883 -v /ruta/absoluta/a/tu/archivo/mosquitto.conf:/mosquitto/config/mosquitto.conf eclipse-mosquitto
    ```

#### Alternativa: Instalación Nativa

Si prefieres no utilizar Docker, la instalación nativa es sencilla dependiendo de tu sistema operativo:

*   **macOS (Homebrew):**
    ```bash
    brew install mosquitto
    # Para iniciarlo como servicio en segundo plano:
    brew services start mosquitto
    ```

*   **Linux (Debian/Ubuntu):**
    ```bash
    sudo apt update
    sudo apt install mosquitto mosquitto-clients
    # El servicio suele iniciarse automáticamente
    ```

*   **Windows:**
    Descarga el instalador binario desde la página oficial de descargas de [Mosquitto](https://mosquitto.org/download/) y sigue el asistente. Recuerda marcar la opción para instalarlo como un "Servicio de Windows" para que arranque automáticamente con el sistema. Deberás configurar manualmente el archivo `mosquitto.conf` ubicado en el directorio de instalación y reiniciar el servicio para habilitar conexiones externas (`allow_anonymous true`).
```