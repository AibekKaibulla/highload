package com.polls.api.dto;

import java.util.List;

/**
 * JSON bodies for the REST APIs (public and internal baseline).
 * Field names become snake_case via spring.jackson.property-naming-strategy.
 * They carry the same fields as polls.proto.
 */
public final class Dtos {
    private Dtos() {
    }

    public record OptionDto(long optionId, String text) {
    }

    public record PollDto(long pollId, long creatorId, String question, List<OptionDto> options, long createdAtMs) {
    }

    public record PollListDto(List<PollDto> polls) {
    }

    /** Body of POST /v1/polls and POST /internal/polls. */
    public record CreatePollBody(String question, List<String> options) {
    }

    public record CreatePollResult(PollDto poll, boolean replayed) {
    }

    /** Body of POST /v1/polls/{id}/votes and the internal equivalent. */
    public record VoteBody(Long optionId) {
    }

    /** status: CREATED or ALREADY_VOTED_SAME. */
    public record CastVoteResult(String status, long pollId, long optionId) {
    }

    public record OptionCountDto(long optionId, long count) {
    }

    public record ResultsDto(long pollId, long total, List<OptionCountDto> results) {
    }

    public record ErrorDetail(String code, String message) {
    }

    public record ErrorBody(ErrorDetail error) {
        public static ErrorBody of(String code, String message) {
            return new ErrorBody(new ErrorDetail(code, message));
        }
    }
}
