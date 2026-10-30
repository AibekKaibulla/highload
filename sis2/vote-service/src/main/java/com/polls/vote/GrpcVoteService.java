package com.polls.vote;

import com.polls.api.ProtoMapper;
import com.polls.api.proto.CastVoteRequest;
import com.polls.api.proto.CastVoteResponse;
import com.polls.api.proto.GetResultsRequest;
import com.polls.api.proto.Results;
import com.polls.api.proto.VoteServiceGrpc;
import com.polls.common.GrpcCalls;
import com.polls.common.ServerDeadlines;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/** gRPC transport for VoteService. */
@Component
public class GrpcVoteService extends VoteServiceGrpc.VoteServiceImplBase {
    private final VoteLogic logic;

    public GrpcVoteService(VoteLogic logic) {
        this.logic = logic;
    }

    @Override
    public void castVote(CastVoteRequest req, StreamObserver<CastVoteResponse> obs) {
        GrpcCalls.unary(obs, () -> ProtoMapper.toProto(
                logic.cast(req.getPollId(), req.getUserId(), req.getOptionId(), ServerDeadlines.fromGrpc())));
    }

    @Override
    public void getResults(GetResultsRequest req, StreamObserver<Results> obs) {
        GrpcCalls.unary(obs, () -> ProtoMapper.toProto(logic.results(req.getPollId(), ServerDeadlines.fromGrpc())));
    }
}
