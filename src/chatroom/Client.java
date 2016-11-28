package chatroom;

import models.ChatRoom;
import models.User;
import models.messages.*;
import sockets.SocketHandler;
import sockets.SocketHandlerListener;

import java.io.IOException;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Comparator;

/**
 * This class represents a client of the system and will
 * allow the user to create or connect to rooms and communicate
 * with the server and other clients.
 *
 * @author Clinton Cabiles
 * @author Jan Clarin
 * @author Riley Lahd
 */
public class Client implements Runnable, SocketHandlerListener {
    /**
     * Maps SocketHandlers to the User of the connection.
     */
    private final Map<SocketHandler, User> socketHandlerUserMap = new HashMap<>();

    /**
     * Listener for chat room events.
     */
    private final ClientListener listener;

    /**
     * Thread pool for SocketHandlers.
     */
    private ExecutorService socketHandlerThreadPool;

    /**
     * User associated with this client.
     */
    private User clientUser;

    /**
     * Is this client currently a room host.
     */
    private boolean isHost = false;

    /**
     * Determines if the Client should continue listening for requests.
     */
    private boolean continueRunning = true;

    /**
     * Lamport timestamp for message ordering
     */
    private int timestamp = 0;

    /**
     * Comparator for ordering ChatMessages in the message queue
     */
    private Comparator<ChatMessage> timestampComparator = new Comparator<ChatMessage>(){
        @Override
        public int compare(final ChatMessage a, final ChatMessage b)
        {
            return Integer.valueOf(a.getTimestamp()).compareTo(Integer.valueOf(b.getTimestamp()));
        }
    };

    /**
     * Priority queue for later-timestamped messages
     */
    private PriorityQueue<ChatMessage> queuedMessages = new PriorityQueue<ChatMessage>(timestampComparator);

    /**
     * Creates a new Client with the given models.
     *
     * @param clientUser Client user information.
     * @param listener   Listener to notify when certain events occur.
     */
    public Client(final User clientUser, final ClientListener listener) {
        this.clientUser = clientUser;
        this.listener = listener;
    }

    /**
     * Returns the list of known Users.
     *
     * @return List of known Users.
     */
    public List<User> getKnownUsersList() {
        return new ArrayList<>(socketHandlerUserMap.values());
    }

    /**
     * Sets the field indicating whether or not this client is the host of its chat room.
     *
     * @param isHost Indicates whether or not this client is the host of its chat room.
     */
    public void setIsHost(boolean isHost) {
        this.isHost = isHost;
    }

    /**
     * Returns whether or not this client is the host of its chat room.
     *
     * @return boolean True if the this client is hosting a room
     */
    public boolean isHost() {
        return this.isHost;
    }

