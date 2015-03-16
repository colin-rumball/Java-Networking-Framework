package com.CRGames.GameNetworking;

import java.io.*;
import java.net.*;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**************************************************************************************************
 When a Server is created, it will listen for connection requests from clients on a specified port.
 The clients are the players in the game.  Each player is identified
 by an ID number that is assigned by the server when the client connects.
 Clients are defined by subclasses of the class com.CRGames.GameNetworking.Client.
 A Server is a "message center" that can send and receive messages.
 A message can be any non-null object that implements the GameMessage interface.
 When a message  is received, the protected method messageReceived(message) is called.
 In this class, this method will simply resend the message to all connected clients.  That is,
 the Server acts as a passive forwarder of messages.  Subclasses will usually override this method
 and will generally add other functionality to the Server as well.
 The sendToAll(msg) method sends a message to all connected clients.
 The sendToOne(playerID,msg) method will send the message to just the client
 with the specified ID number.  If the same object is transmitted
 more than once, it might be necessary to use the resetOutput() or
 setAutoReset(true) methods.  See those methods for details.
 (Certain messages that are defined by this package are for internal use only. These
 messages do not result in a call to messageReceived, and they are not seen by clients.)

 The communication protocol that is used internally goes as follows:
 - When the server receives a connection request, it expects to
   read a hand shake message from the client.
 - The server responds by sending an object of type Integer
   representing the unique ID number that has been assigned to the client.
   Clients are assigned the IDs 1, 2, 3, ..., in the order they connect.
 - The extraHandshake() method is always called.  This method does nothing
   in this class, but subclasses of Server can override to do extra setup
   or checking before the connection is considered to be created.
   Note that if extraHandshake() throws an error, then the client is
   never considered connected, but that client's ID will not be reused.
 - All connected clients, including the one that has just connected,
   are notified of the new client.  (The playerConnected() method in
   the client will be called.)
 - Once a client has successfully connected, the client can send messages to
   the server.  Messages received from a client are passed to the
   messageReceived() method.
 - If the client's disconnect() method is called, the Server is notified,
   and it in turn notifies all connected clients, not including the one
   that just disconnected.
 - If the Server's shutDown() method is called, all the clients
   will be notified, and the ServerSocket, if it still exists, is closed down.
   One second later, any connection that has not closed normally is closed.
 **************************************************************************************************/
public class Server
{
    /**
     The maximum amount of clients that the server will hold.
     Checked after every client connects. When the max is reached
     the server stops listening for new connections until a client disconnects.
     */
    private volatile int maximumPlayerCapacity;

    /**  A map that associates player IDs with the connections to each player.*/
    private TreeMap<Integer, ConnectedClient> playerConnections;
    
    /**
      A queue of messages received from clients.  When a method is received,
      it is placed in this queue.  A separate thread takes messages from the
      queue and processes them (in the order in which they were received).
     */
    private LinkedBlockingQueue<GameMessage> incomingMessages;
    
    /**
      If the autoreset property is set to true, then the ObjectOutputStreams that are
      used for transmitting messages to clients is reset before each object is sent.
     */
    private volatile boolean autoreset;
    
    private ServerSocket serverSocket;  // Listens for connections.
    private Thread listeningThread;        // Accepts connections on serverSocket
    private BroadcasterThread broadcasterThread; // Broadcasts the servers location over the LAN.
    volatile private boolean shutdown;  // Set to true when the Server is not listening.

    private int socketPort; // The port that the server will be running on.
    
    private int nextClientID = 1;  // The id number that will be assigned to
                                   // the next client that connects.

    //------------------------- constructor implementation ---------------------------------------

    /**
     Creates a Server listening on a specified port, starts the LAN broadcaster if specified.
     Throws IOException if it is not possible to create a listening socket on the specified port.
    */
    public Server(int _port, boolean _broadcast, int _maxCapacity) throws IOException
    {
        playerConnections = new TreeMap<Integer, ConnectedClient>();
        incomingMessages = new LinkedBlockingQueue<GameMessage>();
        socketPort = _port;
        serverSocket = new ServerSocket(socketPort);

        if (_broadcast)
            startBroadcaster();
        setMaximumPlayerCapacity(_maxCapacity);
    }

