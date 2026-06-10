package com.slackmsg.adapter.redis;

import com.slackmsg.port.service.PubSubService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class RedisPubSubService implements PubSubService {

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final Map<String, MessageListener> activeListeners = new ConcurrentHashMap<>();

    public RedisPubSubService(StringRedisTemplate redisTemplate,
                              RedisMessageListenerContainer listenerContainer) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
    }

    @Override
    public void publish(String channel, String message) {
        redisTemplate.convertAndSend(channel, message);
    }

    @Override
    public void subscribe(String channel, MessageHandler handler) {
        MessageListener listener = (msg, pattern) -> {
            String body = new String(msg.getBody());
            handler.onMessage(channel, body);
        };
        activeListeners.put(channel, listener);
        listenerContainer.addMessageListener(listener, new ChannelTopic(channel));
        log.debug("Subscribed to Redis channel: {}", channel);
    }

    @Override
    public void unsubscribe(String channel) {
        MessageListener listener = activeListeners.remove(channel);
        if (listener != null) {
            listenerContainer.removeMessageListener(listener, new ChannelTopic(channel));
            log.debug("Unsubscribed from Redis channel: {}", channel);
        }
    }
}
