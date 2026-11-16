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
        void onLanPacket(int playerId, byte[] packet);
    }

    private static final int HOST = 1;
    private static final int JOIN = 2;
    private final Listener listener;
    private final int mode;
    private final String roomCode;
    private final String playerName;
    private volatile boolean running = true;
    private volatile boolean discoveryFound;
    private volatile boolean discoveryTimedOut;
    private ServerSocketConnection server;
    private DatagramConnection discovery;
    private SocketConnection clientSocket;
    private DataOutputStream clientOutput;
    private final DataOutputStream[] peerOutputs = new DataOutputStream[LanProtocol.MAX_PLAYERS - 1];
    private final SocketConnection[] peerSockets =
            new SocketConnection[LanProtocol.MAX_PLAYERS - 1];
    private volatile int peerCount;
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
                String message = discoveryTimedOut ?
                        "No se encontro esa sala. Comprueba el codigo y la red Wi-Fi." :
                        messageOf(problem);
                stop();
                notifyError(message);
            }
        }
    }

    private void runHost() throws IOException {
        ServerSocketConnection openedServer =
                (ServerSocketConnection)Connector.open(
                        "socket://:" + LanProtocol.GAME_PORT);
        synchronized (this) {
            if (!running) {
                try { openedServer.close(); } catch (IOException ignored) { }
                return;
            }
            server = openedServer;
        }

        if (!running) return;
        final DatagramConnection openedDiscovery =
                (DatagramConnection)Connector.open(
                        "datagram://:" + LanProtocol.DISCOVERY_PORT);
        synchronized (this) {
            if (!running) {
                closeDatagram(openedDiscovery);
                return;
            }
            discovery = openedDiscovery;
        }

        Thread beacon = new Thread(new Runnable() {
            public void run() { runDiscoveryResponder(openedDiscovery); }
        });
        beacon.start();
        notifyStatus("Sala abierta. Esperando jugadores...");
        while (running) {
            SocketConnection accepted =
                    (SocketConnection)server.acceptAndOpen();
            boolean allowed;
            synchronized (this) {
                allowed = running && peerCount + pendingPeers <
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

    private void runDiscoveryResponder(DatagramConnection channel) {
        while (running) {
            Datagram request;
            try {
                request = channel.newDatagram(96);
                channel.receive(request);
            } catch (Throwable problem) {
                if (running) {
                    String message = "LAN UDP: " + messageOf(problem);
                    stop();
                    notifyError(message);
                }
                return;
            }

            boolean matchingRequest = false;
            try {
                DataInputStream input = new DataInputStream(
                        new ByteArrayInputStream(request.getData(),
                                request.getOffset(), request.getLength()));
                int magic = input.readInt();
                int version = input.readUnsignedByte();
                int operation = input.readUnsignedByte();
                String requestedCode = input.readUTF();
                matchingRequest = magic == LanProtocol.DISCOVERY_MAGIC &&
                        version == LanProtocol.PROTOCOL_VERSION &&
                        operation == LanProtocol.DISCOVERY_QUERY &&
                        roomCode.equals(requestedCode);
            } catch (Throwable malformedPacket) {
                // Ignore unrelated or truncated UDP packets; keep listening.
                continue;
            }
            if (!matchingRequest) continue;

            try {
                Datagram reply = channel.newDatagram(96);
                reply.setAddress(request);
                reply.writeInt(LanProtocol.DISCOVERY_MAGIC);
                reply.writeByte(LanProtocol.PROTOCOL_VERSION);
                reply.writeByte(LanProtocol.DISCOVERY_RESPONSE);
                reply.writeUTF(roomCode);
                reply.writeShort(LanProtocol.GAME_PORT);
                channel.send(reply);
            } catch (Throwable problem) {
                if (running) {
                    String message = "LAN UDP: " + messageOf(problem);
                    stop();
                    notifyError(message);
                }
                return;
            }
        }
    }

    private void runJoin() throws IOException {
        notifyStatus("Buscando la partida en esta red Wi-Fi...");
        DatagramConnection openedDiscovery =
                (DatagramConnection)Connector.open("datagram://");
        synchronized (this) {
            if (!running) {
                closeDatagram(openedDiscovery);
                return;
            }
            discovery = openedDiscovery;
        }
        final DatagramConnection waitingConnection = openedDiscovery;
        Thread timeout = new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(7000L); } catch (InterruptedException ignored) { }
                if (running && !discoveryFound) {
                    discoveryTimedOut = true;
                    closeDatagram(waitingConnection);
                }
            }
        });
        timeout.start();

        int attempt;
        for (attempt = 0; attempt < 3 && running; attempt++) {
            Datagram request = discovery.newDatagram(96,
                    "datagram://255.255.255.255:" + LanProtocol.DISCOVERY_PORT);
            request.writeInt(LanProtocol.DISCOVERY_MAGIC);
            request.writeByte(LanProtocol.PROTOCOL_VERSION);
            request.writeByte(LanProtocol.DISCOVERY_QUERY);
            request.writeUTF(roomCode);
            discovery.send(request);
            if (attempt < 2) {
                try { Thread.sleep(180L); }
                catch (InterruptedException ignored) { }
            }
        }

        String hostAddress = null;
        while (running && !discoveryFound) {
            Datagram reply = discovery.newDatagram(96);
            discovery.receive(reply);
            try {
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
            } catch (Throwable malformedPacket) {
                // Ignore unrelated datagrams while searching for the room.
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
        synchronized (this) {
            if (!running) return;
            clientOutput = output;
        }
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
            if (listener != null) listener.onLanPacket(0, packet);
        }
        if (running) {
            stop();
            notifyError("La conexion con el anfitrion termino.");
        }
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
                    if (pendingPeers > 0) pendingPeers--;
                    pendingReleased = true;
                    if (running) {
                        for (int i = 0; i < peerSockets.length; i++) {
                            if (peerSockets[i] == null) { slot = i; break; }
                        }
                        if (slot >= 0) {
                            peerSockets[slot] = socket;
                            peerOutputs[slot] = output;
                            peerCount++;
                        }
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
                    if (listener != null) listener.onLanPacket(slot + 1, incoming);
                }
            } catch (Throwable problem) {
                if (running) notifyStatus("Un jugador se desconecto.");
            } finally {
                synchronized (LanTransport.this) {
                    if (slot >= 0 && peerSockets[slot] == socket) {
                        peerSockets[slot] = null;
                        peerOutputs[slot] = null;
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

    /**
     * Sends a framed gameplay packet to the host (client mode).
     * Packet payload must begin with a LanProtocol opcode.
     */
    void sendToHost(byte[] packet) throws IOException {
        DataOutputStream output;
        synchronized (this) {
            if (!running || mode != JOIN || clientOutput == null)
                throw new IOException("LAN client is not connected");
            output = clientOutput;
        }
        synchronized (output) {
            LanProtocol.writeFrame(output, packet);
        }
    }

    /** Sends a framed packet to one guest; player IDs 1..3 belong to guests. */
    void sendToPlayer(int playerId, byte[] packet) throws IOException {
        if (mode != HOST || playerId < 1 || playerId >= LanProtocol.MAX_PLAYERS)
            throw new IOException("Invalid LAN player");
        DataOutputStream output;
        synchronized (this) {
            if (!running || peerOutputs[playerId - 1] == null)
                throw new IOException("LAN player is not connected");
            output = peerOutputs[playerId - 1];
        }
        synchronized (output) {
            LanProtocol.writeFrame(output, packet);
        }
    }

    /** Broadcasts a framed packet to every connected guest. */
    void broadcast(byte[] packet) {
        if (mode != HOST) return;
        int i;
        for (i = 0; i < peerOutputs.length; i++) {
            DataOutputStream output;
            synchronized (this) {
                output = running ? peerOutputs[i] : null;
            }
            if (output != null) {
                try {
                    synchronized (output) {
                        LanProtocol.writeFrame(output, packet);
                    }
                }
                catch (IOException problem) {
                    // The peer reader will perform normal disconnect cleanup.
                }
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
        ServerSocketConnection oldServer;
        DatagramConnection oldDiscovery;
        SocketConnection oldClient;
        SocketConnection peer0, peer1, peer2;
        synchronized (this) {
            running = false;
            oldServer = server;
            oldDiscovery = discovery;
            oldClient = clientSocket;
            server = null;
            discovery = null;
            clientSocket = null;
            clientOutput = null;
            peer0 = peerSockets.length > 0 ? peerSockets[0] : null;
            peer1 = peerSockets.length > 1 ? peerSockets[1] : null;
            peer2 = peerSockets.length > 2 ? peerSockets[2] : null;
            for (int i = 0; i < peerSockets.length; i++) {
                peerSockets[i] = null;
                peerOutputs[i] = null;
            }
            peerCount = 0;
            pendingPeers = 0;
        }
        closeDatagram(oldDiscovery);
        if (oldServer != null) {
            try { oldServer.close(); } catch (IOException ignored) { }
        }
        closeSocket(oldClient);
        closeSocket(peer0);
        closeSocket(peer1);
        closeSocket(peer2);
    }
}