    //------------------------- protected implementation ---------------------------------------

    /**
      This method is called after a connection request has been received to do
      extra checking or set up before the connection is fully established.
      It is called after the playerID has been transmitted to the client.
      If this method throws an IOException, then the connection is closed
      and the player is never added to the list of players.  The method in
      this class does nothing.   The client and the Server must both be programmed
      with the same handshake protocol.
      Throws IOException should be thrown if some error occurs that should
      prevent the connection from being established.
     */
    protected void extraHandshake(int playerID, ObjectInputStream in,
                                  ObjectOutputStream out) throws IOException {
    }
    
    
    /**
      This method is called when a message is received from one of the
      connected players.  The method in this class simply resends the message,
      along with the ID of the sender of the message, to all connected
      players, including the one who sent the original message.  This
      behavior should be overridden in subclasses.
     */
    protected void handleReceivedMessage(GameMessage message) {
        sendToAllClients(message);
    }
    
    
    /**
      This method is called just after a player has connected.
      Note that getPlayerList() can be called to get a list
      of connected player's IDs.  The method in this class does nothing
      and should be overwritten.
     */
    protected void playerConnected(int playerID) {
    }
    
    /**
      This method is called just after a player has disconnected.
      Note that getPlayerList() can be called to get a list
      of connected player's IDs. The method in this class does nothing
      and should be overwritten.
     */
    protected void playerDisconnected(int playerID) {
    }

    //------------------------- public implementation ---------------------------------------

    /**
      Starts the server by starting the thread to listen for new connections and starts the
     message receiving thread for processing messages received from the clients.
     */
    synchronized public void start()
    {
        System.out.println("Listening for client connections on port " + socketPort);
        listeningThread = new ListeningThread();
        listeningThread.start();

        Thread messageReceivingThread = new Thread(){
            public void run() {
                while (true) {
                    try {
                        GameMessage message = incomingMessages.take();
                        messageReceived(message);
                    }
                    catch (Exception e) {
                        System.out.println("Exception while handling received message:");
                        e.printStackTrace();
                    }
                }
            }
        };
        messageReceivingThread.setDaemon(true);
        messageReceivingThread.start();
    }

    /**
      Restarts the listener for new clients to connect to unless the server was already
      listening for new connections.
     */
    public void restartNewClientListener() throws IOException
    {
        serverSocket = new ServerSocket(socketPort);
        if (listeningThread != null && listeningThread.isAlive())
            throw new IllegalStateException("Server is already listening for connections.");
        listeningThread = new ListeningThread();
        listeningThread.start();
    }

    /**
      Stops listening, without disconnecting any currently connected clients.
      Is called automatically when the maximum number of clients have connected.
     */
    public void shutdownNewClientListener() {
        if (listeningThread == null)
            return;
        shutdown = true;
        try {
            serverSocket.close();
        }
        catch (IOException e) {
        }
        listeningThread = null;
        serverSocket = null;
    }
    
    
    /**
      Restarts the entire server. Listening and accepting new clients.
      Throws IOException if it is impossible to create a listening socket on the specified port.
     */
    public void restartServer(int port) throws IOException {
        shutdown = false;
        restartNewClientListener();
    }
    
    /**
      Disconnects all currently connected clients and stops accepting new client
      requests.  It is still possible to restart listening after this method has
      been called, by calling the restartServer() method.
     */
    public void shutDown() {
        shutdownNewClientListener();
        sendToAllClients(new InternalGameMessage(this, InternalGameMessage.MessageType.DISCONNECT_MESSAGE));
        try {
            Thread.sleep(1000);
        }
        catch (InterruptedException e) {
        }
        for (ConnectedClient pc : playerConnections.values())
            pc.close();
    }
    
    /**
      Resets all output streams, after any messages currently in the output queue
      have been sent.  The stream only needs to be reset in one case:  If the same
      object is transmitted more than once, and changes have been made to it
      between transmissions.  The reason for this is that ObjectOutputStreams are
      optimized for sending objects that don't change -- if the same object is sent
      twice it will not actually be transmitted the second time, unless the stream
      has been reset in the meantime.
     */
    public void resetOutput() {
        for (ConnectedClient pc : playerConnections.values())
            pc.send(new InternalGameMessage(this, InternalGameMessage.MessageType.RESET_SIGNAL)); // A ResetSignal in the output stream is seen as a signal to reset
    }

