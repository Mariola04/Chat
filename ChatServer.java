import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;

enum ConnectionState {
    INIT,
    OUTSIDE,
    INSIDE
}

class Client {
    String username;
    SocketChannel socketChannel;
    String messageBuffer;
    ConnectionState state;
    String currentRoomIdentifier;

    Client(String username, SocketChannel socketChannel) {
        this.username = username;
        this.socketChannel = socketChannel;
        this.messageBuffer = "";
        this.state = ConnectionState.INIT;
        this.currentRoomIdentifier = null;
    }
}

class ChatRoom {
    String identifier;
    Set<Client> currentClients;

    ChatRoom(String name) {
        this.identifier = name;
        this.currentClients = new HashSet<Client>();
    }
}

public class ChatServer {
    // A pre-allocated buffer for the received data
    static private final ByteBuffer buffer = ByteBuffer.allocate(16384);

    // Decoder for incoming text -- assume UTF-8
    static private final Charset charset = Charset.forName("UTF8");
    static private final CharsetDecoder decoder = charset.newDecoder();

    static private final Map<String, Client> clients = new HashMap<>();
    static private final Map<String, ChatRoom> chatRooms = new HashMap<>();

    static public void main(String args[]) throws Exception {

        // Parse port from command line
        int port = Integer.parseInt(args[0]);

        try {
            // Instead of creating a ServerSocket, create a ServerSocketChannel
            ServerSocketChannel ssc = ServerSocketChannel.open();

            // Set it to non-blocking, so we can use select
            ssc.configureBlocking(false);

            // Get the Socket connected to this channel, and bind it to the listening port
            ServerSocket ss = ssc.socket();
            InetSocketAddress isa = new InetSocketAddress(port);
            ss.bind(isa);

            // Create a new Selector for selecting
            Selector selector = Selector.open();

            // Register the ServerSocketChannel, so we can listen for incoming connections
            ssc.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Listening on port " + port);

            while (true) {
                // See if we've had any activity -- either an incoming connection,
                // or incoming data on an existing connection
                int num = selector.select();

                // If we don't have any activity, loop around and wait again
                if (num == 0) {
                    continue;
                }

                // Get the keys corresponding to the activity that has been
                // detected, and process them one by one
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    // Get a key representing one of bits of I/O activity
                    SelectionKey key = it.next();

                    // What kind of activity is it?
                    if (key.isAcceptable()) {

                        // It's an incoming connection. Register this socket with
                        // the Selector so we can listen for input on it
                        Socket s = ss.accept();
                        System.out.println("Got connection from " + s);

                        // Make sure to make it non-blocking, so we can use a selector on it.
                        SocketChannel sc = s.getChannel();
                        sc.configureBlocking(false);

                        // Register it with the selector, for reading and attaching the new client
                        sc.register(selector, SelectionKey.OP_READ, new Client(null, sc));

                    } else if (key.isReadable()) {

                        SocketChannel sc = null;

                        try {

                            // It's incoming data on a connection -- process it
                            sc = (SocketChannel) key.channel();
                            boolean ok = handleClientInput(sc, key);

                            // If the connection is dead, remove it from the selector and close it
                            if (!ok) {
                                disconnectClient(key);
                                key.cancel();

                                Socket s = null;
                                try {
                                    s = sc.socket();
                                    System.out.println("Closing connection to " + s);
                                    s.close();
                                } catch (IOException ie) {
                                    System.err.println("Error closing socket " + s + ": " + ie);
                                }
                            }

                        } catch (IOException ie) {

                            // On exception, remove this channel from the selector
                            disconnectClient(key);
                            key.cancel();

                            try {
                                sc.close();
                            } catch (IOException ie2) {
                                System.out.println(ie2);
                            }

                            System.out.println("Closed " + sc);
                        }
                    }
                }

                // We remove the selected keys, because we've dealt with them.
                keys.clear();
            }
        } catch (IOException ie) {
            System.err.println(ie);
        }
    }

    static private boolean handleClientInput(SocketChannel sc, SelectionKey key) throws IOException {
        // Read the message to the buffer
        buffer.clear();
        sc.read(buffer);
        buffer.flip();

        // If no data, close the connection
        if (buffer.limit() == 0) {
            return false;
        }

        String message = decoder.decode(buffer).toString();

        Client currentClient = (Client) key.attachment();

        if (message.charAt(message.length() - 1) != '\n') {
            currentClient.messageBuffer += message;
            return true;
        }

        processMessage(currentClient.messageBuffer + message, sc, key);
        currentClient.messageBuffer = "";

        return true;
    }

    static private void processMessage(String message, SocketChannel sc, SelectionKey key) throws IOException {

        // Case that the message only includes '\n'
        if (message.length() < 2) {
            return;
        }

        // Remove \n
        if (message.charAt(message.length() - 1) == '\n') {
            message = message.substring(0, message.length() - 1);
        }

        // Message is a command
        if (message.charAt(0) == '/' && message.charAt(1) != '/') {

            String messageSplit[] = message.split(" ", 2);

            switch (messageSplit[0]) {
                case "/leave":
                    leave(sc, key, false, false);
                    break;
                case "/bye":
                    bye(sc, key);    
                    break;
                case "/nick":
                    if (messageSplit.length != 2) {
                        sendMessage(sc, "ERROR" + System.lineSeparator());
                        break;
                    }
                    changeNickname(messageSplit[1], sc, key);
                    break;
                case "/join":
                    if (messageSplit.length < 2) {
                        sendMessage(sc, "ERROR" + System.lineSeparator());
                        break;
                    }
                    join(messageSplit[1], sc, key);
                    break;
                case "/priv":
                    if (messageSplit.length < 2) {
                        sendMessage(sc, "ERROR" + System.lineSeparator());
                        break;
                    }
                    sendPrivateMessage(messageSplit[1], sc, key);
                    break;
                case "/merge":
                    String messageDiv[] = message.split(" ", 3);
                    if (messageDiv.length != 3) {
                        sendMessage(sc, "ERROR" + System.lineSeparator());
                        break;
                    }
                    mergeRoom(messageDiv[1], sc, key, messageDiv[2]);
                    break;
                default:
                    sendMessage(sc, "ERROR" + System.lineSeparator());
            }
        }

        // Is a normal message; let's send it to the group
        else {
            Client sender = (Client) key.attachment();

            if (message.charAt(0) == '/' && message.charAt(1) == '/')
                message = message.substring(1); // remove the escaped '/'

            if (sender.state == ConnectionState.INSIDE) {
                String msg = "MESSAGE " + sender.username + " " + message + '\n';
                broadcastToRoom(sender.currentRoomIdentifier, sender.username, msg);
            } else
                sendMessage(sc, "ERROR" + System.lineSeparator());
        }
    }

    static private void disconnectClient(SelectionKey key) throws IOException {
        if (key.attachment() != null) {

            Client clientToRemove = (Client) key.attachment();

            if (clientToRemove.state == ConnectionState.INIT) {
                clients.remove(clientToRemove.username);
            }

            else if (clientToRemove.state == ConnectionState.INSIDE) {
                chatRooms.get(clientToRemove.currentRoomIdentifier).currentClients.remove(clientToRemove);
                clients.remove(clientToRemove.username);

                String exitMessage = "LEFT " + clientToRemove.username + System.lineSeparator();
                broadcastToRoom(clientToRemove.currentRoomIdentifier, clientToRemove.username, exitMessage);
            }
        }
    }

    static private void mergeRoom(String roomName, SocketChannel sc, SelectionKey key, String lastRoom) throws IOException {
        
        if (!chatRooms.containsKey(roomName) || !chatRooms.containsKey(lastRoom)) {
            sendMessage(sc, "ERROR: One or both rooms do not exist" + System.lineSeparator());
            return;
        }

        for (Client lastTmp : chatRooms.get(lastRoom).currentClients) {
            for (Client tmp : chatRooms.get(roomName).currentClients) {
                if (tmp.username.equals(lastTmp.username)) {
                    sendMessage(sc, "ERROR: duplicate username" + System.lineSeparator());
                    return;
                }
            }
        }

        for (Client lastTmp : chatRooms.get(lastRoom).currentClients) {
            chatRooms.get(roomName).currentClients.add(lastTmp);
            lastTmp.currentRoomIdentifier = roomName;
        
            // Notify clients in the new room
            for (Client tmp : chatRooms.get(roomName).currentClients) {
                if (!tmp.username.equals(lastTmp.username)) {
                    String noti = lastTmp.username + " from " + lastRoom + " joined " + roomName + System.lineSeparator();
                    sendMessage(tmp.socketChannel, noti);
                    sendMessage(tmp.socketChannel, "OK: MERGE SUCCESSFUL" + System.lineSeparator());

                }
            }
        }
        
        // Clean up the source room
        chatRooms.remove(lastRoom);
    
        // Acknowledge the merge to the requesting client
        sendMessage(sc, "OK: MERGE SUCCESSFUL" + System.lineSeparator());
    }

    static private void broadcastToRoom(String room, String user, String message) throws IOException {
        for (Client tmp : chatRooms.get(room).currentClients) {
            sendMessage(clients.get(tmp.username).socketChannel, message);
        }
    }

    static private void sendMessage(SocketChannel sc, String message) throws IOException {
        CharBuffer cb = CharBuffer.wrap(message.toCharArray());
        ByteBuffer bb = charset.encode(cb);
        sc.write(bb);
    }

    static private void changeNickname(String newUsername, SocketChannel sc, SelectionKey key) throws IOException {
        Client currentClient = (Client) key.attachment();
        
        // Username already used
        if (clients.containsKey(newUsername)) {
            sendMessage(sc, "ERROR" + System.lineSeparator());
            return;
        }

        String oldUsername = currentClient.username;

        clients.remove(oldUsername);
        currentClient.username = newUsername;
        clients.put(newUsername, currentClient);

        // Notify the others in the same room
        if (currentClient.state == ConnectionState.INSIDE) {
            String message = "NEWNICK " + oldUsername + " " + newUsername + System.lineSeparator();
            broadcastToRoom(currentClient.currentRoomIdentifier, currentClient.username, message);
        } else {
            currentClient.state = ConnectionState.OUTSIDE;
        }

        sendMessage(sc, "OK" + System.lineSeparator());
    }

    static private void join(String roomName, SocketChannel sc, SelectionKey key) throws IOException {
        Client clientWantJoin = (Client) key.attachment();

        // Not chosen a username 
        if (clientWantJoin.state == ConnectionState.INIT) {
            sendMessage(sc, "ERROR" + System.lineSeparator());
            return;
        }

        // Create the room if it doesn't exist
        if (!chatRooms.containsKey(roomName)) {
            chatRooms.put(roomName, new ChatRoom(roomName));
        }

        if (clientWantJoin.state == ConnectionState.OUTSIDE) {
            String message = "JOINED " + clientWantJoin.username + " " + roomName + System.lineSeparator();
            broadcastToRoom(roomName, clientWantJoin.username, message);

            chatRooms.get(roomName).currentClients.add(clientWantJoin);
            clientWantJoin.currentRoomIdentifier = roomName;
            clientWantJoin.state = ConnectionState.INSIDE;
        
        } else if (clientWantJoin.state == ConnectionState.INSIDE) {
            //leave the current room before joining the new one
            leave(sc, key, true, false);

            String message = "JOINED " + clientWantJoin.username + " " + roomName + System.lineSeparator();
            broadcastToRoom(roomName, clientWantJoin.username, message);

            chatRooms.get(roomName).currentClients.add(clientWantJoin);
            clientWantJoin.currentRoomIdentifier = roomName;
        }

        sendMessage(sc, "OK" + System.lineSeparator());
    }

    static private void leave(SocketChannel sc, SelectionKey key, boolean leavingToNewRoom, boolean bye)
            throws IOException {
        Client clientWantLeave = (Client) key.attachment();

        if (clientWantLeave.state != ConnectionState.INSIDE) {
            sendMessage(sc, "ERROR" + System.lineSeparator());
            return;
        }

        String roomIdentifier = clientWantLeave.currentRoomIdentifier;
        chatRooms.get(roomIdentifier).currentClients.remove(clientWantLeave);

        String message = "LEFT " + clientWantLeave.username + " " + roomIdentifier + System.lineSeparator();
        broadcastToRoom(roomIdentifier, clientWantLeave.username, message);

        if (!leavingToNewRoom) {
            clientWantLeave.state = ConnectionState.OUTSIDE;
            clientWantLeave.currentRoomIdentifier = null;
        }

        if (!bye) {
            sendMessage(sc, "OK" + System.lineSeparator());
        }
    }

    static private void sendPrivateMessage(String message, SocketChannel sc, SelectionKey key) throws IOException {

        // Verify sender is valid
        Client sender = (Client) key.attachment();

        if (sender.state == ConnectionState.INIT) {
            sendMessage(sc, "ERROR" + System.lineSeparator());
            return;
        }

        // First position is recipient, second position is message
        String messageSplit[] = message.split(" ", 2);

        if (messageSplit.length != 2) {
            sendMessage(sc, "ERROR" + System.lineSeparator());
            return;
        }

        String messageToSend = "PRIVATE " + sender.username + " " + messageSplit[1] + '\n';

        if (clients.containsKey(messageSplit[0])) {
            sendMessage(clients.get(messageSplit[0]).socketChannel, messageToSend);
        } else {
            sendMessage(sc, "ERROR" + System.lineSeparator());
        }
    }

    static private void bye(SocketChannel sc, SelectionKey key) throws IOException {
        Client clientLeaving = (Client) key.attachment();

        if (clientLeaving.state == ConnectionState.INSIDE) {
            leave(sc, key, false, true);
        }

        if (clients.containsKey(clientLeaving.username)) {
            clients.remove(clientLeaving.username);
        }

        sendMessage(sc, "BYE" + System.lineSeparator());
        System.out.println("Closing connection to " + sc.socket());
        sc.close();
    }

}
