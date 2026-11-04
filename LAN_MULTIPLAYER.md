# MeCraft LAN Multiplayer

## Player experience

- **Host:** open a saved world, choose LAN hosting, and receive a six-digit room code.
- **Join:** choose Join LAN, enter the six digits, and discover the matching host on the same Wi-Fi network.
- The code is valid only while that host session is running. Closing the world ends the session.
- Initial target: four players total (one host plus up to three guests).

## Network layout

- UDP discovery on port **27186**. A joining device broadcasts a room-code query; hosts answer only when the code matches.
- TCP gameplay connection on port **27185**. TCP carries the handshake and reliable game events.
- No Internet account, central server, router configuration, or manually entered IP address.
- A host is authoritative: clients send requested actions; the host validates and broadcasts accepted changes.

## Protocol

- Discovery packets use the MCLN magic value and protocol version.
- TCP frames use a two-byte unsigned payload length followed by the payload.
- Maximum frame size is 4096 bytes.
- Initial message families: handshake, player state, player leave, block changes, world time, inventory changes, entity state, ping and pong.
- Room codes are six decimal digits and must be treated as session locators, not as strong authentication.

## Implementation status

### Implemented in the current branch

- Six-digit room-code generation and validation.
- UDP broadcast discovery on port **27186**, including repeated query attempts and a seven-second search timeout.
- TCP host listener on port **27185**.
- Versioned HELLO / ACCEPT / REJECT handshake with room-code validation.
- One host plus up to three connected clients.
- Background network threads, bounded TCP frames, disconnect cleanup and peer count updates.
- Bidirectional framed-packet transport API: clients can send to the host; the host can send to one guest or broadcast; received payloads are delivered to the listener with the sender's player ID.
- Main-menu LAN entry, host-code lobby, numeric join-code editor, keyboard and touch navigation.
- Optional MIDP permissions requested in the generated JAD/manifest.

### Still to implement

1. **Gameplay integration (not yet implemented):** connect the LAN lobby to world creation/loading, assign the host's world as authoritative, and decode the packet callbacks into game-state updates.
2. **Player synchronization:** remote player positions, yaw, pitch, skins and spawn points.
3. **World synchronization:** initial world snapshot/seed and edit transfer, block changes with metadata, time and inventory.
4. **Entities:** dropped items, mobs, boats, minecarts and damage.
5. **Hardening:** reconnection, duplicate room-code handling, packet validation, latency handling and low-memory testing.

The current LAN screen establishes a network lobby only. The transport now supports bidirectional gameplay frames and dispatches received payloads to the game listener, but the listener does not yet apply them. It does **not** yet start a shared world or synchronize gameplay.

## Compatibility and limitations

- Use only MIDP 2.0 / CLDC 1.1 APIs (javax.microedition.io); do not depend on Java SE networking classes.
- Network permissions and incoming connections depend on the phone, Java runtime and Wi-Fi access point. Some hotspots isolate clients, which prevents LAN discovery even when both devices show the same Wi-Fi name.
- UDP broadcast support and server sockets must be verified on the target JBed runtime before treating LAN multiplayer as working.
- Keep network I/O off the render/game loop. Bound packet sizes and queue sizes to protect the S730's limited memory.
- A six-digit code can collide. Hosts must detect an active duplicate code on their local session and regenerate; clients should show a clear not-found/timeout result.

## How to test the LAN lobby

1. Install the same MeCraft build on two MIDP 2.0 devices.
2. Connect both devices to the same Wi-Fi network. The access point must allow devices to communicate with each other.
3. On the host, open **Multijugador LAN → Crear partida** and share the six-digit code.
4. On the guest, open **Multijugador LAN → Unirse con codigo**, enter the code and accept any MIDP network permission prompts.
5. The host should show **2/4 players** and the guest should show **Connection established**.
6. Press **0**, *****, or the left softkey to disconnect and close the lobby.

If discovery fails, check that both devices are on the same local network, that the router/hotspot does not enable client isolation, and that the JBed runtime permits UDP datagrams and TCP server sockets.
