package com.osundosun.mupid.api.event.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix="event")
public record EventProperties(

    String kafkaBootstrapServers,

    @DefaultValue("mupid.listen.events") String kafkaTopic,

    /**
     * 한 요청에 담을 수 있는 이벤트 수. 프론트 SDK 는 20건씩 보낸다.
     */
    @DefaultValue("50") int maxBatchSize,

    /**
     * event_id 를 기억해 두는 기간. 네트워크 재시도 간격보다 넉넉하면 된다.
     */
    @DefaultValue("10m") Duration dedupTtl,

    /**
     * 클라이언트가 보낸 occurred_at 을 얼마나 과거까지 받아줄 것인가.
     * 오프라인 후 일괄 전송을 위해 열어두지만, 무한정 열면 조작된 시각이 들어온다.
     */
    @DefaultValue("72h") Duration occurredAtMaxPast,

    /**
     * 미래 방향 허용치. 클라이언트 시계가 조금 빠른 것은 흔하다.
     */
    @DefaultValue("5m") Duration occurredAtMaxFuture,

    /**
     * 브로커 메타데이터를 기다리는 최대 시간.
     *
     * 카프카가 죽어 있으면 KafkaProducer.send 는 이 시간만큼 **호출 스레드를 막는다.**
     * 기본값 60초를 그대로 두면 브로커 장애가 곧 API 응답 지연이 되므로 짧게 잡는다.
     */
    @DefaultValue("3s") Duration kafkaMaxBlock

) {

}