    /**
      Sends a specified non-null Object that implements GameMessage to one connected client.
      The receiving client is specified by the ID number of the player to whom the message is
      meant to be sent to.  If there is no such player, then the method returns the
      value false.
      Returns true if the specified recipient exists, false if not.
     */
    synchronized public boolean sendToClient(int recipientID, GameMessage message) {
        if (message == null)
            throw new IllegalArgumentException("Null cannot be sent as a message.");
        if ( ! (message instanceof Serializable) )
            throw new IllegalArgumentException("Messages must implement the Serializable interface.");
        ConnectedClient pc = playerConnections.get(recipientID);
        if (pc == null)
            return false;
        else {
            pc.send(message);
            return true;
        }
    }

    /**
      Sends a specified non-null Object that implements the GameMessage interface to all connected clients.
     */
    synchronized public void sendToAllClients(GameMessage message) {
        if (message == null)
            throw new IllegalArgumentException("Null cannot be sent as a message.");
        if ( ! (message instanceof Serializable) )
            throw new IllegalArgumentException("Messages must implement the Serializable interface.");
        for (ConnectedClient pc : playerConnections.values())
            pc.send(message);
    }

    //------------------------- getter/setter implementation ---------------------------------------

    /**
      Sets the maximum number of clients that can be connected at one time.
     */
    public void setMaximumPlayerCapacity(int _max)
    {
        maximumPlayerCapacity = _max;
    }

    /**
     Gets the maximum number of clients that can be connected at one time.
     */
    public final int getMaximumPlayerCapacity()
    {
        return maximumPlayerCapacity;
    }

    /**
      Gets a list of ID numbers of currently connected clients.
      The array is newly created each time this method is called.
     */
    synchronized public int[] getPlayerList() {
        int[] players = new int[playerConnections.size()];
        int i = 0;
        for (int p : playerConnections.keySet())
            players[i++] = p;
        return players;
    }

    /**
     Gets the number of currently connected clients.
     */
    synchronized public int getNumberOfConnectedPlayers()
    {
        return playerConnections.size();
    }

    /**
      If the autoreset property is set to true, then all output streams will be reset
      before every object transmission.  Use this if the same object is going to be
      continually changed and retransmitted.  See the resetOutput() method for more
      information on resetting the output stream.  The default value is false.
     */
    public void setAutoreset(boolean auto) {
        autoreset = auto;
    }
    
    /**
      Returns the value of the autoreset property.
     */
    public boolean getAutoreset() {
        return autoreset;
    }

    //------------------------- private implementation ---------------------------------------

    /**
      Starts the LAN broadcaster
     */
    private void startBroadcaster()
    {
        broadcasterThread = new BroadcasterThread();
        broadcasterThread.setDaemon(true);
        broadcasterThread.start();
    }

    /**
      Is an internal call made every time a message is received by the server.
     */
    synchronized private void messageReceived(GameMessage message)
    {
        handleReceivedMessage(message);
    }

    /**
      An internal call made when a client is authenticated. Adds the client to the client
      mapping and shuts down the listener if enough clients have joined.
     */
    synchronized private void acceptConnection(ConnectedClient newConnection) {
        int ID = newConnection.getPlayerID();
        playerConnections.put(ID,newConnection);
        StatusMessage statusMessage = new StatusMessage(this, ID, true, getPlayerList());
        sendToAllClients(statusMessage);
        playerConnected(ID);
        System.out.println("Connection accepted from client number " + ID);

        if (getNumberOfConnectedPlayers() == getMaximumPlayerCapacity())
        {
            shutdownNewClientListener();
        }
    }

