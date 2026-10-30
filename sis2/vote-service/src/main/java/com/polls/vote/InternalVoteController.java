package com.polls.vote;

import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import com.polls.api.Headers;
import com.polls.api.dto.Dtos.CastVoteResult;
import com.polls.api.dto.Dtos.ResultsDto;
import com.polls.api.dto.Dtos.VoteBody;
import com.polls.common.ServerDeadlines;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal REST/JSON baseline: same operations as GrpcVoteService, for the comparison. */
@RestController
@RequestMapping("/internal/polls/{pollId}")
public class InternalVoteController {
    private final VoteLogic logic;

    public InternalVoteController(VoteLogic logic) {
        this.logic = logic;
    }

    @PostMapping("/votes")
    public CastVoteResult cast(@PathVariable long pollId,
                               @RequestHeader(Headers.USER_ID) long userId,
                               @RequestHeader(value = Headers.DEADLINE_MS, required = false) String deadline,
                               @RequestBody VoteBody body) {
        if (body.optionId() == null) throw new ApiException(ErrorCode.INVALID_ARGUMENT, "option_id is required");
        return logic.cast(pollId, userId, body.optionId(), ServerDeadlines.fromHeader(deadline));
    }

    @GetMapping("/results")
    public ResultsDto results(@PathVariable long pollId,
                              @RequestHeader(value = Headers.DEADLINE_MS, required = false) String deadline) {
        return logic.results(pollId, ServerDeadlines.fromHeader(deadline));
    }
}
