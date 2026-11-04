package mecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import javax.microedition.io.Connector;
import javax.microedition.io.Datagram;
import javax.microedition.io.DatagramConnection;
import javax.microedition.io.ServerSocketConnection;
import javax.microedition.io.SocketConnection;

/**
 * Small LAN lobby transport. Networking always runs on worker threads.
 * This establishes a real TCP session but does not yet replicate the world.
 */
final class LanTransport implements Runnable {
    interface Listener {
        void onLanStatus(String status);
        void onLanPeerCount(int count);
        void onLanConnected(String hostName);
        void onLanError(String message);
    }

    private static final int HOST = 1;
    private static final int JOIN = 2;
    private final Listener listener;
    private final int mode;
    private final String roomCode;
    private final String playerName;
    private volatile boolean running = true;
    private volatile boolean discoveryFound;
    private ServerSocketConnection server;
    private DatagramConnection discovery;
    private SocketConnection clientSocket;
    private final SocketConnection[] peerSockets =
            new SocketConnection[LanProtocol.MAX_PLAYERS - 1];
    private int peerCount;
    private int pendingPeers;
    private Thread worker;

    private LanTransport(Listener listener, int mode, String roomCode,
            String playerName) {
        this.listener = listener;
        this.mode = mode;
        this.roomCode = roomCode;
        this.playerName = playerName == null ? "Jugador" : playerName;
    }

    static LanTransport host(Listener listener, String roomCode, String playerName) {
        return new LanTransport(listener, HOST, roomCode, playerName);
    }

    static LanTransport join(Listener listener, String roomCode, String playerName) {
        return new LanTransport(listener, JOIN, roomCode, playerName);
    }

    void start() {
        if (worker != null) return;
        worker = new Thread(this);
        worker.start();
    }

    public void run() {
        try {
            if (mode == HOST) runHost();
            else runJoin();
        } catch (Throwable problem) {
            if (running) {
                String message = messageOf(problem);
                stop();
                notifyError(message);
            }
        }
    }

    private void runHost() throws IOException {
        server = (ServerSocketConnection)Connector.open(
                "socket://:" + LanProtocol.GAME_PORT);
        discovery = (DatagramConnection)Connector.open(
                "datagram://:" + LanProtocol.DISCOVERY_PORT);
        Thread beacon = new Thread(new Runnable() {
            public void run() { runDiscoveryResponder(); }
        });
        beacon.start();
        notifyStatus("Sala abierta. Esperando jugadores...");
        while (running) {
            SocketConnection accepted = (SocketConnection)server.acceptAndOpen();
            boolean allowed;
            synchronized (this) {
                allowed = peerCount + pendingPeers <
                        LanProtocol.MAX_PLAYERS - 1;
                if (allowed) pendingPeers++;
            }
            if (!allowed) {
                closeSocket(accepted);
            } else {
                Thread peer = new Thread(new PeerTask(accepted));
                peer.start();
            }
        }
    }

    private void runDiscoveryResponder() {
        while (running) {
            try {
                Datagram request = discovery.newDatagram(96);
                discovery.receive(request);
                DataInputStream input = new DataInputStream(
                        new ByteArrayInputStream(request.getData(),
                                request.getOffset(), request.getLength()));
                int magic = input.readInt();
                int version = input.readUnsignedByte();
                int operation = input.readUnsignedByte();
                String requestedCode = input.readUTF();
                if (magic != LanProtocol.DISCOVERY_MAGIC ||
                        version != LanProtocol.PROTOCOL_VERSION ||
                        operation != LanProtocol.DISCOVERY_QUERY ||
                        !roomCode.equals(requestedCode)) continue;

                Datagram reply = discovery.newDatagram(96);
                reply.setAddress(request);
                reply.writeInt(LanProtocol.DISCOVERY_MAGIC);
                reply.writeByte(LanProtocol.PROTOCOL_VERSION);
                reply.writeByte(LanProtocol.DISCOVERY_RESPONSE);
                reply.writeUTF(roomCode);
                reply.writeShort(LanProtocol.GAME_PORT);
                discovery.send(reply);
            } catch (Throwable problem) {
                if (running) notifyError("LAN UDP: " + messageOf(problem));
                return;
            }
        }
    }

