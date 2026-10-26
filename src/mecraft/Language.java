package mecraft;

/** Compact two-language table for the in-game interface. English is default. */
final class Language {
    static final int ENGLISH = 0;
    static final int SPANISH = 1;
    private static int current = ENGLISH;

    private static final String[] SPANISH_TEXT = new String[] {
        "Un jugador", "Controles", "Ajustes", "Salir", "2/8 elegir   5 aceptar",
        "Frameskip: Desactivado", "Frameskip: 1 de cada 2", "Frameskip: 2 de cada 3",
        "Sensibilidad: Muy baja", "Sensibilidad: Baja", "Sensibilidad: Normal",
        "Sensibilidad: Alta", "Sensibilidad: Muy alta", "Sensibilidad", "Idioma",
        "English", "Español", "Skin del jugador", "5 abrir", "0 volver",
        "2/8 elegir   4/6 cambiar   5 abrir", "Pulsa una tecla nueva (0 cancela)",
        "5: reasignar   7: restaurar fila", "2/8 mover   0 volver",
        "Esa tecla ya esta asignada", "Asignacion cancelada", "Control guardado",
        "Control restaurado", "Mirar arriba", "Mirar abajo", "Mirar izquierda",
        "Mirar derecha", "Avanzar", "Retroceder", "Desplazarse izquierda",
        "Desplazarse derecha", "Saltar / volar", "Romper / atacar",
        "Usar / colocar / comer", "Inventario", "Casilla anterior", "Casilla siguiente",
        "Pausa", "Descender (creativo)", "Dpad arriba", "Dpad abajo",
        "Dpad izquierda", "Dpad derecha", "Cruceta: mirar alrededor",
        "2 / 8: avanzar / retroceder", "4 / 6: desplazarse",
        "5: saltar; doble toque: volar", "1: romper / atacar",
        "3: colocar / usar / comer", "#: inventario o crafteo", "7 / 9: cambiar casilla",
        "*: pausa", "Skins", "No se pudo mostrar la vista 3D", "Falta skin",
        "4/6 elegir   5 usar   0 volver", "Seleccionar mundo", "Creativo",
        "Supervivencia", "Crear mundo nuevo", "Volver", "2/8 elegir  5 abrir  1 borrar",
        "Borrar mundo", "Mundo", "No se pudo borrar", "Se eliminara para siempre",
        "5 confirmar  0 cancelar", "Crear mundo", "Nombre", "Modo",
        "5 editar/cambiar/elegir", "Noche", "Día", "Volando  5 subir  0 bajar",
        "5 dos veces: volar", "Recetas de la mesa", "Crafteo 2x2", "5 crear",
        "No disponible", "# cerrar  |  2/8 lista", "Inventario creativo",
        "4/6 mover  2/8 fila  5 tomar  # cerrar", "Menú del juego",
        "2/8 elegir   5 aceptar   0 menú", "2/8 ver detalles  5 volver",
        "Pulsa 5 para volver", "Aire", "Césped", "Tierra", "Piedra", "Arena",
        "Agua", "Tronco", "Hojas", "Roca madre", "Carbón", "Hierro", "Tablones",
        "Mesa de crafteo", "Piedra labrada", "Tierra de cultivo", "Flor roja",
        "Flor amarilla", "Antorcha", "Lana", "Puerta de madera", "Palo", "Espada",
        "Pala", "Pico", "Hacha", "Azada", "madera", "piedra", "hierro", "diamante",
        "oro", "Lingote de hierro", "Diamante", "Lingote de oro", "Semillas",
        "Cerdo crudo", "Pluma", "Blanca", "Naranja", "Magenta", "Azul claro",
        "Amarilla", "Lima", "Rosa", "Gris", "Gris claro", "Cian", "Morada", "Azul",
        "Marrón", "Verde", "Roja", "Negra", "4 Tablones", "Mesa de trabajo",
        "4 Palos", "4 Antorchas", "3 Puertas", "Mundo guardado", "preparando mundo",
        "cargando /mecraft/textures.png y creando escena M3G",
        "No hay memoria suficiente para crear el mundo. Libera memoria y vuelve a intentarlo.",
        "No se pudo cargar el mundo guardado.",
        "No hay memoria suficiente para cargar el mundo guardado.",
        "No se pudieron cargar las texturas de jugador.",
        "M3G agotó el heap. El diseño apunta a móviles con heap total de 4 MiB o más.",
        "Desactivado", "1 de cada 2", "2 de cada 3", "Nombre del mundo", "Aceptar", "Cancelar",
        "Puntuación: ", "Fin del juego!", "Generar nuevo nivel...", "Cargar nivel..",
        "Guardar y menú", "Guardar y salir", "Etapa", "Tipo", "Mensaje",
        "no informado", "no disponible", "arriba", "abajo", "izquierda", "derecha",
        "Perfil solicitado por la app (JAD)", "Perfiles que soporta el emulador",
        "Config solicitada (JAD)", "Config que soporta el emulador"
    };

