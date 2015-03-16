package com.CRGames.GameNetworking;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;

/**************************************************************************************************
  This abstract class represents a Client, or Player, that can connect
  to a Server.  The client is used for sending messages to the
  Server.  It also receives messages from the Server. An application must define a
  subclass of this abstract class in order to create players.
  Each player in the game will have an associated Client object,
  which will handle details of network communication with the Server.
  At a minimum, the abstract method messageReceived(GameMessage) must
  be defined to say how the Client should respond when a message is
  received. Subclasses might also define the closedByError(),
  serverShutdown(), playerConnected(), and playerDisconnected() methods.
  A client provides methods send(GameMessage) and disconnect() for
  sending a message to the Server and for closing down the connection.
  Any non-null object that implements the GameMessage interface can be sent
  as a message.  Note that an ObjectOutputStream is used for sending
  messages.  If the same message is to be sent more than once, with
  changes between transmissions, then the resetOutput() method should be
  called between transmissions (or the autoReset property should be
  set to true).
  A client has a unique ID number that is assigned to the client
  when it connects to the Server.  The ID can retrieved by calling
  the getID() method.  The protected variable connectedPlayerIDs
  contains the ID numbers of all clients currently connected to the
 Server, including this one.
 **************************************************************************************************/

abstract public class Client {
    
    /**
      A list of the ID numbers of all clients who are currently connected
      to the Server.  This list is set each time this client is notified that
      a client has connected to or disconnected from the Server.
     */
    protected int[] connectedPlayerIDs = new int[0];
    
    /**
      If the autoReset property is set to true, then the ObjectOutputStream
      that is used for transmitting messages is reset before each message is
      sent.
     */
    private volatile boolean autoReset;
    private String serverIP;
    private int port;

//------------------------- constructor implementation ---------------------------------------

    /**
      The constructor opens a connection to a Server. If no IP is provided
      the the server will attempt to locate  the server automatically.This
      constructor will block while waiting for the connection to be established.
      Throws IOException if any I/O exception occurs while trying to connect.
     */
    public Client(int _port) throws IOException
    {
        port = _port;
        LocateServerThread serverLocator = new LocateServerThread();
        serverLocator.start();
    }

    public Client(String _IP, int _port) throws IOException
    {
        serverIP = _IP;
        port = _port;
        connection = new ConnectionToServer(serverIP, port);
    }

//------------------------- protected implementation ---------------------------------------
    
    /**
       This method is called when a message is received from the Server.
       Concrete subclasses of this class must override this method to
       say how to respond to messages.
     */
    abstract protected void messageReceived(GameMessage message);
    
    /**
      This method is called whenever this client is notified that
      a client has connected to the Server.  (Note that is IS called
      when this client connects, so this method will be called just
      after the connection has been established.) The list of all
      connected players, including the new one, is in the protected
      variable connectedPlayerIDs.  The method in this class will
      need to be overridden to add additional functionality.
     */
    protected void playerConnected(int newPlayerID) { }
    
    /**
      This method is called when this client is notified that a client
      has disconnected from the Server.  (Note that it IS NOT called
      when this client disconnects.) The list of all connected
      players is in the protected variable connectedPlayerIDs.
      The method in this class will need to be overridden to add
      additional functionality.
     */
    protected void playerDisconnected(int departingPlayerID) { }
    
    /**
      This method is called when the connection to the Server is closed down
      because of some error.  The method in this class does nothing.  Subclasses
      can override this method to take some action when the error occurs.
     */
    protected void connectionClosedByError(String message) { }
    
    /**
      This method is called when the connection to the Server is closed down
      because the Server is shutting down normally.  The method in this class does
      nothing. Subclasses can override this method to take some action when shutdown
      occurs. The message will be "*shutdown*" if the message was in fact
      sent by a Server that is shutting down in the normal way.
     */
    protected void serverShutdown(String message) { }
    
    /**
      This method is called after a connection to the server has been opened
      and after the client has been assigned an ID number.  Its purpose is to
      do extra checking or set up before the connection is fully established.
      If this method throws an IOException, then the connection is closed
      and the player is never added to the list of players.  The method in
      this class does nothing.  The client and the Server must both be programmed
      with the same handshake protocol.  At the time this method is called,
      the client's ID number has already been set and can be retrieved by
      calling the getID() method, but the client has not yet been added to
      the list of connected players.
      Throws IOException if some error occurs that should prevent the
      connection from being fully established.
     */
    protected void extraHandshake(ObjectInputStream in, ObjectOutputStream out) throws IOException { }

//------------------------- public implementation ---------------------------------------
    
    /**
      This method can be called to disconnect cleanly from the server.
      If the connection is already closed, this method has no effect.
     */
    public void disconnect() {
        if (!connection.closed)
            connection.send(new InternalGameMessage(this, InternalGameMessage.MessageType.DISCONNECT_MESSAGE));
    }
    
