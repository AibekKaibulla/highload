package com.polls.gateway.backend;

import com.polls.api.dto.Dtos.CastVoteResult;
import com.polls.api.dto.Dtos.CreatePollBody;
import com.polls.api.dto.Dtos.CreatePollResult;
import com.polls.api.dto.Dtos.PollDto;
import com.polls.api.dto.Dtos.PollListDto;
import com.polls.api.dto.Dtos.ResultsDto;

/** Transport-independent view of the internal services. Implemented by gRPC and by REST/JSON. */
public final class Backends {
    private Backends() {
    }

    public interface PollBackend {
        CreatePollResult create(long userId, CreatePollBody body, String idempotencyKey, long timeoutMs);

        PollDto get(long pollId, long timeoutMs);

        PollListDto list(long afterId, int limit, long timeoutMs);
    }

    public interface VoteBackend {
        CastVoteResult cast(long pollId, long userId, long optionId, long timeoutMs);

        ResultsDto results(long pollId, long timeoutMs);
    }
}