    /**
      An internal call made when a client is authenticated. Adds the client to the client
      mapping and shuts down the listener if enough clients have joined.
     */
    synchronized private void clientDisconnected(int playerID) {
        if (playerConnections.containsKey(playerID)) {
            playerConnections.remove(playerID);
            StatusMessage statusMessage = new StatusMessage(this, playerID, false, getPlayerList());
            sendToAllClients(statusMessage);
            playerDisconnected(playerID);
            System.out.println("Connection with client number " + playerID + " closed by DisconnectMessage from client.");

            if (listeningThread == null)
            {
                try {
                    restartNewClientListener();
                } catch (IOException e){}
            }
        }
    }

    /**
      An internal call made when a client is unreachable or otherwise becomes unresponsive.
      Removes the client from the client mapping and updates all other clients.
     */
    synchronized private void connectionToClientClosedWithError( ConnectedClient playerConnection, String message ) {
        int ID = playerConnection.getPlayerID();
        if (playerConnections.remove(ID) != null) {
            playerDisconnected(ID);
            StatusMessage statusMessage = new StatusMessage(this, ID, false, getPlayerList());
            sendToAllClients(statusMessage);
        }
    }

    //------------------------- private classes ---------------------------------------

    /**
      Thread to listen for new connection requests from clients.
     */
    private class ListeningThread extends Thread {  // Listens for connection requests from clients.
        public void run() {
            try {
                while ( ! shutdown ) {
                    Socket newConnection = serverSocket.accept();
                    if (shutdown) {
                        System.out.println("Listener socket has shut down.");
                        break;
                    }
                    new ConnectedClient(incomingMessages,newConnection);
                }
            }
            catch (Exception e) {
                if (shutdown)
                    System.out.println("Listener socket has shut down.");
                else
                    System.out.println("Listener socket has been shut down by error: " + e);
            }
        }
    }

    /**
      Thread to broadcast where the server is located over the LAN.
     */
    private class BroadcasterThread extends Thread
    {
        MulticastSocket receivingSocket, sendingSocket;
        InetAddress group;

        public void run()
        {
            try {
                receivingSocket = new MulticastSocket(3369);
                sendingSocket = new MulticastSocket(3370);

                group = InetAddress.getByName("230.0.0.1");
                receivingSocket.joinGroup(group);
                sendingSocket.joinGroup(group);
            } catch (Exception e)
            {
                System.out.println("Exception while creating broadcaster:");
                e.printStackTrace();
                return;
            }

            while (true)
            {
                try {
                    byte[] buf = "*HelloServer*".getBytes();
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    System.out.println("Server Broadcaster receiving packet.");
                    receivingSocket.receive(packet);
                    String receivedString = new String(packet.getData(), 0, packet.getLength());
                    System.out.println("Server Broadcaster received: " + receivedString);
                    if (receivedString.equals("*HelloServer*"))
                    {
                        buf = "*HelloClient*".getBytes();
                        packet = new DatagramPacket(buf, buf.length, group, 3370);
                        System.out.println("Server broadcaster sending packet.");
                        sendingSocket.send(packet);
                    }
                }
                catch (Exception e) {
                    System.out.println("Exception while broadcasting:");
                    e.printStackTrace();
                }
            }
        }
    }

    /**
      Represents and handles the connection with one client.
     */
    public class ConnectedClient
    {
        private int playerID;  // The ID number for this player.
        private BlockingQueue<GameMessage> incomingMessages;
        private LinkedBlockingQueue<GameMessage> outgoingMessages;
        private Socket connection;
        private ObjectInputStream in;
        private ObjectOutputStream out;
        private volatile boolean closed;  // Set to true when connection is closing normally.
        private Thread sendThread; // Handles setup, then handles outgoing messages.
        private volatile Thread receiveThread; // Created only after connection is open.
        
        ConnectedClient(BlockingQueue<GameMessage> receivedMessageQueue, Socket connection)  {
            this.connection = connection;
            incomingMessages = receivedMessageQueue;
            outgoingMessages = new LinkedBlockingQueue<GameMessage>();
            sendThread =  new SendThread();
            sendThread.start();
        }
        
        int getPlayerID() {
            return playerID;
        }
        
        void close() {
            closed = true;
            sendThread.interrupt();
            if (receiveThread != null)
                receiveThread.interrupt();
            try {
                connection.close();
            }
            catch (IOException e) {
            }
            clientDisconnected(playerID);
        }
        
