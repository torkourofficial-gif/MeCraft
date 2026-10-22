# MeCraft (demake para Java ME)

MeCraft es un prototipo 3D para teléfonos con **MIDP 2.0, CLDC 1.1 y M3G (JSR-184)**. Toma como referencia las ideas visibles de Minecraft Indev 20100223 —mundos de isla, supervivencia/creativo, bloques y barra rápida— y usa el atlas y la pantalla de título incluidos en este repositorio. El código Java de este demake se ha escrito para MeCraft; no copia clases del cliente de referencia.

Referencia consultada: [EaglerPorts/in-20100223](https://github.com/EaglerPorts/in-20100223). Consulta [NOTICE.md](NOTICE.md) para la atribución.

## Estado jugable

- Mundo de isla procedural de 48 × 24 × 48 bloques, con costa, agua, árboles, carbón, hierro y roca madre.
- Modo supervivencia empieza con el inventario vacío; los bloques rotos se recogen, y modo creativo tiene bloques ilimitados.
- Cámara en primera persona, movimiento, colisión, gravedad y salto.
- Ciclo de día y noche basado en la curva de Indev de 24 000 ticks, acelerado 10× para que cada ciclo se vea en unos dos minutos; la hora se conserva al guardar y el HUD indica si es de día o de noche.
- Antorchas colocables y rompibles, fabricadas en grupos de cuatro con carbón y un palo. Iluminan el entorno con un conjunto M3G fijo de tres luces cercanas para limitar el costo de memoria.
- Romper/colocar bloques, partículas de rotura, daño progresivo con grietas sobre la cara apuntada, seleccionar la barra rápida y guardar la partida en RMS.
- HUD con la barra de `gui.png`, corazones y burbujas de `icons.png` y sprites de bloque de `gui_blocks.png`; al sumergir la cámara aparece un filtro azul, el oxígeno se agota y provoca ahogamiento, y tanto la caída como el ahogamiento activan la animación de daño de Indev. La vida y estos daños solo se aplican en supervivencia.
- Crafteo por recetas: `#` abre el inventario 2×2 (tronco → 4 tablones, tablones → mesa y palos); usa `3` apuntando a una mesa para abrir `container.png`, centrado, con una lista desplazable de recetas y las no disponibles atenuadas. En la mesa se crean herramientas de madera, piedra, hierro, diamante y oro. El horno fue retirado.
- Criaturas 3D con modelos y UV adaptados a las skins Indev de 64 × 32, además de una capa de vellón separada para la oveja. Los animales deambulan, huyen al recibir golpes y los zombis aparecen al anochecer, persiguen y hacen 5 puntos de daño cada segundo en supervivencia; desaparecen al amanecer. `1` golpea a una criatura apuntada antes de intentar romper un bloque; la mano hace 1 punto y las espadas hacen daño según su material.
- Drops de criaturas: la primera vez que golpeas una oveja esquilada deja 1–3 bloques de lana y pierde visualmente el vellón; al morir, cerdos y zombis pueden dejar 0–2 trozos de cerdo crudo o plumas. Los objetos usan el pool fijo de drops y se recogen dentro de 1,5 bloques.
- Render 3D M3G con sectores de 16 × 8 × 16 bloques y un grupo fijo de cuatro criaturas para limitar memoria y geometría.

Es un demake inicial, no una reproducción completa de Indev: todavía no incluye los demás tipos de mundo de Indev. El reloj, la curva de luz, las animaciones y el tile 80 de la antorcha siguen el código de referencia; las luces cercanas y la aparición nocturna se adaptan al tamaño pequeño del mundo y al límite de memoria de Java ME.

## Controles

| Tecla | Acción |
| --- | --- |
| Cruceta | Mirar arriba, abajo, izquierda y derecha |
| 2 / 8 | Avanzar / retroceder |
| 4 / 6 | Desplazarse a la izquierda / derecha |
| 5 | Saltar / crear la receta elegida |
| 1 (mantener) | Golpear la criatura apuntada o romper el bloque; aparecen grietas según el daño |
| 3 | Colocar bloque; si apuntas a una mesa de crafteo, abrir su interfaz |
| # | Abrir/cerrar el inventario y crafteo 2×2 |
| 7 / 9 | Cambiar bloque seleccionado |
| * | Pausar, guardar o regresar al menú |

En las pantallas de crafteo, usa `2`/`8` para elegir una receta, `5` para crearla y `#` para volver al juego.
En supervivencia, las casillas vacías del acceso rápido no muestran bloques y `7`/`9` recorren también esas casillas. Las herramientas de un solo uso se gastan según el material; picos, palas y hachas aceleran los bloques que les corresponden, y solo el pico recoge piedra, adoquín y minerales. La piedra deja adoquín. La azada convierte tierra o césped expuestos en tierra de cultivo y puede producir semillas. La espada pierde dos puntos de durabilidad al romper un bloque. La rotura conserva las penalizaciones de Indev por estar en el aire o bajo el agua. Soltar `1` cancela el daño acumulado. En creativo los bloques se rompen al instante.

El jugador mide 0,6 × 1,8 bloques y la cámara está a 1,62 bloques sobre sus pies, igual que las medidas clásicas de Indev.

Si falla M3G al iniciar el mundo o al dibujar, la pantalla de diagnóstico muestra la etapa, el tipo y mensaje de la excepción, el perfil/configuración solicitados por el JAD y los perfiles/configuración que soporta el emulador. `microedition.profiles` describe las capacidades del runtime; por ejemplo, un emulador puede anunciar MIDP 2.1 aunque el MIDlet solicite MIDP 2.0. Usa `2`/`8` para recorrer el texto y `5` para volver al menú. El valor M3G reportado por el runtime permite distinguir una API agregada al classpath de una implementación disponible en el emulador.

## Memoria y compatibilidad

El objetivo es ejecutarse en un heap Java de **4 MiB o más**. Las decisiones para mantener bajo el consumo son:

- 55 296 bytes para el mundo (un byte por bloque) y unos pocos bytes de estado/inventario.
- Una sola imagen de terreno de 256 × 256 píxeles, cargada una vez y compartida por todas las mallas.
- 27 sectores como máximo; no se crea un objeto de escena por bloque.
- Geometría cuantizada a `short`, una apariencia compartida y arreglos temporales de CPU descartables después de construir cada sector.
- Al editar un bloque se reconstruye su sector y, solo si cae en un borde, los sectores vecinos.
- Las partículas de rotura reproducen la cuadrícula 4 × 4 × 4 de Indev con 64 ranuras fijas y una sola malla; cada fragmento reutiliza un recorte del atlas y no crea objetos durante la actualización.
- Cuatro criaturas como máximo, con tres texturas de 64 × 32 píxeles compartidas por especie y modelos M3G construidos una vez al crear el renderizador.
- Se libera la imagen de título al entrar al mundo.
- RMS guarda un registro del tamaño aproximado del mundo; algunos teléfonos pueden imponer un límite menor al tamaño de registro.

El heap de 4 MiB es el mínimo de diseño, no una garantía para cada implementación: M3G puede reservar memoria nativa además del heap Java, y cada fabricante fija sus propios límites. El teléfono debe incluir JSR-184 y permitir el uso de la textura/M3G con un heap de al menos 4 MiB. Java ME sin M3G no puede ejecutar este renderizador.

## Compilar en NetBeans

El repositorio es un proyecto Java ME de NetBeans; no necesita `build.bat`. Abre la carpeta del repositorio como proyecto y usa **Build** o **Clean and Build**. NetBeans genera `MeCraft.jar` y `MeCraft.jad` dentro de `dist/`; **Run** lo inicia en el emulador seleccionado.

El proyecto está configurado para Oracle Java ME SDK 3.2, CLDC 1.1, MIDP 2.0 y M3G 1.1 (JSR-184). Si NetBeans muestra que el dispositivo soporta MIDP 2.1, eso describe sus capacidades; el perfil solicitado por el MIDlet sigue siendo MIDP 2.0. Puedes comprobarlo en **Project Properties > Platform > Device Profile** y en **Application Descriptor > Attributes**.

Si la plataforma SDK 3.2 no aparece en NetBeans, regístrala en **Tools > Java Platforms** y selecciona el dispositivo y la API opcional **JSR-184** en las propiedades del proyecto. El proyecto usa Java 1.3 y pre-verificación CLDC durante la compilación. Los PNG se guardan en `resources/mecraft/` y se empaquetan bajo `/mecraft/` en el JAR.

Para instalar manualmente, transfiere ambos archivos de `dist/` al teléfono o abre `MeCraft.jad` en el emulador Java ME.



