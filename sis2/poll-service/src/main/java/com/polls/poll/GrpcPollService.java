package com.polls.poll;

import com.polls.api.ProtoMapper;
import com.polls.api.proto.CreatePollRequest;
import com.polls.api.proto.CreatePollResponse;
import com.polls.api.proto.GetPollRequest;
import com.polls.api.proto.ListPollsRequest;
import com.polls.api.proto.Poll;
import com.polls.api.proto.PollList;
import com.polls.api.proto.PollServiceGrpc;
import com.polls.common.GrpcCalls;
import com.polls.common.ServerDeadlines;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/** gRPC transport for PollService. */
@Component
public class GrpcPollService extends PollServiceGrpc.PollServiceImplBase {
    private final PollLogic logic;

    public GrpcPollService(PollLogic logic) {
        this.logic = logic;
    }

    @Override
    public void createPoll(CreatePollRequest req, StreamObserver<CreatePollResponse> obs) {
        GrpcCalls.unary(obs, () -> {
            String key = req.getIdempotencyKey().isEmpty() ? null : req.getIdempotencyKey();
            var r = logic.create(req.getCreatorId(), req.getQuestion(), req.getOptionsList(), key,
                    ServerDeadlines.fromGrpc());
            return CreatePollResponse.newBuilder()
                    .setPoll(ProtoMapper.toProto(r.poll()))
                    .setReplayed(r.replayed())
                    .build();
        });
    }

    @Override
    public void getPoll(GetPollRequest req, StreamObserver<Poll> obs) {
        GrpcCalls.unary(obs, () -> ProtoMapper.toProto(logic.get(req.getPollId(), ServerDeadlines.fromGrpc())));
    }

    @Override
    public void listPolls(ListPollsRequest req, StreamObserver<PollList> obs) {
        GrpcCalls.unary(obs, () -> ProtoMapper.toProto(
                logic.list(req.getAfterId(), req.getLimit(), ServerDeadlines.fromGrpc())));
    }
}
