package com.polls.gateway.backend;

import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import com.polls.api.ProtoMapper;
import com.polls.api.dto.Dtos.CastVoteResult;
import com.polls.api.dto.Dtos.CreatePollBody;
import com.polls.api.dto.Dtos.CreatePollResult;
import com.polls.api.dto.Dtos.PollDto;
import com.polls.api.dto.Dtos.PollListDto;
import com.polls.api.dto.Dtos.ResultsDto;
import com.polls.api.proto.CastVoteRequest;
import com.polls.api.proto.CastVoteResponse;
import com.polls.api.proto.CreatePollRequest;
import com.polls.api.proto.CreatePollResponse;
import com.polls.api.proto.GetPollRequest;
import com.polls.api.proto.GetResultsRequest;
import com.polls.api.proto.ListPollsRequest;
import com.polls.api.proto.Poll;
import com.polls.api.proto.PollList;
import com.polls.api.proto.PollServiceGrpc;
import com.polls.api.proto.Results;
import com.polls.api.proto.VoteServiceGrpc;
import com.google.protobuf.MessageLite;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.ChannelOption;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * gRPC client. One long-lived channel per service = one HTTP/2 connection that
 * multiplexes all concurrent calls (this is the "connection pool").
 * Built-in gRPC retries are disabled: retries happen only in ResilientCaller.
 * The deadline set here is sent to the server and propagates automatically.
 */
@Component
public class GrpcBackend implements Backends.PollBackend, Backends.VoteBackend {
    private static final String P = "grpc";

    private final ManagedChannel pollChannel;
    private final ManagedChannel voteChannel;
    private final PollServiceGrpc.PollServiceBlockingStub pollStub;
    private final VoteServiceGrpc.VoteServiceBlockingStub voteStub;
    private final PayloadMetrics payload;

    public GrpcBackend(@Value("${backends.poll.grpc-target}") String pollTarget,
                       @Value("${backends.vote.grpc-target}") String voteTarget,
                       @Value("${backends.connect-timeout-ms}") int connectTimeoutMs,
                       PayloadMetrics payload) {
        this.pollChannel = channel(pollTarget, connectTimeoutMs);
        this.voteChannel = channel(voteTarget, connectTimeoutMs);
        this.pollStub = PollServiceGrpc.newBlockingStub(pollChannel);
        this.voteStub = VoteServiceGrpc.newBlockingStub(voteChannel);
        this.payload = payload;
    }

    private static ManagedChannel channel(String target, int connectTimeoutMs) {
        return NettyChannelBuilder.forTarget(target)
                .usePlaintext()
                .disableRetry()
                // One HTTP/2 connection sticks to one server; round_robin over DNS results
                // spreads calls when there are several replicas (client-side L7 balancing).
                .defaultLoadBalancingPolicy("round_robin")
                .withOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public CreatePollResult create(long userId, CreatePollBody body, String key, long timeoutMs) {
        CreatePollRequest req = CreatePollRequest.newBuilder()
                .setCreatorId(userId)
                .setQuestion(body.question() == null ? "" : body.question())
                .addAllOptions(body.options() == null ? java.util.List.of() : body.options())
                .setIdempotencyKey(key == null ? "" : key)
                .build();
        CreatePollResponse r = call("create_poll", req,
                () -> pollStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).createPoll(req));
        return new CreatePollResult(ProtoMapper.fromProto(r.getPoll()), r.getReplayed());
    }

    @Override
    public PollDto get(long pollId, long timeoutMs) {
        GetPollRequest req = GetPollRequest.newBuilder().setPollId(pollId).build();
        Poll r = call("get_poll", req, () -> pollStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).getPoll(req));
        return ProtoMapper.fromProto(r);
    }

    @Override
    public PollListDto list(long afterId, int limit, long timeoutMs) {
        ListPollsRequest req = ListPollsRequest.newBuilder().setAfterId(afterId).setLimit(limit).build();
        PollList r = call("list_polls", req,
                () -> pollStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).listPolls(req));
        return ProtoMapper.fromProto(r);
    }

    @Override
    public CastVoteResult cast(long pollId, long userId, long optionId, long timeoutMs) {
        CastVoteRequest req = CastVoteRequest.newBuilder()
                .setPollId(pollId).setUserId(userId).setOptionId(optionId).build();
        CastVoteResponse r = call("cast_vote", req,
                () -> voteStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).castVote(req));
        return ProtoMapper.fromProto(r);
    }

    @Override
    public ResultsDto results(long pollId, long timeoutMs) {
        GetResultsRequest req = GetResultsRequest.newBuilder().setPollId(pollId).build();
        Results r = call("get_results", req,
                () -> voteStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).getResults(req));
        return ProtoMapper.fromProto(r);
    }

    private <T extends MessageLite> T call(String method, MessageLite request, Supplier<T> rpc) {
        payload.record(method, P, "request", request.getSerializedSize());
        try {
            T response = rpc.get();
            payload.record(method, P, "response", response.getSerializedSize());
            return response;
        } catch (StatusRuntimeException e) {
            String msg = e.getStatus().getDescription() != null ? e.getStatus().getDescription()
                    : e.getStatus().getCode().name();
            throw new ApiException(ErrorCode.fromGrpc(e.getStatus().getCode()), msg);
        }
    }

    @PreDestroy
    public void shutdown() {
        pollChannel.shutdown();
        voteChannel.shutdown();
    }
}
