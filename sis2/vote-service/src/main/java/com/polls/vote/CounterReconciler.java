package com.polls.vote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Background job: Postgres is the source of truth, Redis is a fast copy.
 * If a vote was committed but the HINCRBY failed (crash, Redis down),
 * this job recounts recently active polls and overwrites the Redis hash.
 */
@Component
public class CounterReconciler {
    private static final Logger log = LoggerFactory.getLogger(CounterReconciler.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final int windowSeconds;

    public CounterReconciler(JdbcTemplate jdbc, StringRedisTemplate redis,
                             @Value("${reconciler.window-seconds:120}") int windowSeconds) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.windowSeconds = windowSeconds;
    }

    @Scheduled(fixedDelayString = "${reconciler.interval-ms:60000}", initialDelay = 30_000)
    public void reconcile() {
        try {
            Map<Long, Map<String, String>> byPoll = new HashMap<>();
            jdbc.query("SELECT poll_id, option_id, count(*) FROM votes "
                            + "WHERE poll_id IN (SELECT DISTINCT poll_id FROM votes "
                            + "                  WHERE created_at > now() - (? * interval '1 second')) "
                            + "GROUP BY poll_id, option_id",
                    rs -> {
                        byPoll.computeIfAbsent(rs.getLong(1), k -> new HashMap<>())
                                .put(String.valueOf(rs.getLong(2)), String.valueOf(rs.getLong(3)));
                    }, windowSeconds);
            byPoll.forEach((pollId, counts) -> redis.opsForHash().putAll(VoteLogic.countsKey(pollId), counts));
            if (!byPoll.isEmpty()) log.info("Reconciled counters for {} active poll(s)", byPoll.size());
        } catch (RuntimeException e) {
            log.warn("Reconciler run failed, will retry next interval: {}", e.getMessage());
        }
    }
}