    /**
      This method is called to send a message to the Server. This method simply
      drops the message into a queue of outgoing messages, and it
      never blocks. This method throws an IllegalStateException if the
      connection to the Server has already been closed.
      Throws IllegalArgumentException if message is null or is not Serializable.
      Throws IllegalStateException if the connection has already been closed,
         either by the disconnect() method, because the server has shut down, or
         because of a network error.
     */
    public void send(GameMessage message) {
        if (message == null)
            throw new IllegalArgumentException("Null cannot be sent as a message.");
        if (! (message instanceof Serializable))
            throw new IllegalArgumentException("Messages must implement the Serializable interface.");
        if (connection.closed)
            throw new IllegalStateException("Message cannot be sent because the connection is closed.");
        connection.send(message);
    }

    /**
      Returns the ID number of this client, which is assigned by the server when
      the connection to the server is created. The id uniquely identifies this
      client among all clients which have connected to the server. ID numbers
      are always assigned in the order 1, 2, 3, 4... There can be gaps in the
      sequence if some client disconnects or because some client does not
      completely connect because of an exception.  (This can include an
      exception in the "extra handshake" part, if there is one, of the
      connection setup.)
     */
    public int getID() {
        return connection.id_number;
    }
    
    /**
      Resets the output stream, after any messages currently in the output queue
      have been sent. The stream only needs to be reset in one case: If the same
      object is transmitted more than once, and changes have been made to it
      between transmissions.  The reason for this is that ObjectOutputStreams are
      optimized for sending objects that don't change -- if the same object is sent
      twice it will not actually be transmitted the second time, unless the stream
      has been reset in the meantime.
     */
    public void resetOutput() {
        connection.send(new InternalGameMessage(this, InternalGameMessage.MessageType.RESET_SIGNAL)); // A ResetSignal in the output stream is seen as a signal to reset
    }
    
    /**
      If the autoReset property is set to true, then the output stream will be reset
      before every message transmission.  Use this if the same object is going to be
      continually changed and retransmitted.  See the resetOutput() method for more
      information on resetting the output stream.
     */
    public void setAutoReset(boolean auto) {
        autoReset = auto;
    }
    
    /**
      Returns the value of the autoReset property.
     */
    public boolean getAutoReset() {
        return autoReset;
    }


//------------------------- private classes ---------------------------------------
    
    private ConnectionToServer connection;  // Represents the network connection to the Server.

    private class LocateServerThread extends Thread
    {
        MulticastSocket receivingSocket, sendingSocket;
        InetAddress group;
        boolean locating = true;
        public void run()
        {
            try
            {
                receivingSocket = new MulticastSocket(3370);
                sendingSocket = new MulticastSocket(3369);

                group = InetAddress.getByName("230.0.0.1");
                receivingSocket.joinGroup(group);
                sendingSocket.joinGroup(group);
            } catch (Exception e)
            {
                System.out.println("Exception while locating server:");
                e.printStackTrace();
                return;
            }

            while (locating) {
                try {
                    byte[] buf = "*HelloServer*".getBytes();
                    DatagramPacket packet = new DatagramPacket(buf, buf.length, group, 3369);
                    System.out.println("Server Locator sending packet.");
                    sendingSocket.send(packet);

                    buf = "*HelloClient*".getBytes();
                    packet = new DatagramPacket(buf, buf.length);
                    System.out.println("Server Locator receiving packet.");
                    receivingSocket.receive(packet);
                    String receivedString = new String(packet.getData(), 0, packet.getLength());
                    System.out.println("Server Locator received: " + receivedString);
                    if (receivedString.equals("*HelloClient*"))
                    {
                        serverIP = packet.getAddress().getHostAddress();
                        connection = new ConnectionToServer(serverIP, port);
                        locating = false;
                        System.out.println("Server located at: " + serverIP);
                    }
                }
                catch (Exception e) {
                    System.out.println("Exception while locating server:");
                    e.printStackTrace();
                    locating = false;
                }
            }
            System.out.println("Server Locator stopped.");
        }
    }

    /**
       This private class handles the actual communication with the server.
     */
    private class ConnectionToServer {

        private final int id_number;               // The ID of this client, assigned by the Server.
        private final Socket socket;               // The socket that is connected to the Server.
        private final ObjectInputStream in;        // A stream for sending messages to the Server.
        private final ObjectOutputStream out;      // A stream for receiving messages from the Server.
        private final SendThread sendThread;       // The thread that sends messages to the Server.
        private final ReceiveThread receiveThread; // The thread that receives messages from the Server.

        private final LinkedBlockingQueue<GameMessage> outgoingMessages;  // Queue of messages waiting to be transmitted.

