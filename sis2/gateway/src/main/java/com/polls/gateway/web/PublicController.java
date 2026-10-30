package com.polls.gateway.web;

import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import com.polls.api.Headers;
import com.polls.api.dto.Dtos.CastVoteResult;
import com.polls.api.dto.Dtos.CreatePollBody;
import com.polls.api.dto.Dtos.CreatePollResult;
import com.polls.api.dto.Dtos.PollDto;
import com.polls.api.dto.Dtos.PollListDto;
import com.polls.api.dto.Dtos.ResultsDto;
import com.polls.api.dto.Dtos.VoteBody;
import com.polls.gateway.auth.Auth;
import com.polls.gateway.backend.BackendRouter;
import com.polls.gateway.resilience.ResilientCaller;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/** Public REST/JSON API (the edge: we don't own the clients, so no gRPC here). */
@RestController
@RequestMapping("/v1/polls")
public class PublicController {
    private final Auth auth;
    private final BackendRouter backends;
    private final ResilientCaller caller;

    public PublicController(Auth auth, BackendRouter backends, ResilientCaller caller) {
        this.auth = auth;
        this.backends = backends;
        this.caller = caller;
    }

    @PostMapping
    public ResponseEntity<PollDto> create(HttpServletRequest request,
                                          @RequestHeader(value = Headers.IDEMPOTENCY_KEY, required = false) String key,
                                          @RequestBody CreatePollBody body) {
        long userId = auth.userId(request);
        if (key == null || key.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "Idempotency-Key header is required");
        }
        try {
            UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "Idempotency-Key must be a UUID");
        }
        var backend = backends.polls();
        CreatePollResult r = caller.call("create_poll", t -> backend.create(userId, body, key, t));
        return ResponseEntity.created(URI.create("/v1/polls/" + r.poll().pollId()))
                .header(Headers.REPLAYED, String.valueOf(r.replayed()))
                .body(r.poll());
    }

    @GetMapping("/{pollId}")
    public ResponseEntity<PollDto> get(@PathVariable long pollId) {
        var backend = backends.polls();
        PollDto poll = caller.call("get_poll", t -> backend.get(pollId, t));
        // A poll never changes after creation.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60))).body(poll);
    }

    @GetMapping
    public PollListDto list(@RequestParam(name = "after_id", defaultValue = "0") long afterId,
                            @RequestParam(defaultValue = "100") int limit) {
        var backend = backends.polls();
        return caller.call("list_polls", t -> backend.list(afterId, limit, t));
    }

    @PostMapping("/{pollId}/votes")
    public ResponseEntity<CastVoteResult> vote(HttpServletRequest request, @PathVariable long pollId,
                                               @RequestBody VoteBody body) {
        long userId = auth.userId(request);
        if (body == null || body.optionId() == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "option_id is required");
        }
        var backend = backends.votes();
        CastVoteResult r = caller.call("cast_vote", t -> backend.cast(pollId, userId, body.optionId(), t));
        // 201 = new vote, 200 = the same vote again (retry), 409 = other option (thrown as ALREADY_EXISTS)
        HttpStatus status = "CREATED".equals(r.status()) ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(r);
    }

    @GetMapping("/{pollId}/results")
    public ResponseEntity<ResultsDto> results(@PathVariable long pollId) {
        var backend = backends.votes();
        ResultsDto r = caller.call("get_results", t -> backend.results(pollId, t));
        // Short cache instead of a CDN: results may be up to 1 s old.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(1))).body(r);
    }
}
