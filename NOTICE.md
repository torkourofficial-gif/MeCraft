# Créditos y licencias

MeCraft adapta a MIDP/M3G varias reglas consultadas en **Minecraft Indev 20100223**, incluidas las medidas del jugador, la fuerza de rotura por dureza, las restricciones de recolección, el comportamiento de los fragmentos, la geometría y la transformación de primera persona del brazo derecho y su curva de balanceo. La mecha, movimiento, destello y rayos de la explosión de TNT también siguen la lógica observada en Indev. Los modelos de cerdo, oveja, zombi, creeper y esqueleto y sus movimientos toman como referencia `ModelQuadruped`, `ModelSheep`, `ModelBiped`, `ModelZombie`, `ModelCreeper` y `ModelSkeleton`; la conducta de persecución, huida, mecha, explosión y flechas se reimplementó para este mundo acotado. La referencia técnica es [EaglerPorts/in-20100223](https://github.com/EaglerPorts/in-20100223); su README atribuye el proyecto a [Colbster937](https://github.com/colbster937) y permite forks con atribución. Esta adaptación usa estructuras acotadas para Java ME y no incluye clases copiadas literalmente del cliente original.

Los PNG que ya estaban en este repositorio se conservan sin modificar. `src/mecraft/sheep_fur.png`, `src/mecraft/creeper.png` y `src/mecraft/skeleton.png` se añadieron sin modificar desde `resources/mob/` de [EaglerPorts/in-20100223](https://github.com/EaglerPorts/in-20100223). La titularidad y licencia de las texturas corresponden a sus autores originales; este aviso no concede derechos adicionales sobre esos assets.

`src/mecraft/fire.png` es una secuencia de fotogramas generada con la lógica de `TextureFlamesFX` de [EaglerPorts/in-20100223](https://github.com/EaglerPorts/in-20100223), que en Indev calcula la textura animada del fuego en vez de cargarla desde un PNG estático.

El código de MeCraft en `src/mecraft/` es una implementación original del demake para MIDP/M3G. Confirma los derechos/licencias de las texturas antes de redistribuir un `.jar` que las incluya.