    private static final String[] ENGLISH_TEXT = new String[] {
        "Singleplayer", "Controls", "Settings", "Quit", "2/8 select   5 confirm",
        "Frameskip: Off", "Frameskip: 1 of 2", "Frameskip: 2 of 3",
        "Sensitivity: Very low", "Sensitivity: Low", "Sensitivity: Normal",
        "Sensitivity: High", "Sensitivity: Very high", "Sensitivity", "Language",
        "English", "Español", "Player skin", "5 open", "0 back",
        "2/8 select   4/6 change   5 open", "Press a key (0 cancels)",
        "5: rebind   7: reset row", "2/8 move   0 back",
        "That key is already assigned", "Binding cancelled", "Control saved",
        "Control reset", "Look up", "Look down", "Look left", "Look right", "Move forward",
        "Move backward", "Strafe left", "Strafe right", "Jump / fly", "Break / attack",
        "Use / place / eat", "Inventory", "Previous slot", "Next slot", "Pause",
        "Descend (creative)", "D-pad up", "D-pad down", "D-pad left", "D-pad right",
        "D-pad: look around", "2 / 8: forward / back", "4 / 6: strafe",
        "5: jump; double tap: fly", "1: break / attack", "3: place / use / eat",
        "#: inventory or crafting", "7 / 9: change slot", "*: pause", "Skins",
        "Could not show the 3D preview", "Missing skin", "4/6 select   5 use   0 back",
        "Select world", "Creative", "Survival", "Create new world", "Back",
        "2/8 select  5 open  1 delete", "Delete world", "World", "Could not delete",
        "This cannot be undone", "5 confirm  0 cancel", "Create world", "Name", "Mode",
        "5 edit/change/select", "Night", "Day", "Flying  5 up  0 down",
        "Double-tap 5: fly", "Crafting table recipes", "2x2 crafting", "5 craft",
        "Unavailable", "# close  |  2/8 list", "Creative blocks",
        "4/6 move  2/8 row  5 take  # close", "Game menu",
        "2/8 select   5 confirm   0 menu", "2/8 details  5 back", "Press 5 to return",
        "Air", "Grass", "Dirt", "Stone", "Sand", "Water", "Log", "Leaves", "Bedrock",
        "Coal", "Iron", "Planks", "Crafting table", "Cobblestone", "Farmland",
        "Red flower", "Yellow flower", "Torch", "Wool", "Wooden door", "Stick", "Sword",
        "Shovel", "Pickaxe", "Axe", "Hoe", "wood", "stone", "iron", "diamond", "gold",
        "Iron ingot", "Diamond", "Gold ingot", "Seeds", "Raw porkchop", "Feather",
        "White", "Orange", "Magenta", "Light blue", "Yellow", "Lime", "Pink", "Gray",
        "Light gray", "Cyan", "Purple", "Blue", "Brown", "Green", "Red", "Black",
        "4 Planks", "Crafting table", "4 Sticks", "4 Torches", "3 Doors", "Saved world",
        "preparing world", "loading /mecraft/textures.png and creating M3G scene",
        "Not enough memory to create the world. Free memory and try again.",
        "Could not load the saved world.",
        "Not enough memory to load the saved world.",
        "Could not load player skins.",
        "M3G ran out of heap. The design targets phones with at least 4 MiB total heap.",
        "Off", "1 of 2", "2 of 3", "World name", "Accept", "Cancel", "Score: ",
        "Game over!", "Generate new level...", "Load level..", "Save and menu",
        "Save and quit", "Stage", "Type", "Message", "not reported", "unavailable",
        "up", "down", "left", "right", "App profile (JAD)", "Profiles supported by emulator",
        "Requested config (JAD)", "Config supported by emulator"
    };

