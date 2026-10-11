package com.zhh.handsome.gaodemcptest.service;

import com.zhh.handsome.gaodemcptest.model.AmapPoiUtils;
import com.zhh.handsome.gaodemcptest.model.TripPlanItem;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TripPlanServiceLifecycleTest {
    private AmapPoiUtils poi;
    private ChatModel model;
    private TripPlanStreamableSseStore store;
    private TripPlanServiceImpl service;
    private List<Runnable> tasks;
    private TripPlanItem item;

    @BeforeEach void setup() throws Exception {
        poi = mock(AmapPoiUtils.class);
        model = mock(ChatModel.class);
        store = mock(TripPlanStreamableSseStore.class);
        tasks = new ArrayList<>();
        TaskExecutor executor = tasks::add;
        service = new TripPlanServiceImpl(poi, model, executor, store);
        item = TripPlanItem.builder().city("北京").lng(116.4).lat(39.9).title("故宫").build();
        when(poi.getScenicSpotsAround(anyDouble(), anyDouble())).thenReturn(List.of(Map.of("name", "故宫")));
        when(store.appendEvent(anyString(), anyString(), any())).thenReturn("1-0");
        CloseableHttpClient http = mock(CloseableHttpClient.class);
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(http.execute(any(HttpUriRequest.class))).thenReturn(response);
        when(response.getEntity()).thenReturn(new StringEntity("{\"status\":\"0\"}"));
        ((CloseableHttpClient) ReflectionTestUtils.getField(service, "httpClient")).close();
        ReflectionTestUtils.setField(service, "httpClient", http);
        ReflectionTestUtils.setField(service, "amapKey", "test");
    }

    @AfterEach void teardown() throws Exception { service.shutdown(); }

    @Test void rejectsInvalidInputBeforeQueuing() {
        item.setCity("");
        assertThrows(ResponseStatusException.class, () -> service.streamPlan(item, 3, null, null, null));
        assertTrue(tasks.isEmpty());
        verifyNoInteractions(store, model, poi);
    }

    @Test void repeatedTaskIdQueuesOnlyOneGenerator() {
        service.streamPlan(item, 3, null, "same", null);
        service.streamPlan(item, 3, null, "same", null);
        assertEquals(1, tasks.size());
    }

    @Test void normalCompletionWritesDoneOnceAndReleasesReservation() {
        when(model.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.empty());
        service.streamPlan(item, 3, null, "normal", null);
        tasks.get(0).run();
        verify(store, times(1)).appendEvent("normal", "trip-done", "[DONE]");
        verify(store, times(1)).markFinished("normal");
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(service, "runningTasks")).isEmpty());
        verify(store, never()).appendEvent(eq("normal"), eq("trip-error"), any());
    }

    @Test void stalledAiTimesOutCancelsAndReleasesWorker() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(model.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
        ReflectionTestUtils.setField(service, "maxDurationSeconds", 1L);
        service.streamPlan(item, 3, null, "timeout", null);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(4), () -> tasks.get(0).run());
        assertTrue(cancelled.get());
        verify(store).appendEvent(eq("timeout"), eq("trip-error"), any());
        verify(store, never()).appendEvent(eq("timeout"), eq("trip-done"), any());
        verify(store).markFinished("timeout");
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(service, "runningTasks")).isEmpty());
    }

    @Test void upstreamErrorNeverWritesSuccess() {
        when(model.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.error(new IllegalStateException("upstream")));
        service.streamPlan(item, 3, null, "error", null);
        tasks.get(0).run();
        verify(store).appendEvent(eq("error"), eq("trip-error"), any());
        verify(store, never()).appendEvent(eq("error"), eq("trip-done"), any());
        verify(store).markFinished("error");
    }

    @Test void queueRejectionReleasesReservation() throws Exception {
        service.shutdown();
        service = new TripPlanServiceImpl(poi, model, command -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        }, store);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.streamPlan(item, 3, null, "rejected", null));
        assertEquals(503, ex.getStatusCode().value());
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(service, "runningTasks")).isEmpty());
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(service, "connectionStops")).isEmpty());
    }

    @Test void expiredQueuedTaskDoesNotStartExternalCalls() {
        ReflectionTestUtils.setField(service, "maxDurationSeconds", 0L);
        service.streamPlan(item, 3, null, "expired", null);
        tasks.get(0).run();
        verifyNoInteractions(poi, model);
        verify(store).appendEvent(eq("expired"), eq("trip-error"), any());
        verify(store).markFinished("expired");
    }

    @Test void finalDataIsPersistedBeforeDone() {
        org.springframework.ai.chat.model.Generation generation = mock(
                org.springframework.ai.chat.model.Generation.class, RETURNS_DEEP_STUBS);
        when(generation.getOutput().getText()).thenReturn(
                "{\"day\":1,\"color\":\"#FF5733\",\"spot\":{\"name\":\"故宫\",\"lng\":116.4,\"lat\":39.9,\"address\":\"北京\"}}");
        when(model.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(generation))));
        service.streamPlan(item, 3, null, "ordered", null);
        tasks.get(0).run();
        org.mockito.InOrder order = inOrder(store);
        order.verify(store, times(2)).appendEvent(eq("ordered"), eq("trip-data"), any());
        order.verify(store).appendEvent("ordered", "trip-done", "[DONE]");
        order.verify(store).markFinished("ordered");
    }

    @Test void aiErrorPayloadNeverWritesSuccess() {
        org.springframework.ai.chat.model.Generation generation = mock(
                org.springframework.ai.chat.model.Generation.class, RETURNS_DEEP_STUBS);
        when(generation.getOutput().getText()).thenReturn("{\"error\":\"invalid request\"}");
        when(model.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(generation))));
        service.streamPlan(item, 3, null, "payload-error", null);
        tasks.get(0).run();
        verify(store).appendEvent(eq("payload-error"), eq("trip-error"), any());
        verify(store, never()).appendEvent(eq("payload-error"), eq("trip-done"), any());
    }

    @Test void disconnectStopsReaderButKeepsResumableGeneration() throws Exception {
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation -> {
            AtomicBoolean stop = invocation.getArgument(3);
            reading.countDown();
            while (!stop.get()) Thread.sleep(5);
            stopped.countDown();
            return null;
        }).when(store).streamRealtimeUntilDone(anyString(), nullable(String.class), any(), any());
        SseEmitter connection = service.streamPlan(item, 3, null, "resume", null);
        assertTrue(reading.await(2, java.util.concurrent.TimeUnit.SECONDS));
        ReflectionTestUtils.invokeMethod(service, "closeConnection", connection);
        assertTrue(stopped.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, tasks.size());
        when(model.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.empty());
        tasks.get(0).run();
        verify(store).appendEvent("resume", "trip-done", "[DONE]");
    }
}
