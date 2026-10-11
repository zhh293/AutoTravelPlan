package com.zhh.handsome.gaodemcptest.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TripPlanStreamableSseStoreTest {
    @Test void drainsEventsWrittenBetweenEmptyReadAndFinishedStatus() throws Exception {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.<Object, Object>opsForStream()).thenReturn(streams);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("trip:sse:status:task")).thenReturn("DONE");
        MapRecord<String, Object, Object> record = StreamRecords
                .<String, Object, Object>mapBacked(Map.of("event", "trip-done", "data", "[DONE]"))
                .withStreamKey("trip:sse:stream:task").withId(RecordId.of("1-0"));
        when(streams.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(), List.of(record), List.of(), List.of());
        SseEmitter emitter = mock(SseEmitter.class);
        new TripPlanStreamableSseStore(redis).streamRealtimeUntilDone("task", null, emitter, new AtomicBoolean());
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test void closedEmitterStopsReading() throws Exception {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.<Object, Object>opsForStream()).thenReturn(streams);
        MapRecord<String, Object, Object> record = StreamRecords
                .<String, Object, Object>mapBacked(Map.of("event", "trip-data", "data", "{}"))
                .withStreamKey("trip:sse:stream:task").withId(RecordId.of("1-0"));
        when(streams.read(any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(List.of(record));
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("completed")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        AtomicBoolean stop = new AtomicBoolean();
        new TripPlanStreamableSseStore(redis).streamRealtimeUntilDone("task", null, emitter, stop);
        assertTrue(stop.get());
        verify(streams, times(1)).read(any(StreamReadOptions.class), any(StreamOffset.class));
    }
}