    private void runJoin() throws IOException {
        notifyStatus("Buscando la partida en esta red Wi-Fi...");
        discovery = (DatagramConnection)Connector.open("datagram://");
        final DatagramConnection waitingConnection = discovery;
        Thread timeout = new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(7000L); } catch (InterruptedException ignored) { }
                if (running && !discoveryFound) closeDatagram(waitingConnection);
            }
        });
        timeout.start();

        Datagram request = discovery.newDatagram(96,
                "datagram://255.255.255.255:" + LanProtocol.DISCOVERY_PORT);
        request.writeInt(LanProtocol.DISCOVERY_MAGIC);
        request.writeByte(LanProtocol.PROTOCOL_VERSION);
        request.writeByte(LanProtocol.DISCOVERY_QUERY);
        request.writeUTF(roomCode);
        discovery.send(request);

        String hostAddress = null;
        while (running && !discoveryFound) {
            Datagram reply = discovery.newDatagram(96);
            discovery.receive(reply);
            DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(reply.getData(),
                            reply.getOffset(), reply.getLength()));
            int magic = input.readInt();
            int version = input.readUnsignedByte();
            int operation = input.readUnsignedByte();
            String receivedCode = input.readUTF();
            int port = input.readUnsignedShort();
            if (magic == LanProtocol.DISCOVERY_MAGIC &&
                    version == LanProtocol.PROTOCOL_VERSION &&
                    operation == LanProtocol.DISCOVERY_RESPONSE &&
                    roomCode.equals(receivedCode) &&
                    port == LanProtocol.GAME_PORT) {
                hostAddress = extractHost(reply.getAddress());
                if (hostAddress != null) discoveryFound = true;
            }
        }
        closeDatagram(discovery);
        discovery = null;
        if (!running) return;
        if (hostAddress == null) throw new IOException(
                "No se encontro esa sala. Comprueba el codigo y la red Wi-Fi.");

        notifyStatus("Encontrada. Conectando...");
        clientSocket = (SocketConnection)Connector.open(
                "socket://" + hostAddress + ":" + LanProtocol.GAME_PORT);
        DataOutputStream output = clientSocket.openDataOutputStream();
        DataInputStream input = clientSocket.openDataInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream hello = new DataOutputStream(bytes);
        hello.writeByte(LanProtocol.HELLO);
        hello.writeByte(LanProtocol.PROTOCOL_VERSION);
        hello.writeUTF(roomCode);
        hello.writeUTF(playerName);
        hello.flush();
        LanProtocol.writeFrame(output, bytes.toByteArray());

        byte[] response = LanProtocol.readFrame(input);
        if (response == null) throw new IOException("El anfitrion cerro la conexion.");
        DataInputStream accepted = new DataInputStream(
                new ByteArrayInputStream(response));
        int operation = accepted.readUnsignedByte();
        if (operation != LanProtocol.ACCEPT) {
            String reason = accepted.readUTF();
            throw new IOException(reason);
        }
        int playerId = accepted.readUnsignedByte();
        notifyConnected("Anfitrion");
        while (running) {
            byte[] packet = LanProtocol.readFrame(input);
            if (packet == null) break;
            // World/player replication messages are added in the next milestone.
        }
        if (running) notifyError("La conexion con el anfitrion termino.");
    }

    private final class PeerTask implements Runnable {
        private final SocketConnection socket;

        PeerTask(SocketConnection socket) {
            this.socket = socket;
        }

        public void run() {
            int slot = -1;
            boolean pendingReleased = false;
            DataInputStream input = null;
            DataOutputStream output = null;
            try {
                input = socket.openDataInputStream();
                output = socket.openDataOutputStream();
                byte[] packet = LanProtocol.readFrame(input);
                if (packet == null) throw new IOException("Handshake vacio");
                DataInputStream hello = new DataInputStream(
                        new ByteArrayInputStream(packet));
                int operation = hello.readUnsignedByte();
                int version = hello.readUnsignedByte();
                String code = hello.readUTF();
                String name = hello.readUTF();
                if (operation != LanProtocol.HELLO ||
                        version != LanProtocol.PROTOCOL_VERSION ||
                        !roomCode.equals(code)) {
                    sendReject(output, "Codigo o version incorrectos.");
                    return;
                }
                synchronized (LanTransport.this) {
                    pendingPeers--;
                    pendingReleased = true;
                    for (int i = 0; i < peerSockets.length; i++) {
                        if (peerSockets[i] == null) { slot = i; break; }
                    }
                    if (slot >= 0) {
                        peerSockets[slot] = socket;
                        peerCount++;
                    }
                }
                if (slot < 0) {
                    sendReject(output, "La partida esta llena.");
                    return;
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream accept = new DataOutputStream(bytes);
                accept.writeByte(LanProtocol.ACCEPT);
                accept.writeByte(slot + 1);
                accept.flush();
                LanProtocol.writeFrame(output, bytes.toByteArray());
                notifyPeerCount();
                notifyStatus("Jugador conectado: " + safeName(name));
                while (running) {
                    byte[] incoming = LanProtocol.readFrame(input);
                    if (incoming == null) break;
                    // Client actions will be decoded and validated in the
                    // world-synchronization milestone.
                }
            } catch (Throwable problem) {
                if (running) notifyStatus("Un jugador se desconecto.");
            } finally {
                synchronized (LanTransport.this) {
                    if (slot >= 0 && peerSockets[slot] == socket) {
                        peerSockets[slot] = null;
                        peerCount--;
                    } else if (slot < 0 && !pendingReleased && pendingPeers > 0) {
                        pendingPeers--;
                    }
                }
                closeSocket(socket);
                notifyPeerCount();
            }
        }
    }

    private void sendReject(DataOutputStream output, String reason) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream reject = new DataOutputStream(bytes);
            reject.writeByte(LanProtocol.REJECT);
            reject.writeUTF(reason);
            reject.flush();
            LanProtocol.writeFrame(output, bytes.toByteArray());
        } catch (IOException ignored) { }
    }

    private String extractHost(String address) {
        if (address == null) return null;
        int scheme = address.indexOf("://");
        int start = scheme >= 0 ? scheme + 3 : 0;
        int end = address.lastIndexOf(':');
        if (end <= start) return null;
        String host = address.substring(start, end);
        if (host.length() == 0 || host.indexOf('/') >= 0) return null;
        return host;
    }

    private String safeName(String name) {
        if (name == null || name.length() == 0) return "Jugador";
        if (name.length() > 12) return name.substring(0, 12);
        return name;
    }

    private String messageOf(Throwable problem) {
        String message = problem.getMessage();
        if (message == null || message.length() == 0)
            message = problem.getClass().getName();
        return message;
    }

    private void notifyStatus(String status) {
        if (listener != null) listener.onLanStatus(status);
    }

    private void notifyPeerCount() {
        if (listener != null) listener.onLanPeerCount(peerCount + 1);
    }

    private void notifyConnected(String hostName) {
        if (listener != null) listener.onLanConnected(hostName);
    }

    private void notifyError(String message) {
        if (listener != null) listener.onLanError(message);
    }

    private void closeSocket(SocketConnection socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private void closeDatagram(DatagramConnection connection) {
        if (connection == null) return;
        try { connection.close(); } catch (IOException ignored) { }
    }

    void stop() {
        running = false;
        closeDatagram(discovery);
        if (server != null) {
            try { server.close(); } catch (IOException ignored) { }
        }
        closeSocket(clientSocket);
        synchronized (this) {
            for (int i = 0; i < peerSockets.length; i++) {
                closeSocket(peerSockets[i]);
                peerSockets[i] = null;
            }
            peerCount = 0;
            pendingPeers = 0;
        }
    }
}