    /**
     * Starts listening for incoming connections. Creates a new SocketHandler for every new connection.
     */
    @Override
    public void run() {
        socketHandlerThreadPool = Executors.newCachedThreadPool();

        try {
            // Server socket for incoming connections.
            ServerSocket serverSocket = new ServerSocket(clientUser.getPort());

            while (continueRunning) {
                Socket socket = serverSocket.accept();
                SocketHandler socketHandler = new SocketHandler(socket, this);
                socketHandlerThreadPool.execute(socketHandler);

                dequeueMessages();
            }

            socketHandlerThreadPool.shutdown();
            serverSocket.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /**
     * Sends a message to all known recipients through their SocketHandlers.
     *
     * @param message The message as a String.
     */
    public void sendMessageToAll(Message message) {
        for (SocketHandler recipientSocketHandler : socketHandlerUserMap.keySet()) {
            sendMessage(message, recipientSocketHandler);
        }
    }

    /**
     * Sends a message to the given socket.
     *
     * @param message   The message as a Message object.
     * @param recipientSocketHandler The recipient's SocketHandler.
     */
    public void sendMessage(Message message, SocketHandler recipientSocketHandler) {
        try {
            recipientSocketHandler.sendMessage(message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Sends a message to the given socket address.
     * Tries to find an existing socket handler in the socketHandlerUserMap.
     * If it does not find one, open a new socket connection.
     *
     * @param message The message to send.
     * @param recipientSocketAddress The recipient's socket address.
     */
    public void sendMessage(Message message, InetSocketAddress recipientSocketAddress) {
        try {
            SocketHandler recipientSocketHandler = null;

            // Find matching socket handler with the recipientSocketAddress.
            for (SocketHandler socketHandler : socketHandlerUserMap.keySet()) {
                // Compare IP address and ports.
                if (socketHandler.getRemoteSocketAddress().equals(recipientSocketAddress)) {
                    recipientSocketHandler = socketHandler;
                    break;
                }
            }

            // If there was no match, open a new socket handler connection.
            if (recipientSocketHandler == null) {
                recipientSocketHandler = openSocketConnection(recipientSocketAddress);
            }

            // Send the message with the socket handler pointing to the recipientSocketAddress.
            sendMessage(message, recipientSocketHandler);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Notifies listener that a message was successfully sent through a SocketHandler.
     *
     * @param recipientSocketHandler The SocketHandler that the message was sent through.
     * @param message The sent message.
     */
    @Override
    public void messageSent(SocketHandler recipientSocketHandler, Message message) {
        User recipient = socketHandlerUserMap.get(recipientSocketHandler);
        // Notify listener that a message was sent.
        listener.messageSent(recipient, message);
    }

    /**
     * Responds to different types of received messages and notifies listeners about the message.
     *
     * @param senderSocketHandler The SocketHandler that the message was received from.
     * @param message The received message.
     */
    @Override
    public void messageReceived(SocketHandler senderSocketHandler, Message message) {
        boolean notify = true;
        if (message instanceof HelloMessage) {
            handleHelloMessage(senderSocketHandler, (HelloMessage) message);
        }
        else if (message instanceof UserInfoMessage) {
            handleUserInfoMessage((UserInfoMessage) message);
        }
        else if (message instanceof RoomListMessage) {
            handleRoomListMessage((RoomListMessage) message);
        }
        else if (message instanceof YourInfoMessage) {
            handleYourInfoMessage((YourInfoMessage) message);
        }
        else if (message instanceof ChatMessage) {
            notify = handleChatMessage((ChatMessage) message);
        }

        if(notify){
            // Notify listener that a message was received.
            User sender = socketHandlerUserMap.get(senderSocketHandler);
            listener.messageReceived(sender, message);
        }
    }

    /**
     * Handles HelloMessages.
     * Maps the sender of the message to the SocketHandler.
     * If this client is the host, notify all other clients about the new client. Also, replies to the new client.
     *
     * @param senderSocketHandler The SocketHandler of the sender.
     * @param message The message to handle.
     */
    private void handleHelloMessage(SocketHandler senderSocketHandler, HelloMessage message) {
        User sender = message.getSender();
        if (isHost) {
            // Send new client information to all clients.
            sendMessageToAll(new UserInfoMessage(clientUser, sender));
            // Send message to the new client.
            sendMessage(new HelloMessage(clientUser), senderSocketHandler);
        }
        socketHandlerUserMap.put(senderSocketHandler, sender);
    }

    /**
     * Handles UserInfoMessages.
     * Ignores the message if it is from this client.
     * Otherwise, opens a new connection to the user specified in the message.
     *
     * @param message The message to handle.
     */
    private void handleUserInfoMessage(UserInfoMessage message) {
        User messageUser = message.getUser();
        if (!clientUser.equals(messageUser)) {
            try {
                SocketHandler newSocketHandler = openSocketConnection(messageUser.getSocketAddress());
                socketHandlerUserMap.put(newSocketHandler, messageUser);
                sendMessage(new HelloMessage(clientUser), newSocketHandler);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Handles RoomListMessages.
     * Picks the first room it can join and joins it.
     *
     * @param message
     */
    private void handleRoomListMessage(RoomListMessage message) {
        List<ChatRoom> chatRooms = message.getChatRooms();
        if (chatRooms.size() < 1) {
            System.out.println("No chat rooms to join.");
            return;
        }
        ChatRoom chatRoomToJoin = chatRooms.get(0);
        System.out.println("Joining room: " + chatRoomToJoin.getName());
        sendMessage(new HelloMessage(clientUser), chatRoomToJoin.getHostSocketAddress());
    }

    /**
     * Handles YourInfoMessages.
     * Sets the clientUser of this Client to the received one. This should be the external-facing socket address.
     *
     * @param message
     */
    private void handleYourInfoMessage(YourInfoMessage message) {
        this.clientUser = message.getUser();
    }

    /**
     * Handles ChatMessages.
     *
     * Compares timestamp on incoming message with current time.
     * If message is equal or lower, send as normal. Else put into queue.
     * @param message
     * @return boolean True if the message is allowed to continue, false otherwise
     */
    private boolean handleChatMessage(ChatMessage message) {
        if(message.getTimestamp() > peekTimestamp()){
            //Put it into a queue to be taken off when timestamp is higher
            queuedMessages.offer(message);
            return false;
        }
        timestamp(); //increment the timestamp
        return true;
    }

    /**
     * Opens a connection with given user and sends a HLO
     * @param userSocketAddress The socket address of the user to open a connection with
     * @return SocketHandler The socketHandler connected to that user
     */
    private SocketHandler openSocketConnection(InetSocketAddress userSocketAddress) throws IOException
    {
        Socket socket = new Socket(userSocketAddress.getAddress(), userSocketAddress.getPort());
        SocketHandler socketHandler = new SocketHandler(socket, this);
        socketHandlerThreadPool.execute(socketHandler);
        return socketHandler;
    }

    /**
     * Peeks at the current Lamport timestamp without incrementing it.
     *
     */
    public int peekTimestamp(){
        return timestamp;
    }

    /**
     * Stamps a message with a timestamp, incrementing the timestamp.
     * NOTE: This message must be sent ASAP after it is stamped.
     *
     */
    public int timestamp(){
        int time = timestamp;
        timestamp++;
        return time;
    }

    public void dequeueMessages(){
        //Dequeue all messages in the message queue that have a timestamp lower than/equal to current
        while(queuedMessages.peek() != null && queuedMessages.peek().getTimestamp() <= peekTimestamp())
        {
            ChatMessage poppedMessage = queuedMessages.poll();
            timestamp(); //increment timestamp
            User sender = null; //TODO: get sender using message.getSenderSocketAddress()
            listener.messageReceived(sender, poppedMessage);
        }
    }
}