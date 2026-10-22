# MeCraft (demake para Java ME)

MeCraft es un prototipo 3D para teléfonos con **MIDP 2.0, CLDC 1.1 y M3G (JSR-184)**. Toma como referencia las ideas visibles de Minecraft Indev 20100223 —mundos de isla, supervivencia/creativo, bloques y barra rápida— y usa el atlas y la pantalla de título incluidos en este repositorio. El código Java de este demake se ha escrito para MeCraft; no copia clases del cliente de referencia.

Referencia consultada: [EaglerPorts/in-20100223](https://github.com/EaglerPorts/in-20100223). Consulta [NOTICE.md](NOTICE.md) para la atribución.

## Estado jugable

- Mundo de isla procedural de 48 × 24 × 48 bloques, con costa, agua, árboles, carbón, hierro y roca madre.
- Modo supervivencia con inventario y modo creativo con bloques ilimitados.
- Cámara en primera persona, movimiento, colisión, gravedad y salto.
- Romper/colocar bloques, seleccionar la barra rápida y guardar la partida en RMS.
- Render 3D M3G con sectores de 16 × 8 × 16 bloques y un atlas compartido.

Es un demake inicial, no una reproducción completa de Indev: todavía no incluye crafteo, criaturas, día/noche ni los demás tipos de mundo de Indev.

## Controles

| Tecla | Acción |
| --- | --- |
| 2 / 8 | Avanzar / retroceder |
| 4 / 6 | Girar |
| 1 / 3 | Desplazarse lateralmente |
| 5 | Romper el bloque apuntado / confirmar en menús |
| 0 | Colocar el bloque seleccionado |
| 7 / 9 | Cambiar bloque seleccionado |
| # | Saltar |
| * | Pausar, guardar o regresar al menú |

En algunos teléfonos los comandos de pantalla reemplazan a las teclas `*`, `5` y `0`.

Si falla M3G al iniciar el mundo o al dibujar, la pantalla de diagnóstico muestra la etapa, el tipo y mensaje de la excepción, el perfil/configuración solicitados por el JAD y los perfiles/configuración que soporta el emulador. `microedition.profiles` describe las capacidades del runtime; por ejemplo, un emulador puede anunciar MIDP 2.1 aunque el MIDlet solicite MIDP 2.0. Usa `2`/`8` para recorrer el texto y `5` para volver al menú. El valor M3G reportado por el runtime permite distinguir una API agregada al classpath de una implementación disponible en el emulador.

## Memoria y compatibilidad

El objetivo es ejecutarse en un heap Java de **4 MiB o más**. Las decisiones para mantener bajo el consumo son:

- 55 296 bytes para el mundo (un byte por bloque) y unos pocos bytes de estado/inventario.
- Una sola imagen de terreno de 256 × 256 píxeles, cargada una vez y compartida por todas las mallas.
- 27 sectores como máximo; no se crea un objeto de escena por bloque.
- Geometría cuantizada a `short`, una apariencia compartida y arreglos temporales de CPU descartables después de construir cada sector.
- Al editar un bloque se reconstruye su sector y, solo si cae en un borde, los sectores vecinos.
- Se libera la imagen de título al entrar al mundo.
- RMS guarda un registro del tamaño aproximado del mundo; algunos teléfonos pueden imponer un límite menor al tamaño de registro.

El heap de 4 MiB es el mínimo de diseño, no una garantía para cada implementación: M3G puede reservar memoria nativa además del heap Java, y cada fabricante fija sus propios límites. El teléfono debe incluir JSR-184 y permitir el uso de la textura/M3G con un heap de al menos 4 MiB. Java ME sin M3G no puede ejecutar este renderizador.

## Compilar en NetBeans

El repositorio es un proyecto Java ME de NetBeans; no necesita `build.bat`. Abre la carpeta del repositorio como proyecto y usa **Build** o **Clean and Build**. NetBeans genera `MeCraft.jar` y `MeCraft.jad` dentro de `dist/`; **Run** lo inicia en el emulador seleccionado.

El proyecto está configurado para Oracle Java ME SDK 3.2, CLDC 1.1, MIDP 2.0 y M3G 1.1 (JSR-184). Si NetBeans muestra que el dispositivo soporta MIDP 2.1, eso describe sus capacidades; el perfil solicitado por el MIDlet sigue siendo MIDP 2.0. Puedes comprobarlo en **Project Properties > Platform > Device Profile** y en **Application Descriptor > Attributes**.

Si la plataforma SDK 3.2 no aparece en NetBeans, regístrala en **Tools > Java Platforms** y selecciona el dispositivo y la API opcional **JSR-184** en las propiedades del proyecto. El proyecto usa Java 1.3 y pre-verificación CLDC durante la compilación. Los PNG se guardan en `resources/mecraft/` y se empaquetan bajo `/mecraft/` en el JAR.

Para instalar manualmente, transfiere ambos archivos de `dist/` al teléfono o abre `MeCraft.jad` en el emulador Java ME.



