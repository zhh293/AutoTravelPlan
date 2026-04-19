package com.zhh.handsome.gaodemcptest.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TripPlanStreamableSseStore {

    private static final String STREAM_PREFIX = "trip:sse:stream:";
    private static final String STATUS_PREFIX = "trip:sse:status:";
    private static final String STATUS_DONE = "DONE";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${trip.sse.stream.retention-seconds:300}")
    private long retentionSeconds;

    @Value("${trip.sse.stream.read-batch-size:100}")
    private int readBatchSize;

    @Value("${trip.sse.stream.block-seconds:5}")
    private long blockSeconds;

    public TripPlanStreamableSseStore(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    public String appendEvent(String taskId, String eventName, Object payload) {
        String streamKey = streamKey(taskId);
        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
        Map<String, String> body = new HashMap<>();
        body.put("event", eventName);
        body.put("data", toJson(payload));
        RecordId id = ops.add(StreamRecords.mapBacked(body).withStreamKey(streamKey));
        redisTemplate.expire(streamKey, Duration.ofSeconds(retentionSeconds));
        return id == null ? null : id.getValue();
    }

    public String replayHistory(String taskId, String lastEventId, SseEmitter emitter) throws IOException {
        String streamKey = streamKey(taskId);
        String cursor = normalizeLastEventId(lastEventId);
        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();

        List<MapRecord<String, Object, Object>> records;
        if ("0-0".equals(cursor)) {
            records = ops.range(streamKey, Range.unbounded(), Limit.of(readBatchSize));
        } else {
            records = ops.range(streamKey, Range.rightOpen(cursor, "+"), Limit.unlimited());
        }

        if (records == null || records.isEmpty()) {
            return cursor;
        }

        for (MapRecord<String, Object, Object> record : records) {
            cursor = emitRecord(emitter, record);
        }
        return cursor;
    }

    public void streamRealtimeUntilDone(String taskId, String startAfterEventId, SseEmitter emitter,
            AtomicBoolean stop) {
        String streamKey = streamKey(taskId);
        String cursor = normalizeLastEventId(startAfterEventId);
        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();

        while (!stop.get()) {
            List<MapRecord<String, Object, Object>> records = ops.read(
                    StreamReadOptions.empty()
                            .count(readBatchSize)
                            .block(Duration.ofSeconds(blockSeconds)),
                    StreamOffset.create(streamKey, ReadOffset.from(cursor)));

            if (records == null || records.isEmpty()) {
                if (isFinished(taskId)) {
                    return;
                }
                continue;
            }

            for (MapRecord<String, Object, Object> record : records) {
                if (stop.get()) {
                    return;
                }
                try {
                    cursor = emitRecord(emitter, record);
                } catch (IOException e) {
                    stop.set(true);
                    return;
                }
            }
        }
    }

    public void markFinished(String taskId) {
        redisTemplate.opsForValue().set(statusKey(taskId), STATUS_DONE, Duration.ofSeconds(retentionSeconds));
    }

    public boolean isFinished(String taskId) {
        Object value = redisTemplate.opsForValue().get(statusKey(taskId));
        return STATUS_DONE.equals(String.valueOf(value));
    }

    public boolean hasAnyEvent(String taskId) {
        String streamKey = streamKey(taskId);
        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
        List<MapRecord<String, Object, Object>> records = ops.range(streamKey, Range.unbounded(), Limit.of(1));
        return records != null && !records.isEmpty();
    }

    public void clearTask(String taskId) {
        redisTemplate.delete(streamKey(taskId));
        redisTemplate.delete(statusKey(taskId));
    }

    private String emitRecord(SseEmitter emitter, MapRecord<String, Object, Object> record) throws IOException {
        String id = record.getId().getValue();
        String eventName = String.valueOf(record.getValue().getOrDefault("event", "trip-data"));
        String data = String.valueOf(record.getValue().getOrDefault("data", "{}"));
        Object parsed = parseJsonOrRaw(data);
        emitter.send(SseEmitter.event().id(id).name(eventName).data(parsed));
        return id;
    }

    private Object parseJsonOrRaw(String json) {
        if (!StringUtils.hasText(json)) {
            return "";
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignore) {
            return json;
        }
    }

    private String toJson(Object payload) {
        if (payload == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return String.valueOf(payload);
        }
    }

    private String normalizeLastEventId(String lastEventId) {
        return StringUtils.hasText(lastEventId) ? lastEventId : "0-0";
    }

    private String streamKey(String taskId) {
        return STREAM_PREFIX + taskId;
    }

    private String statusKey(String taskId) {
        return STATUS_PREFIX + taskId;
    }
}
