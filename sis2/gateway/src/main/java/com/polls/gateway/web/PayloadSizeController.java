package com.polls.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.MessageLite;
import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import com.polls.api.ProtoMapper;
import com.polls.api.dto.Dtos.CastVoteResult;
import com.polls.api.dto.Dtos.PollDto;
import com.polls.api.dto.Dtos.PollListDto;
import com.polls.api.dto.Dtos.ResultsDto;
import com.polls.gateway.backend.RestBackend;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * GET /admin/payload-sizes : encodes the SAME real data as JSON and as Protobuf and reports
 * body size (raw and gzip) plus encode/decode CPU time. Separates "encoding" from "transport".
 */
@RestController
@ConditionalOnProperty(name = "auth.dev-endpoints", havingValue = "true")
public class PayloadSizeController {
    private final RestBackend rest;
    private final ObjectMapper json;

    public PayloadSizeController(RestBackend rest, ObjectMapper json) {
        this.rest = rest;
        this.json = json;
    }

    @GetMapping("/admin/payload-sizes")
    public List<Map<String, Object>> sizes(@RequestParam(defaultValue = "500") int limit) throws IOException {
        PollListDto list = rest.list(0, limit, 5000);
        if (list.polls().isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND, "seed some polls first");
        PollDto poll = list.polls().stream()
                .max(java.util.Comparator.comparingInt(p -> p.options().size())).orElseThrow();
        ResultsDto results = rest.results(poll.pollId(), 5000);
        CastVoteResult vote = new CastVoteResult("CREATED", poll.pollId(), poll.options().get(0).optionId());

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(measure("cast_vote response", vote, CastVoteResult.class, ProtoMapper.toProto(vote), 20_000));
        rows.add(measure("get_results (" + results.results().size() + " options)", results, ResultsDto.class,
                ProtoMapper.toProto(results), 20_000));
        rows.add(measure("get_poll", poll, PollDto.class, ProtoMapper.toProto(poll), 20_000));
        for (int n : new int[]{10, 100, list.polls().size()}) {
            if (n > list.polls().size()) continue;
            PollListDto sub = new PollListDto(list.polls().subList(0, n));
            rows.add(measure("list_polls (" + n + " polls)", sub, PollListDto.class, ProtoMapper.toProto(sub),
                    Math.max(200, 20_000 / n)));
        }
        return rows;
    }

    private Map<String, Object> measure(String name, Object dto, Class<?> type, MessageLite proto, int iters)
            throws IOException {
        byte[] jsonBytes = json.writeValueAsBytes(dto);
        byte[] protoBytes = proto.toByteArray();

        // Warm up the JIT, then time encode+decode for both formats.
        for (int i = 0; i < iters; i++) {
            json.readValue(json.writeValueAsBytes(dto), type);
            proto.getParserForType().parseFrom(proto.toByteArray());
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) json.readValue(json.writeValueAsBytes(dto), type);
        long t1 = System.nanoTime();
        for (int i = 0; i < iters; i++) proto.getParserForType().parseFrom(proto.toByteArray());
        long t2 = System.nanoTime();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("payload", name);
        row.put("json_bytes", jsonBytes.length);
        row.put("json_gzip_bytes", gzip(jsonBytes));
        row.put("protobuf_bytes", protoBytes.length);
        row.put("protobuf_gzip_bytes", gzip(protoBytes));
        row.put("json_to_protobuf_ratio", Math.round(100.0 * jsonBytes.length / protoBytes.length) / 100.0);
        row.put("json_encode_decode_us", Math.round((t1 - t0) / 10.0 / iters) / 100.0);
        row.put("protobuf_encode_decode_us", Math.round((t2 - t1) / 10.0 / iters) / 100.0);
        return row;
    }

    private static int gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.size();
    }
}
