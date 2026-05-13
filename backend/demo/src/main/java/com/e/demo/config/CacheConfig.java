package com.e.demo.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Cache + Redis. Каждый кеш со своим TTL — иначе горячие данные
 * (например статус job'а который меняется каждые 5 сек) будут жить вечно.
 *
 * Имена кешей строки в коде через @Cacheable("slide-info") и т.д.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CACHE_SLIDE_INFO         = "slide-info";
    public static final String CACHE_JOB_STATUS         = "job-status";
    public static final String CACHE_SLIDES_BY_USER     = "slides-by-user";
    public static final String CACHE_CASES_BY_USER      = "cases-by-user";
    public static final String CACHE_DETECTIONS_SUMMARY = "detections-summary";

    /**
     * Этот бин активен ТОЛЬКО когда spring.cache.type=redis в application.yaml.
     * При cache.type=simple Spring сам создаст ConcurrentMapCacheManager, без сериализации.
     */
    @Bean
    @Conditional(RedisCacheCondition.class)
    public RedisCacheManager cacheManager(RedisConnectionFactory cf) {
        // Дефолт — на случай если в коде @Cacheable("новое-имя") без явного TTL
        RedisCacheConfiguration defaultCfg = baseCfg(Duration.ofSeconds(30));

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        perCache.put(CACHE_SLIDE_INFO,         baseCfg(Duration.ofHours(1)));      // immutable после загрузки
        perCache.put(CACHE_JOB_STATUS,         baseCfg(Duration.ofSeconds(3)));    // часто меняется
        perCache.put(CACHE_SLIDES_BY_USER,     baseCfg(Duration.ofSeconds(5)));    // дашборд пуллит каждые 5с
        perCache.put(CACHE_CASES_BY_USER,      baseCfg(Duration.ofSeconds(10)));
        perCache.put(CACHE_DETECTIONS_SUMMARY, baseCfg(Duration.ofMinutes(1)));

        return RedisCacheManager.builder(cf)
                .cacheDefaults(defaultCfg)
                .withInitialCacheConfigurations(perCache)
                .build();
    }

    private RedisCacheConfiguration baseCfg(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()       // null'ы не кешируем — иначе залипает «no data»
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonRedisSerializer()));
    }

    /**
     * Сериализатор с typing в формате WRAPPER_ARRAY: ["full.class.Name", {...payload...}].
     * Это совместимо и с Map<String,Object>, и с обычными классами/записями (SlideInfo).
     *
     * Без typing (мой предыдущий вариант) ломался viewer — getInfo возвращал Optional<Map>
     * вместо Optional<SlideInfo>, ClassCastException в TileController.
     *
     * As.PROPERTY (ещё более ранний вариант) ломался на Map с разнотиповыми значениями.
     */
    private GenericJackson2JsonRedisSerializer jsonRedisSerializer() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        m.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        m.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.WRAPPER_ARRAY);
        return new GenericJackson2JsonRedisSerializer(m);
    }

    /** Активирует RedisCacheManager только если spring.cache.type=redis. */
    public static class RedisCacheCondition implements Condition {
        @Override
        public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
            String type = ctx.getEnvironment().getProperty("spring.cache.type", "simple");
            return "redis".equalsIgnoreCase(type);
        }
    }
}
