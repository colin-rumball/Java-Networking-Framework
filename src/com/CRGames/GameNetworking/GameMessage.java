package com.CRGames.GameNetworking;

/**
 * Interface that every game message must extend from.
 */
public interface GameMessage
{
    public MessageTypeInterface getType();
    public int getIDofSender();
}