        void send(GameMessage message) { // Just drop message into message output queue.
            if (message.getType().equals(InternalGameMessage.MessageType.DISCONNECT_MESSAGE)) {
                // A signal to close the connection;
                // discard other waiting messages, if any.
                outgoingMessages.clear();
            }
            outgoingMessages.add(message);
        }
        
        private void closedWithError(String message) {
            System.out.println(message);
            connectionToClientClosedWithError(this, message);
            close();
        }
        
        /**
          Handles the "handshake" that occurs before the connection is opened.
          Once that's done, it creates a thread for receiving incoming messages,
          and goes into an infinite loop in which it transmits outgoing messages.
         */
        private class SendThread extends Thread {
            public void run() {
                try {
                    out = new ObjectOutputStream(connection.getOutputStream());
                    in = new ObjectInputStream(connection.getInputStream());
                    InternalGameMessage handle = (InternalGameMessage)in.readObject(); // first input must be "Hello Server"
                    if (!handle.getType().equals(InternalGameMessage.MessageType.HANDSHAKE_MESSAGE))
                        throw new Exception("Incorrect handle received from client.");
                    synchronized(Server.this) {
                        playerID = nextClientID++; // Get a player ID for this player.
                    }
                    out.writeObject(new IdAssignmentMessage(this, playerID));  // send playerID to the client.
                    out.flush();
                    extraHandshake(playerID,in,out);  // Does any extra stuff before connection is fully established.
                    acceptConnection(ConnectedClient.this);
                    receiveThread = new ReceiveThread();
                    receiveThread.start();
                }
                catch (Exception e) {
                    try {
                        closed = true;
                        connection.close();
                    }
                    catch (Exception e1) {
                    }
                    System.out.println("\nError while setting up connection: " + e);
                    e.printStackTrace();
                    return;
                }
                try {
                    while ( ! closed ) {  // Get messages from outgoingMessages queue and send them.
                        try {
                            GameMessage message = outgoingMessages.take();
                            if (message.getType().equals(InternalGameMessage.MessageType.RESET_SIGNAL))
                                out.reset();
                            else {
                                if (autoreset)
                                    out.reset();
                                out.writeObject(message);
                                out.flush();
                                if (message.getType().equals(InternalGameMessage.MessageType.DISCONNECT_MESSAGE)) // A signal to close the connection.
                                    close();
                            }
                        }
                        catch (InterruptedException e) {
                            // should mean that connection is closing
                        }
                    }    
                }
                catch (IOException e) {
                    if (! closed) {
                        closedWithError("Error while sending data to client.");
                        System.out.println("Server send thread terminated by IOException: " + e);
                    }
                }
                catch (Exception e) {
                    if (! closed) {
                        closedWithError("Internal Error: Unexpected exception in output thread: " + e);
                        System.out.println("\nUnexpected error shuts down Server's send thread:");
                        e.printStackTrace();
                    }
                }
            }
        }
        
        /**
          The ReceiveThread reads messages transmitted from the client.  Messages
          are dropped into an incomingMessages queue, which is shared by all clients.
          If a DisconnectMessage is received, however, it is a signal from the
          client that the client is disconnecting.
         */
        private class ReceiveThread extends Thread {
            public void run() {
                try {
                    while ( ! closed ) {
                        try {
                            GameMessage message = (GameMessage) in.readObject();
                            if ( ! (message.getType().equals(InternalGameMessage.MessageType.DISCONNECT_MESSAGE)) )
                                incomingMessages.put(message);
                            else {
                                closed = true;
                                outgoingMessages.clear();
                                //out.writeObject("*goodbye*");
                                //out.flush();
                                clientDisconnected(playerID);
                                close();
                            }
                        }
                        catch (InterruptedException e) {
                            // should mean that connection is closing
                        }
                    }
                }
                catch (IOException e) {
                    if (! closed) {
                        closedWithError("Error while reading data from client.");
                        System.out.println("Server receive thread terminated by IOException: " + e);
                    }
                }
                catch (Exception e) {
                    if ( ! closed ) {
                        closedWithError("Internal Error: Unexpected exception in input thread: " + e);
                        System.out.println("\nUnexpected error shuts down Server's receive thread:");
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}
