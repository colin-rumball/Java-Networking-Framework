package com.CRGames.GameNetworking;

import java.io.Serializable;
import java.util.EventObject;


public class InternalGameMessage extends EventObject implements Serializable, GameMessage
{
    protected int IDofSender;
    protected enum MessageType implements MessageTypeInterface {
        HANDSHAKE_MESSAGE, ID_ASSIGNMENT_MESSAGE, STATUS_MESSAGE, RESET_SIGNAL, DISCONNECT_MESSAGE
    }
    private MessageType messageType;

    public InternalGameMessage(Object _src, MessageType _type)
    {
        super(_src);
        messageType = _type;
    }

    public MessageType getType()
    {
        return messageType;
    }
    public int getIDofSender() { return IDofSender; }
}

class IdAssignmentMessage extends InternalGameMessage
{
    public final int ID;
    public IdAssignmentMessage(Object _src, int _ID)
    {
        super(_src, MessageType.ID_ASSIGNMENT_MESSAGE);
        this.ID = _ID;
    }

    public int getID()
    {
        return ID;
    }
}

final class StatusMessage extends InternalGameMessage
{
    /**
     The ID number of the player who has connected or disconnected.
    */
    public final int playerID;

    /**
     True if the player has just connected; false if the player
     has just disconnected.
    */
    public final boolean connecting;

    /**
     The list of players after the change has been made.
    */
    public final int[] players;

    public StatusMessage(Object _src, int _playerID, boolean _connecting, int[] _players)
    {
        super(_src, MessageType.STATUS_MESSAGE);
        this.playerID = _playerID;
        this.connecting = _connecting;
        this.players = _players;
    }
}