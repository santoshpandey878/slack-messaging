package com.slackmsg.domain.enums;

/**
 * All WebSocket event types. Used by WsPayloadBuilder + WsHandler + clients.
 * To add a new real-time event: add enum value here, add builder in WsPayloadBuilder,
 * and publish via EventFanoutService.
 */
public enum WsEventType {

    // Messages
    MESSAGE_NEW("message.new"),
    MESSAGE_EDITED("message.edited"),
    MESSAGE_DELETED("message.deleted"),

    // Threads
    THREAD_REPLY("thread.reply"),

    // Reactions
    REACTION_ADDED("reaction.added"),
    REACTION_REMOVED("reaction.removed"),

    // Typing
    TYPING_START("typing.start"),
    TYPING_STOP("typing.stop"),

    // Presence
    PRESENCE_CHANGE("presence.change"),

    // Channel events
    CHANNEL_UPDATED("channel.updated"),
    CHANNEL_ARCHIVED("channel.archived"),

    // Membership
    MEMBER_JOINED("member.joined"),
    MEMBER_LEFT("member.left"),

    // Pins
    PIN_ADDED("pin.added"),
    PIN_REMOVED("pin.removed"),

    // Read receipts
    READ_RECEIPT("read.receipt"),

    // System
    CONNECTED("connected"),
    SYNC_COMPLETE("sync_complete"),
    ERROR("error");

    private final String value;

    WsEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
