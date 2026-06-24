package com.dropbid.auction.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    // ── Auction Redis (hot path: Lua script, auction state, schedule) ─────────

    @Value("${spring.data.redis.host:localhost}")
    private String auctionHost;

    @Value("${spring.data.redis.port:6379}")
    private int auctionPort;

    @Bean
    @Primary
    public RedisConnectionFactory auctionRedisConnectionFactory() {
        return new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(auctionHost, auctionPort));
    }

    @Bean
    @Primary
    public StringRedisTemplate stringRedisTemplate(
            RedisConnectionFactory auctionRedisConnectionFactory) {
        return new StringRedisTemplate(auctionRedisConnectionFactory);
    }

    // ── Stream Redis (Redis Streams: bid_placed, auction:closed, etc.) ────────

    @Value("${stream.redis.host:localhost}")
    private String streamHost;

    @Value("${stream.redis.port:6380}")
    private int streamPort;

    @Bean("streamRedisConnectionFactory")
    public RedisConnectionFactory streamRedisConnectionFactory() {
        return new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(streamHost, streamPort));
    }

    @Bean("streamRedisTemplate")
    public StringRedisTemplate streamRedisTemplate() {
        return new StringRedisTemplate(streamRedisConnectionFactory());
    }
}
