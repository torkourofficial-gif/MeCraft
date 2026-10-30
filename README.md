# MeCraft (demake para Java ME)

MeCraft es un prototipo 3D para teléfonos con **MIDP 2.0, CLDC 1.1 y M3G (JSR-184)**. Toma como referencia las ideas visibles de Minecraft Indev 20100223 —mundos de isla, supervivencia/creativo, bloques y barra rápida— y usa el atlas y la pantalla de título incluidos en este repositorio. El código Java de este demake se ha escrito para MeCraft; no copia clases del cliente de referencia.

Referencia consultada: [EaglerPorts/in-20100223](https://github.com/EaglerPorts/in-20100223). Consulta [NOTICE.md](NOTICE.md) para la atribución.

## Estado jugable

- Menú principal con fondo de tierra del atlas y selector de varios mundos. Cada partida conserva su propio nombre, modo y progreso; al crear una se puede editar el nombre y elegir Supervivencia o Creativo. Las partidas guardadas por versiones anteriores aparecen como «Mundo guardado».
- Música MIDI: `menu.mid` acompaña el menú; durante el juego se elige al azar entre `calm1.mid` y `calm4.mid`, dejando diez segundos de silencio al terminar cada pista. El reproductor MMAPI mantiene una sola pista abierta y la música se silencia al pausar la aplicación.
- Mundo de isla procedural de 48 × 24 × 48 bloques, con costa, agua, árboles, carbón, hierro y roca madre.
- Modo supervivencia empieza con el inventario vacío; los bloques rotos se recogen, y modo creativo tiene bloques ilimitados.
- En creativo, `#` abre una paleta paginada con los bloques texturados del atlas `textures.png`; se elige uno con `5` para ponerlo en la casilla rápida seleccionada. Las celdas vacías del atlas no aparecen. La TNT se activa al golpearla con `1`, sin soltar un bloque normal; cae, rebota y explota tras 80 ticks. La explosión puede encadenar otras TNT y dañar criaturas y al jugador.
- En creativo, pulsa `5` dos veces dentro de 350 ms para activar o desactivar el vuelo; mantén `5` para subir lentamente y `0` para bajar.
- Cámara en primera persona, tercera persona trasera y segunda persona frontal; el jugador usa su skin 64 × 32 con animación básica de brazos y piernas. Las cámaras externas se acercan automáticamente al jugador al encontrar paredes. `0` + cruceta arriba cambia entre las tres perspectivas.
- Movimiento, colisión, gravedad y salto.
- Ciclo de día y noche basado en la curva de Indev de 24 000 ticks, acelerado 10× para que cada ciclo se vea en unos dos minutos; la hora se conserva al guardar y el HUD indica si es de día o de noche.
- Antorchas colocables y rompibles, fabricadas en grupos de cuatro con carbón y un palo. Iluminan el entorno con un conjunto M3G fijo de tres luces cercanas para limitar el costo de memoria.
- Romper/colocar bloques, partículas de rotura, daño progresivo con grietas sobre la cara apuntada, seleccionar la barra rápida y guardar la partida en RMS.
- HUD con la barra de `gui.png`, corazones y burbujas de `icons.png` y sprites de bloque de `gui_blocks.png`; al sumergir la cámara aparece un filtro azul, el oxígeno se agota y provoca ahogamiento, y tanto la caída como el ahogamiento activan la animación de daño de Indev. La vida y estos daños solo se aplican en supervivencia.
- Crafteo por recetas: `#` abre el inventario 2×2 (tronco → 4 tablones, tablones → mesa y palos); usa `3` apuntando a una mesa para abrir `container.png`, centrado, con una lista desplazable de recetas y las no disponibles atenuadas. En la mesa se crean herramientas de madera, piedra, hierro, diamante y oro. El horno fue retirado.
- Criaturas 3D con modelos y UV adaptados a las skins Indev de 64 × 32, con todas las caras del cubo y una capa de vellón separada para la oveja. Los animales deambulan y huyen al recibir golpes; los zombis y esqueletos aparecen de noche, persiguen y hacen daño en supervivencia. A la luz del día se queman a cielo abierto con los primeros ocho cuadros de `fire.png`. Las criaturas caídas se inclinan durante su animación de muerte y después levantan polvo. `1` golpea a una criatura apuntada antes de intentar romper un bloque.
- Drops de criaturas: la primera vez que golpeas una oveja esquilada deja 1–3 bloques de lana y pierde visualmente el vellón; al morir, cerdos y zombis pueden dejar 0–2 trozos de cerdo crudo o plumas. Los objetos usan el pool fijo de drops y se recogen dentro de 1,5 bloques.
- Render 3D M3G con sectores de 16 × 8 × 16 bloques y un grupo fijo de seis criaturas para limitar memoria y geometría.

Es un demake inicial, no una reproducción completa de Indev: todavía no incluye los demás tipos de mundo de Indev. El reloj, la curva de luz, las animaciones y el tile 80 de la antorcha siguen el código de referencia; las luces cercanas y la aparición nocturna se adaptan al tamaño pequeño del mundo y al límite de memoria de Java ME.

La generación actual calcula primero una altura por columna y después rellena directamente el arreglo de bloques; mantiene esa altura temporal solo mientras coloca cuevas y vegetación. Como referencia de generación clásica, ClassiCube documenta las etapas de altura, capas, cuevas, agua y plantas. Su versión Dreamcast ejecuta el mismo cliente portable con KallistiOS y GLdc, no un generador exclusivo de Dreamcast ([algoritmo](https://github.com/ClassiCube/ClassiCube/wiki/Minecraft-Classic-map-generation-algorithm), [port Dreamcast](https://www.classicube.net/download/dreamcast)).

## Controles táctiles

Al iniciar, el juego pregunta si el dispositivo tiene pantalla táctil. Si eliges **Sí**, aparecen controles en pantalla: cruceta para moverse, arrastrar sobre el mundo para mirar, botones para saltar, romper/atacar, usar objetos, abrir el inventario y pausar. También puedes tocar una casilla de la barra rápida. Los menús y las pantallas de inventario admiten toques básicos. Si eliges **No**, se mantienen los controles físicos originales. La pregunta vuelve a aparecer al iniciar la aplicación para permitir cambiar de dispositivo o de modo.

## Controles

| Tecla | Acción |
| --- | --- |
| Cruceta | Mirar arriba, abajo, izquierda y derecha |
| 0 + cruceta arriba | Cambiar primera / tercera / segunda persona |
| 2 / 8 | Avanzar / retroceder |
| 4 / 6 | Desplazarse a la izquierda / derecha |
| 5 | Saltar / crear la receta elegida |
| 1 (mantener) | Golpear la criatura apuntada o romper el bloque; aparecen grietas según el daño |
| 3 | Comer cerdo crudo seleccionado; con otros objetos, colocar bloque o abrir la mesa apuntada |
| # | Abrir/cerrar el inventario y crafteo 2×2 |
| 7 / 9 | Cambiar bloque seleccionado |
| * | Pausar, guardar o regresar al menú |

En las pantallas de crafteo, usa `2`/`8` para elegir una receta, `5` para crearla y `#` para volver al juego.
En el selector de mundos, selecciona una partida y pulsa `1` para abrir la confirmación de borrado; `5` confirma y `0` cancela.
En supervivencia, las casillas vacías del acceso rápido no muestran bloques y `7`/`9` recorren también esas casillas. Las herramientas de un solo uso se gastan según el material; picos, palas y hachas aceleran los bloques que les corresponden, y solo el pico recoge piedra, adoquín y minerales. La piedra deja adoquín. La azada convierte tierra o césped expuestos en tierra de cultivo y puede producir semillas. La espada pierde dos puntos de durabilidad al romper un bloque. La rotura conserva las penalizaciones de Indev por estar en el aire o bajo el agua. Soltar `1` cancela el daño acumulado. En creativo los bloques se rompen al instante.

El cerdo crudo cura 3 puntos de vida como en Indev; solo se consume si falta vida y el stack tiene tamaño uno.

El jugador mide 0,6 × 1,8 bloques y la cámara está a 1,62 bloques sobre sus pies, igual que las medidas clásicas de Indev.

Si falla M3G al iniciar el mundo o al dibujar, la pantalla de diagnóstico muestra la etapa, el tipo y mensaje de la excepción, el perfil/configuración solicitados por el JAD y los perfiles/configuración que soporta el emulador. `microedition.profiles` describe las capacidades del runtime; por ejemplo, un emulador puede anunciar MIDP 2.1 aunque el MIDlet solicite MIDP 2.0. Usa `2`/`8` para recorrer el texto y `5` para volver al menú. El valor M3G reportado por el runtime permite distinguir una API agregada al classpath de una implementación disponible en el emulador.

## Memoria y compatibilidad

El objetivo es ejecutarse en un heap Java de **4 MiB o más**. Las decisiones para mantener bajo el consumo son:

- 55 296 bytes para el mundo (un byte por bloque) y unos pocos bytes de estado/inventario.
- Una sola imagen de terreno de 256 × 256 píxeles, cargada una vez y compartida por todas las mallas.
- 27 sectores de datos como máximo, pero las mallas M3G solo se crean para los sectores que intersectan la vista y el alcance de la cámara. Al girar o alejarse, las mallas fuera de vista se quitan de la escena y se liberan; vuelven a construirse desde los bytes del mundo al entrar en vista.
- No hay un límite global de triángulos que recorte terreno. Se descartan caras ocultas antes de crear las mallas y los bloques visibles se agrupan por tile para compartir apariencia y reducir nodos.
- Geometría cuantizada a `short`, una apariencia compartida y buffers de trabajo reutilizados para leer bloques y agrupar caras al reconstruir sectores.
- Al editar un bloque se reconstruyen solo los sectores afectados que están cargados; los sectores fuera de vista se construyen con el bloque actualizado al volver a aparecer.
- Las partículas de rotura reproducen la cuadrícula 4 × 4 × 4 de Indev con 64 ranuras fijas y una sola malla; cada fragmento reutiliza un recorte del atlas y no crea objetos durante la actualización.
- Seis criaturas como máximo y texturas compartidas de 64 × 32 píxeles para las cinco especies; los modelos M3G se construyen una vez al crear el renderizador.
- Se libera la imagen de título al entrar al mundo.
- RMS guarda un registro del tamaño aproximado del mundo; algunos teléfonos pueden imponer un límite menor al tamaño de registro.

El heap de 4 MiB es el mínimo de diseño, no una garantía para cada implementación: M3G puede reservar memoria nativa además del heap Java, y cada fabricante fija sus propios límites. El teléfono debe incluir JSR-184 y permitir el uso de la textura/M3G con un heap de al menos 4 MiB. Java ME sin M3G no puede ejecutar este renderizador.

## Compilar en NetBeans

El repositorio es un proyecto Java ME de NetBeans; no necesita `build.bat`. Abre la carpeta del repositorio como proyecto y usa **Build** o **Clean and Build**. NetBeans genera `MeCraft.jar` y `MeCraft.jad` dentro de `dist/`; **Run** lo inicia en el emulador seleccionado.

El proyecto está configurado para Oracle Java ME SDK 3.2, CLDC 1.1, MIDP 2.0, M3G 1.1 (JSR-184) y MMAPI (JSR-135). Si NetBeans muestra que el dispositivo soporta MIDP 2.1, eso describe sus capacidades; el perfil solicitado por el MIDlet sigue siendo MIDP 2.0. Puedes comprobarlo en **Project Properties > Platform > Device Profile** y en **Application Descriptor > Attributes**.

Si la plataforma SDK 3.2 no aparece en NetBeans, regístrala en **Tools > Java Platforms** y selecciona el dispositivo y las APIs opcionales **JSR-184** y **JSR-135** en las propiedades del proyecto. El proyecto usa Java 1.3 y pre-verificación CLDC durante la compilación. Los PNG y las cinco pistas MIDI deben estar en `src/mecraft/` y se empaquetan bajo `/mecraft/` en el JAR; el cargador también acepta esos recursos en la raíz del JAR. Si falta MMAPI o una pista, el juego sigue funcionando sin música.

Para instalar manualmente, transfiere ambos archivos de `dist/` al teléfono o abre `MeCraft.jad` en el emulador Java ME.



