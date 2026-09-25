package com.osundosun.mupid.api.vector.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.vector.error.VectorErrorCode;
import com.osundosun.mupid.api.vector.properties.QdrantProperties;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 벡터 쓰기 전용 스레드 풀 (S15P21A204-457, 설계 §10.3-7).
 *
 * <p>모델 재계산은 1,000구간 요청을 수백 번 보낸다(설계 기준 약 900번). 요청 스레드가 그대로 Qdrant
 * 를 기다리면 배치가 동시에 보내는 만큼 Tomcat 스레드가 묶여 <b>사용자 요청이 밀린다.</b> 그래서 Qdrant
 * 쓰기는 이 풀에서만 돈다.
 *
 * <ul>
 *   <li>동시에 도는 쓰기는 {@code qdrant.write-concurrency}(기본 2)개 — Qdrant·node-b CPU 도 이 이상
 *       밀리지 않는다.</li>
 *   <li>그 뒤로 {@code qdrant.write-queue}(기본 8)개가 기다린다. <b>줄이 차면 바로 429</b> 다 — 요청
 *       스레드를 오래 잡지 않는다. 배치는 잠시 뒤 같은 요청을 다시 보낸다.</li>
 *   <li>줄에 선 요청은 {@code qdrant.write-wait}(기본 60초) 안에 끝나야 한다. 넘으면 503 — 쓰기 자체는
 *       풀에서 끝까지 가므로(멱등이라) 다시 보내도 결과가 같다.</li>
 * </ul>
 *
 * <p>그래서 벡터 API 가 동시에 잡을 수 있는 요청 스레드는 많아야 {@code concurrency + queue} 개다.
 */
@Slf4j
@Component
public class VectorWriteExecutor {

    private final ThreadPoolExecutor pool;
    private final long waitMillis;

    public VectorWriteExecutor(QdrantProperties properties) {
        int threads = Math.max(1, properties.writeConcurrency());
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(1, properties.writeQueue())),
            runnable -> {
                Thread thread = new Thread(runnable, "vector-write-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
        this.waitMillis = properties.writeWait().toMillis();
    }

    /**
     * 풀에서 돌리고 끝날 때까지 기다린다. 풀 안에서 던진 {@code BusinessException} 은 그대로 올린다.
     */
    public <T> T call(Supplier<T> work) {
        Future<T> future;
        try {
            future = pool.submit(work::get);
        } catch (RejectedExecutionException e) {
            log.warn("벡터 쓰기 줄이 찼다 — 429. 동시 {} · 대기 {}", pool.getActiveCount(), pool.getQueue().size());
            throw new BusinessException(VectorErrorCode.VECTOR_BUSY);
        }
        try {
            return future.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("벡터 쓰기가 {}ms 안에 끝나지 않았다 — 503. 쓰기는 계속 돈다(멱등)", waitMillis);
            throw new BusinessException(VectorErrorCode.VECTOR_STORE_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(VectorErrorCode.VECTOR_STORE_UNAVAILABLE);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    public void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

}
