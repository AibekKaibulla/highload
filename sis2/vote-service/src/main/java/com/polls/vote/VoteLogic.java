package com.polls.vote;

import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import com.polls.api.dto.Dtos.CastVoteResult;
import com.polls.api.dto.Dtos.OptionCountDto;
import com.polls.api.dto.Dtos.ResultsDto;
import com.polls.common.Chaos;
import com.polls.common.DbTimeouts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business logic shared by the gRPC and the REST transport. */
@Service
public class VoteLogic {
    private static final Logger log = LoggerFactory.getLogger(VoteLogic.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    private final Counter redisIncrementFailures;
    private final Counter duplicateVotes;
    private final Chaos chaos;

    public VoteLogic(JdbcTemplate jdbc, TransactionTemplate tx, StringRedisTemplate redis, MeterRegistry metrics,
                     Chaos chaos) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.redis = redis;
        this.chaos = chaos;
        this.redisIncrementFailures = Counter.builder("votes.redis.increment.failures")
                .description("Votes saved in Postgres whose Redis increment failed (fixed by the reconciler)")
                .register(metrics);
        this.duplicateVotes = Counter.builder("votes.duplicates")
                .description("Retries/duplicates blocked by PK(poll_id, user_id)")
                .register(metrics);
    }

    private record Outcome(boolean created, long optionId) {
    }

    /**
     * Idempotent by design: PK(poll_id, user_id) + ON CONFLICT DO NOTHING.
     * A retried vote never creates a second row, and the counter is only
     * incremented when a new row was actually inserted, so it never double-counts.
     * The composite FK (poll_id, option_id) -> options rejects an option of another poll.
     */
    public CastVoteResult cast(long pollId, long userId, long optionId, long timeoutMs) {
        if (pollId <= 0 || userId <= 0 || optionId <= 0) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "poll_id, user_id and option_id must be positive");
        }
        chaos.before();
        Outcome outcome = tx.execute(status -> {
            DbTimeouts.apply(jdbc, timeoutMs);
            List<Long> inserted = jdbc.queryForList(
                    "INSERT INTO votes (poll_id, user_id, option_id) VALUES (?, ?, ?) "
                            + "ON CONFLICT (poll_id, user_id) DO NOTHING RETURNING option_id",
                    Long.class, pollId, userId, optionId);
            if (!inserted.isEmpty()) {
                return new Outcome(true, optionId);
            }
            Long existing = jdbc.queryForObject(
                    "SELECT option_id FROM votes WHERE poll_id = ? AND user_id = ?", Long.class, pollId, userId);
            return new Outcome(false, existing);
        });

        if (outcome.created()) {
            // After COMMIT. If this fails, the vote is still saved and the reconciler fixes the counter.
            try {
                redis.opsForHash().increment(countsKey(pollId), String.valueOf(optionId), 1);
            } catch (RuntimeException e) {
                redisIncrementFailures.increment();
                log.warn("Redis HINCRBY failed for poll {}: {}", pollId, e.getMessage());
            }
            chaos.after();
            return new CastVoteResult("CREATED", pollId, optionId);
        }
        duplicateVotes.increment();
        if (outcome.optionId() == optionId) {
            return new CastVoteResult("ALREADY_VOTED_SAME", pollId, optionId);
        }
        throw new ApiException(ErrorCode.ALREADY_EXISTS,
                "user already voted for option " + outcome.optionId() + " in this poll");
    }

    /** Read path: Redis hash (~1 ms); on a miss, count in Postgres and refill the cache. */
    public ResultsDto results(long pollId, long timeoutMs) {
        if (pollId <= 0) throw new ApiException(ErrorCode.INVALID_ARGUMENT, "poll_id must be positive");
        chaos.before();

        Map<Object, Object> cached = null;
        try {
            cached = redis.opsForHash().entries(countsKey(pollId));
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, counting in Postgres: {}", e.getMessage());
        }
        if (cached != null && !cached.isEmpty()) {
            List<OptionCountDto> counts = cached.entrySet().stream()
                    .map(en -> new OptionCountDto(Long.parseLong(en.getKey().toString()),
                            Long.parseLong(en.getValue().toString())))
                    .sorted(Comparator.comparingLong(OptionCountDto::optionId))
                    .toList();
            return toResults(pollId, counts);
        }

        List<OptionCountDto> counts = tx.execute(status -> {
            DbTimeouts.apply(jdbc, timeoutMs);
            return jdbc.query(
                    "SELECT o.option_id, count(v.user_id) FROM options o "
                            + "LEFT JOIN votes v ON v.poll_id = o.poll_id AND v.option_id = o.option_id "
                            + "WHERE o.poll_id = ? GROUP BY o.option_id ORDER BY o.option_id",
                    (rs, i) -> new OptionCountDto(rs.getLong(1), rs.getLong(2)), pollId);
        });
        if (counts.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND, "poll " + pollId + " not found");

        try {
            Map<String, String> hash = new LinkedHashMap<>();
            counts.forEach(c -> hash.put(String.valueOf(c.optionId()), String.valueOf(c.count())));
            redis.opsForHash().putAll(countsKey(pollId), hash);
        } catch (RuntimeException e) {
            log.warn("Could not refill Redis for poll {}: {}", pollId, e.getMessage());
        }
        return toResults(pollId, counts);
    }

    static String countsKey(long pollId) {
        return "poll:" + pollId + ":counts";
    }

    private static ResultsDto toResults(long pollId, List<OptionCountDto> counts) {
        long total = counts.stream().mapToLong(OptionCountDto::count).sum();
        return new ResultsDto(pollId, total, counts);
    }
}