        private volatile boolean closed;     // This is set to true when the connection is closing.
                                             // For one thing, this will prevent errors from being
                                             // reported when exceptions are generated because the
                                             // connection is being closed in the normal way.
        
        /**
          Constructor opens the connection and sends the string "Hello server"
          to the server.  The server responds with an object of type Integer representing
          the ID number of the client.  The extraHandshake() method is then called
          to do any other required startup communication.  Finally, threads
          are created to handle sending and receiving messages.
         */
        ConnectionToServer(String host, int port) throws IOException {
            outgoingMessages = new LinkedBlockingQueue<GameMessage>();
            socket = new Socket(host,port);
            out = new ObjectOutputStream(socket.getOutputStream());
            out.writeObject(new InternalGameMessage(this, InternalGameMessage.MessageType.HANDSHAKE_MESSAGE));
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());
            try {
                IdAssignmentMessage response = (IdAssignmentMessage)in.readObject();
                id_number = response.getID();
            }
            catch (Exception e){
                throw new IOException("Illegal response from server.");
            }
            extraHandshake(in,out);  // Will throw an IOException if handshake doesn't succeed.
            sendThread = new SendThread();
            receiveThread = new ReceiveThread();
            sendThread.start();
            receiveThread.start();
        }
        
        /**
          This method is called to close the connection.  It can be called from outside
          this class, and it is also used internally for closing the connection.
         */
        void close() {
            closed = true;
            sendThread.interrupt();
            receiveThread.interrupt();
            try {
                socket.close();
            }
            catch (IOException e) {
            }
        }
        
        /**
          This method is called to transmit a message to the Server.
         */
        void send(GameMessage message) {
            outgoingMessages.add(message);
        }
        
        /**
          This method is called by the threads that do input and output
          on the connection when an IOException occurs.
         */
        synchronized void closedByError(String message) {
            if (! closed ) {
                connectionClosedByError(message);
                close();
            }
        }
        
        /**
          This class defines a thread that sends messages to the server.
         */
        private class SendThread extends Thread {
            public void run() {
                System.out.println("Client send thread started.");
                try {
                    while ( ! closed ) {
                        GameMessage message = outgoingMessages.take();
                        if (message.getType().equals(InternalGameMessage.MessageType.RESET_SIGNAL)) {
                            out.reset();
                        }
                        else {
                            if (autoReset)
                                out.reset();
                            out.writeObject(message);
                            out.flush();
                            if (message.getType().equals(InternalGameMessage.MessageType.DISCONNECT_MESSAGE)) {
                                close();
                            }
                        }
                    }
                }
                catch (IOException e) {
                    if ( ! closed ) {
                        closedByError("IO error occurred while trying to send message.");
                        System.out.println("Client send thread terminated by IOException: " + e);
                    }
                }
                catch (Exception e) {
                    if ( ! closed ) {
                        closedByError("Unexpected internal error in send thread: " + e);
                        System.out.println("\nUnexpected error shuts down client send thread:");
                        e.printStackTrace();
                    }
                }
                finally {
                    System.out.println("Client send thread terminated.");
                }
            }
        }
        
        /**
          This class defines a thread that reads messages from the server.
         */
        private class ReceiveThread extends Thread {
            public void run() {
                System.out.println("Client receive thread started.");
                try {
                    while ( ! closed ) {
                        GameMessage message = (GameMessage) in.readObject();
                        if (message.getType().equals(InternalGameMessage.MessageType.DISCONNECT_MESSAGE)) {
                            close();
                            serverShutdown("Ordered to shutdown by server.");
                        }
                        else if (message.getType().equals(InternalGameMessage.MessageType.STATUS_MESSAGE)) {
                            StatusMessage msg = (StatusMessage)message;
                            connectedPlayerIDs = msg.players;
                            if (msg.connecting)
                                playerConnected(msg.playerID);
                            else
                                playerDisconnected(msg.playerID);
                        }
                        else
                            messageReceived(message);
                    }
                }
                catch (IOException e) {
                    if ( ! closed ) {
                        closedByError("IO error occurred while waiting to receive message.");
                        System.out.println("Client receive thread terminated by IOException: " + e);
                    }
                }
                catch (ClassNotFoundException e)
                {
                    if ( ! closed ) {
                        closedByError("ClassNotFound error occurred while waiting to receive message.");
                        System.out.println("Client receive thread terminated by ClassNotFoundException: " + e);
                    }
                }
                catch (Exception e) {
                    if ( ! closed ) {
                        closedByError("Unexpected internal error in receive thread: " + e);
                        System.out.println("\nUnexpected error shuts down client receive thread:");
                        e.printStackTrace();
                    }
                }
                finally {
                    System.out.println("Client receive thread terminated.");
                }
            }
        }
        
    }
}
