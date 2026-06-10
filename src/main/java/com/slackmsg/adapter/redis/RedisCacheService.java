package com.slackmsg.adapter.redis;

import com.slackmsg.port.service.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RedisCacheService implements CacheService {

    private final StringRedisTemplate redis;

    @Override
    public void set(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    @Override
    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    @Override
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    @Override
    public Long increment(String key) {
        return redis.opsForValue().increment(key);
    }

    @Override
    public void hset(String key, String field, String value) {
        redis.opsForHash().put(key, field, value);
    }

    @Override
    public String hget(String key, String field) {
        Object val = redis.opsForHash().get(key, field);
        return val != null ? val.toString() : null;
    }

    @Override
    public Map<String, String> hgetAll(String key) {
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        Map<String, String> result = new java.util.HashMap<>();
        raw.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    @Override
    public Long hincrBy(String key, String field, long delta) {
        return redis.opsForHash().increment(key, field, delta);
    }

    @Override
    public void hdel(String key, String field) {
        redis.opsForHash().delete(key, field);
    }

    @Override
    public void sadd(String key, String... members) {
        redis.opsForSet().add(key, members);
    }

    @Override
    public void srem(String key, String... members) {
        redis.opsForSet().remove(key, (Object[]) members);
    }

    @Override
    public Set<String> smembers(String key) {
        return redis.opsForSet().members(key);
    }

    @Override
    public Long scard(String key) {
        return redis.opsForSet().size(key);
    }

    @Override
    public void expire(String key, Duration ttl) {
        redis.expire(key, ttl);
    }

    @Override
    public void del(String key) {
        redis.delete(key);
    }
}
