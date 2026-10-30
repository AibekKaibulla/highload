package com.polls.poll;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import com.polls.api.dto.Dtos.CreatePollResult;
import com.polls.api.dto.Dtos.OptionDto;
import com.polls.api.dto.Dtos.PollDto;
import com.polls.api.dto.Dtos.PollListDto;
import com.polls.common.Chaos;
import com.polls.common.DbTimeouts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Business logic shared by the gRPC and the REST transport. */
@Service
public class PollLogic {
    private static final Logger log = LoggerFactory.getLogger(PollLogic.class);
    private static final Duration META_TTL = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Chaos chaos;

    public PollLogic(JdbcTemplate jdbc, TransactionTemplate tx, StringRedisTemplate redis, ObjectMapper json,
                     Chaos chaos) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.redis = redis;
        this.json = json;
        this.chaos = chaos;
    }

    /**
     * Idempotent poll creation.
     * The key, the poll and the stored response are written in ONE transaction:
     * either all exist or none. If two retries race, the second INSERT waits on the
     * unique (user_id, key) index until the first commits, then sees the stored response.
     */
    public CreatePollResult create(long creatorId, String question, List<String> options,
                                   String idempotencyKey, long timeoutMs) {
        validate(creatorId, question, options, idempotencyKey);
        chaos.before();
        String hash = requestHash(question, options);

        CreatePollResult result = tx.execute(status -> {
            DbTimeouts.apply(jdbc, timeoutMs);
            if (idempotencyKey != null) {
                int inserted = jdbc.update(
                        "INSERT INTO idempotency_keys (user_id, key, request_hash) VALUES (?, ?::uuid, ?) "
                                + "ON CONFLICT DO NOTHING",
                        creatorId, idempotencyKey, hash);
                if (inserted == 0) {
                    Map<String, Object> row = jdbc.queryForMap(
                            "SELECT request_hash, response::text AS response FROM idempotency_keys "
                                    + "WHERE user_id = ? AND key = ?::uuid",
                            creatorId, idempotencyKey);
                    if (!hash.equals(row.get("request_hash"))) {
                        throw new ApiException(ErrorCode.FAILED_PRECONDITION,
                                "Idempotency-Key was already used with a different request body");
                    }
                    return new CreatePollResult(fromJson((String) row.get("response")), true);
                }
            }
            PollDto poll = insertPoll(creatorId, question, options);
            if (idempotencyKey != null) {
                jdbc.update("UPDATE idempotency_keys SET status_code = 201, response = ?::jsonb "
                        + "WHERE user_id = ? AND key = ?::uuid", toJson(poll), creatorId, idempotencyKey);
            }
            return new CreatePollResult(poll, false);
        });

        if (!result.replayed()) {
            cacheNewPoll(result.poll());
        }
        chaos.after();
        return result;
    }

    public PollDto get(long pollId, long timeoutMs) {
        if (pollId <= 0) throw new ApiException(ErrorCode.INVALID_ARGUMENT, "poll_id must be positive");
        chaos.before();
        String cached = safeRedis(() -> redis.opsForValue().get(metaKey(pollId)));
        if (cached != null) {
            return fromJson(cached);
        }
        List<PollDto> found = tx.execute(status -> {
            DbTimeouts.apply(jdbc, timeoutMs);
            return loadPolls("WHERE poll_id = ?", pollId);
        });
        if (found.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND, "poll " + pollId + " not found");
        PollDto poll = found.get(0);
        safeRedis(() -> {
            redis.opsForValue().set(metaKey(pollId), toJson(poll), META_TTL);
            return null;
        });
        return poll;
    }

    /** Keyset pagination. Used as the "large payload" in the protocol comparison. */
    public PollListDto list(long afterId, int limit, long timeoutMs) {
        int size = limit <= 0 ? 100 : Math.min(limit, 1000);
        chaos.before();
        List<PollDto> polls = tx.execute(status -> {
            DbTimeouts.apply(jdbc, timeoutMs);
            return loadPolls("WHERE poll_id > ? ORDER BY poll_id LIMIT " + size, Math.max(0, afterId));
        });
        return new PollListDto(polls);
    }

    /** Keys only need to live as long as a client could still retry. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void cleanupIdempotencyKeys() {
        int deleted = jdbc.update("DELETE FROM idempotency_keys WHERE created_at < now() - interval '24 hours'");
        if (deleted > 0) log.info("Deleted {} expired idempotency keys", deleted);
    }

    private PollDto insertPoll(long creatorId, String question, List<String> options) {
        Map<String, Object> row = jdbc.queryForMap(
                "INSERT INTO polls (creator_id, question) VALUES (?, ?) "
                        + "RETURNING poll_id, (extract(epoch FROM created_at) * 1000)::bigint AS created_ms",
                creatorId, question);
        long pollId = ((Number) row.get("poll_id")).longValue();
        long createdMs = ((Number) row.get("created_ms")).longValue();
        List<OptionDto> opts = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            Long optionId = jdbc.queryForObject(
                    "INSERT INTO options (poll_id, text, position) VALUES (?, ?, ?) RETURNING option_id",
                    Long.class, pollId, options.get(i), i);
            opts.add(new OptionDto(optionId, options.get(i)));
        }
        return new PollDto(pollId, creatorId, question, opts, createdMs);
    }

    /** Two queries: the polls, then all their options at once (no N+1). */
    private List<PollDto> loadPolls(String where, Object... args) {
        record Row(long pollId, long creatorId, String question, long createdMs) {
        }
        List<Row> rows = jdbc.query(
                "SELECT poll_id, creator_id, question, (extract(epoch FROM created_at) * 1000)::bigint AS created_ms "
                        + "FROM polls " + where,
                (rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4)), args);
        if (rows.isEmpty()) return List.of();

        long min = rows.get(0).pollId();
        long max = rows.get(rows.size() - 1).pollId();
        Map<Long, List<OptionDto>> optionsByPoll = new HashMap<>();
        jdbc.query("SELECT poll_id, option_id, text FROM options WHERE poll_id BETWEEN ? AND ? "
                        + "ORDER BY poll_id, position",
                rs -> {
                    optionsByPoll.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new OptionDto(rs.getLong(2), rs.getString(3)));
                }, min, max);

        return rows.stream()
                .map(r -> new PollDto(r.pollId(), r.creatorId(), r.question(),
                        optionsByPoll.getOrDefault(r.pollId(), List.of()), r.createdMs()))
                .toList();
    }

    /** Cache metadata and create the counters hash with zeros, so an empty hash later means "cache miss". */
    private void cacheNewPoll(PollDto poll) {
        safeRedis(() -> {
            redis.opsForValue().set(metaKey(poll.pollId()), toJson(poll), META_TTL);
            Map<String, String> zeros = new LinkedHashMap<>();
            poll.options().forEach(o -> zeros.put(String.valueOf(o.optionId()), "0"));
            redis.opsForHash().putAll("poll:" + poll.pollId() + ":counts", zeros);
            return null;
        });
    }

    private static void validate(long creatorId, String question, List<String> options, String key) {
        if (creatorId <= 0) throw new ApiException(ErrorCode.INVALID_ARGUMENT, "creator_id must be positive");
        if (question == null || question.isBlank() || question.length() > 300) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "question must be 1..300 characters");
        }
        if (options == null || options.size() < 2 || options.size() > 10) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "a poll needs 2..10 options");
        }
        for (String o : options) {
            if (o == null || o.isBlank() || o.length() > 100) {
                throw new ApiException(ErrorCode.INVALID_ARGUMENT, "each option must be 1..100 characters");
            }
        }
        if (key != null) {
            try {
                UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.INVALID_ARGUMENT, "Idempotency-Key must be a UUID");
            }
        }
    }

    private static String requestHash(String question, List<String> options) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(question.getBytes(StandardCharsets.UTF_8));
            for (String o : options) {
                sha.update((byte) 0);
                sha.update(o.getBytes(StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String metaKey(long pollId) {
        return "poll:" + pollId + ":meta";
    }

    /** Redis is only a cache here: if it fails, log and continue with the database. */
    private <T> T safeRedis(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, falling back to Postgres: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private PollDto fromJson(String s) {
        try {
            return json.readValue(s, PollDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
