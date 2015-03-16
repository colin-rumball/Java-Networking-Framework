package com.CRGames.GameNetworking;

/**
 * Interface that evey game message must extend from.
 */
public interface GameMessage
{
    public MessageTypeInterface getType();
    public int getIDofSender();
}