    private Language() { }

    static void set(int language) {
        current = language == SPANISH ? SPANISH : ENGLISH;
    }

    static int current() { return current; }

    static boolean isEnglish() { return current == ENGLISH; }

    static String text(String value) {
        if (value == null) return null;
        boolean english = current == ENGLISH;
        if (english && value.startsWith("Decoración ")) return "Decoration " + value.substring(11);
        if (!english && value.startsWith("Decoration ")) return "Decoración " + value.substring(11);
        if (value.startsWith("Lana ")) return (english ? "Wool " : "Lana ") + text(value.substring(5));
        if (value.startsWith("Wool ")) return (english ? "Wool " : "Lana ") + text(value.substring(5));
        if (value.startsWith("4 ")) return "4 " + text(value.substring(2));
        if (value.startsWith("3 ")) return "3 " + text(value.substring(2));
        if (english && value.startsWith("Falta skin")) return "Missing skin" + value.substring(10);
        if (!english && value.startsWith("Missing skin")) return "Falta skin" + value.substring(12);
        if (value.startsWith("Nombre: ")) return (english ? "Name: " : "Nombre: ") + text(value.substring(8));
        if (value.startsWith("Name: ")) return (english ? "Name: " : "Nombre: ") + text(value.substring(6));
        if (value.startsWith("Modo: ")) return (english ? "Mode: " : "Modo: ") + text(value.substring(6));
        if (value.startsWith("Mode: ")) return (english ? "Mode: " : "Modo: ") + text(value.substring(6));
        if (value.startsWith("Frameskip: ")) return "Frameskip: " + text(value.substring(11));
        if (value.startsWith("Sensitivity: ")) return (english ? "Sensitivity: " : "Sensibilidad: ") + text(value.substring(13));
        if (value.startsWith("Sensibilidad: ")) return (english ? "Sensitivity: " : "Sensibilidad: ") + text(value.substring(14));
        if (value.startsWith("Idioma: ")) return (english ? "Language: " : "Idioma: ") + text(value.substring(8));
        if (value.startsWith("Language: ")) return (english ? "Language: " : "Idioma: ") + text(value.substring(10));
        if (value.startsWith("Dpad ")) return (english ? "D-pad " : "Dpad ") + text(value.substring(5));
        if (value.startsWith("D-pad ")) return (english ? "D-pad " : "Dpad ") + text(value.substring(6));
        if (value.startsWith("Espada de ")) return english ? text(value.substring(10)) + " sword" : value;
        if (value.startsWith("Pala de ")) return english ? text(value.substring(8)) + " shovel" : value;
        if (value.startsWith("Pico de ")) return english ? text(value.substring(8)) + " pickaxe" : value;
        if (value.startsWith("Hacha de ")) return english ? text(value.substring(9)) + " axe" : value;
        if (value.startsWith("Azada de ")) return english ? text(value.substring(9)) + " hoe" : value;
        if (!english && value.endsWith(" sword")) return "Espada de " + text(value.substring(0, value.length() - 6));
        if (!english && value.endsWith(" shovel")) return "Pala de " + text(value.substring(0, value.length() - 7));
        if (!english && value.endsWith(" pickaxe")) return "Pico de " + text(value.substring(0, value.length() - 8));
        if (!english && value.endsWith(" axe")) return "Hacha de " + text(value.substring(0, value.length() - 4));
        if (!english && value.endsWith(" hoe")) return "Azada de " + text(value.substring(0, value.length() - 4));
        if (english) {
            for (int i = 0; i < SPANISH_TEXT.length; i++)
                if (SPANISH_TEXT[i].equals(value)) return ENGLISH_TEXT[i];
        } else {
            for (int i = 0; i < ENGLISH_TEXT.length; i++)
                if (ENGLISH_TEXT[i].equals(value)) return SPANISH_TEXT[i];
        }
        return value;
    }
}
