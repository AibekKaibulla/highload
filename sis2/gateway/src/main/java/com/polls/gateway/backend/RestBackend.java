package com.polls.gateway.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import com.polls.api.Headers;
import com.polls.api.dto.Dtos.CastVoteResult;
import com.polls.api.dto.Dtos.CreatePollBody;
import com.polls.api.dto.Dtos.CreatePollResult;
import com.polls.api.dto.Dtos.ErrorBody;
import com.polls.api.dto.Dtos.PollDto;
import com.polls.api.dto.Dtos.PollListDto;
import com.polls.api.dto.Dtos.ResultsDto;
import com.polls.api.dto.Dtos.VoteBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * REST/JSON baseline client over HTTP/1.1 with keep-alive.
 * The JDK client keeps a pool of reusable connections, but HTTP/1.1 carries only
 * one request at a time per connection, so concurrency = number of connections.
 * REST has no built-in deadline: we send the remaining budget in X-Deadline-Ms.
 */
@Component
public class RestBackend implements Backends.PollBackend, Backends.VoteBackend {
    private static final String P = "rest";

    private final HttpClient client;
    private final ObjectMapper json;
    private final String pollUrl;
    private final String voteUrl;
    private final PayloadMetrics payload;

    public RestBackend(@Value("${backends.poll.rest-url}") String pollUrl,
                       @Value("${backends.vote.rest-url}") String voteUrl,
                       @Value("${backends.connect-timeout-ms}") int connectTimeoutMs,
                       ObjectMapper json, PayloadMetrics payload) {
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        this.json = json;
        this.pollUrl = pollUrl;
        this.voteUrl = voteUrl;
        this.payload = payload;
    }

    @Override
    public CreatePollResult create(long userId, CreatePollBody body, String key, long timeoutMs) {
        Map<String, String> headers = key == null
                ? Map.of(Headers.USER_ID, String.valueOf(userId))
                : Map.of(Headers.USER_ID, String.valueOf(userId), Headers.IDEMPOTENCY_KEY, key);
        return send("create_poll", "POST", pollUrl + "/internal/polls", body, headers, timeoutMs,
                CreatePollResult.class);
    }

    @Override
    public PollDto get(long pollId, long timeoutMs) {
        return send("get_poll", "GET", pollUrl + "/internal/polls/" + pollId, null, Map.of(), timeoutMs,
                PollDto.class);
    }

    @Override
    public PollListDto list(long afterId, int limit, long timeoutMs) {
        return send("list_polls", "GET", pollUrl + "/internal/polls?after_id=" + afterId + "&limit=" + limit,
                null, Map.of(), timeoutMs, PollListDto.class);
    }

    @Override
    public CastVoteResult cast(long pollId, long userId, long optionId, long timeoutMs) {
        return send("cast_vote", "POST", voteUrl + "/internal/polls/" + pollId + "/votes", new VoteBody(optionId),
                Map.of(Headers.USER_ID, String.valueOf(userId)), timeoutMs, CastVoteResult.class);
    }

    @Override
    public ResultsDto results(long pollId, long timeoutMs) {
        return send("get_results", "GET", voteUrl + "/internal/polls/" + pollId + "/results", null, Map.of(),
                timeoutMs, ResultsDto.class);
    }

    private <T> T send(String method, String httpMethod, String url, Object body, Map<String, String> headers,
                       long timeoutMs, Class<T> type) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))           // request timeout = remaining budget
                    .header(Headers.DEADLINE_MS, String.valueOf(timeoutMs))
                    .header("Accept", "application/json");
            headers.forEach(b::header);
            if (body != null) {
                byte[] bytes = json.writeValueAsBytes(body);
                payload.record(method, P, "request", bytes.length);
                b.header("Content-Type", "application/json").method(httpMethod, HttpRequest.BodyPublishers.ofByteArray(bytes));
            } else {
                payload.record(method, P, "request", 0);
                b.method(httpMethod, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<byte[]> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            payload.record(method, P, "response", resp.body().length);
            if (resp.statusCode() >= 400) {
                String msg = "HTTP " + resp.statusCode();
                try {
                    msg = json.readValue(resp.body(), ErrorBody.class).error().message();
                } catch (IOException | RuntimeException ignored) {
                    // body was not our error format
                }
                throw new ApiException(ErrorCode.fromHttp(resp.statusCode()), msg);
            }
            return json.readValue(resp.body(), type);
        } catch (HttpConnectTimeoutException e) {
            throw new ApiException(ErrorCode.UNAVAILABLE, "connect timeout to " + url);
        } catch (HttpTimeoutException e) {
            throw new ApiException(ErrorCode.DEADLINE_EXCEEDED, "request timeout after " + timeoutMs + " ms");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.UNAVAILABLE, "I/O error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.UNAVAILABLE, "interrupted");
        }
    }
}
