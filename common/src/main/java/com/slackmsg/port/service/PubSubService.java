package com.slackmsg.port.service;

/**
 * Abstraction for pub/sub messaging between app server instances.
 * MVP: Redis Pub/Sub implementation.
 * Scale: Can swap to gRPC or Kafka without changing business logic.
 */
public interface PubSubService {

    void publish(String channel, String message);

    void subscribe(String channel, MessageHandler handler);

    void unsubscribe(String channel);

    @FunctionalInterface
    interface MessageHandler {
        void onMessage(String channel, String message);
    }
}
