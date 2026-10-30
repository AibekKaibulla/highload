package com.polls.poll;

import com.polls.api.Headers;
import com.polls.api.dto.Dtos.CreatePollBody;
import com.polls.api.dto.Dtos.CreatePollResult;
import com.polls.api.dto.Dtos.PollDto;
import com.polls.api.dto.Dtos.PollListDto;
import com.polls.common.ServerDeadlines;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal REST/JSON baseline: same operations as GrpcPollService, for the comparison. */
@RestController
@RequestMapping("/internal/polls")
public class InternalPollController {
    private final PollLogic logic;

    public InternalPollController(PollLogic logic) {
        this.logic = logic;
    }

    @PostMapping
    public CreatePollResult create(@RequestHeader(Headers.USER_ID) long userId,
                                   @RequestHeader(value = Headers.IDEMPOTENCY_KEY, required = false) String key,
                                   @RequestHeader(value = Headers.DEADLINE_MS, required = false) String deadline,
                                   @RequestBody CreatePollBody body) {
        return logic.create(userId, body.question(), body.options(), key, ServerDeadlines.fromHeader(deadline));
    }

    @GetMapping("/{pollId}")
    public PollDto get(@PathVariable long pollId,
                       @RequestHeader(value = Headers.DEADLINE_MS, required = false) String deadline) {
        return logic.get(pollId, ServerDeadlines.fromHeader(deadline));
    }

    @GetMapping
    public PollListDto list(@RequestParam(name = "after_id", defaultValue = "0") long afterId,
                            @RequestParam(defaultValue = "100") int limit,
                            @RequestHeader(value = Headers.DEADLINE_MS, required = false) String deadline) {
        return logic.list(afterId, limit, ServerDeadlines.fromHeader(deadline));
    }
}
