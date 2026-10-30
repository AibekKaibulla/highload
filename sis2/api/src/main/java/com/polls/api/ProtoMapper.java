package com.polls.api;

import com.polls.api.dto.Dtos.CastVoteResult;
import com.polls.api.dto.Dtos.OptionCountDto;
import com.polls.api.dto.Dtos.OptionDto;
import com.polls.api.dto.Dtos.PollDto;
import com.polls.api.dto.Dtos.PollListDto;
import com.polls.api.dto.Dtos.ResultsDto;
import com.polls.api.proto.CastVoteResponse;
import com.polls.api.proto.Option;
import com.polls.api.proto.OptionCount;
import com.polls.api.proto.Poll;
import com.polls.api.proto.PollList;
import com.polls.api.proto.Results;

/** Converts between Protobuf messages and the JSON DTOs. */
public final class ProtoMapper {
    private ProtoMapper() {
    }

    public static Poll toProto(PollDto p) {
        Poll.Builder b = Poll.newBuilder()
                .setPollId(p.pollId())
                .setCreatorId(p.creatorId())
                .setQuestion(p.question())
                .setCreatedAtMs(p.createdAtMs());
        for (OptionDto o : p.options()) {
            b.addOptions(Option.newBuilder().setOptionId(o.optionId()).setText(o.text()));
        }
        return b.build();
    }

    public static PollDto fromProto(Poll p) {
        return new PollDto(p.getPollId(), p.getCreatorId(), p.getQuestion(),
                p.getOptionsList().stream().map(o -> new OptionDto(o.getOptionId(), o.getText())).toList(),
                p.getCreatedAtMs());
    }

    public static PollList toProto(PollListDto list) {
        PollList.Builder b = PollList.newBuilder();
        list.polls().forEach(p -> b.addPolls(toProto(p)));
        return b.build();
    }

    public static PollListDto fromProto(PollList list) {
        return new PollListDto(list.getPollsList().stream().map(ProtoMapper::fromProto).toList());
    }

    public static CastVoteResponse toProto(CastVoteResult r) {
        return CastVoteResponse.newBuilder()
                .setStatus(CastVoteResponse.Status.valueOf(r.status()))
                .setPollId(r.pollId())
                .setOptionId(r.optionId())
                .build();
    }

    public static CastVoteResult fromProto(CastVoteResponse r) {
        return new CastVoteResult(r.getStatus().name(), r.getPollId(), r.getOptionId());
    }

    public static Results toProto(ResultsDto r) {
        Results.Builder b = Results.newBuilder().setPollId(r.pollId()).setTotal(r.total());
        r.results().forEach(c -> b.addResults(OptionCount.newBuilder().setOptionId(c.optionId()).setCount(c.count())));
        return b.build();
    }

    public static ResultsDto fromProto(Results r) {
        return new ResultsDto(r.getPollId(), r.getTotal(),
                r.getResultsList().stream().map(c -> new OptionCountDto(c.getOptionId(), c.getCount())).toList());
    }
}
