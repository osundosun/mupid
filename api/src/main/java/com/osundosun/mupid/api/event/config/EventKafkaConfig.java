package com.osundosun.mupid.api.event.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import com.osundosun.mupid.api.event.properties.EventProperties;

import lombok.RequiredArgsConstructor;

/**
 * 청취 이벤트 프로듀서.
 *
 * `spring.kafka.*` 자동설정을 쓰지 않고 빈을 직접 만든다. 이유는 두 가지다.
 *
 *   1. 이 저장소는 모든 환경변수에 `MUPID_` 접두사를 강제한다 (decision/0002).
 *      자동설정을 쓰면 설정 키가 `spring.kafka.bootstrap-servers` 로 흩어진다.
 *   2. 컨슈머는 ClickHouse 의 Kafka 엔진이 맡는다. 애플리케이션에 필요한 것은
 *      프로듀서 하나뿐이라 스타터 전체를 붙일 이유가 없다.
 *
 * 값은 문자열 그대로 보낸다. 직렬화는 `EventServiceImpl` 이 그 클래스가 직접 들고 있는
 * 매퍼로 한다 — 필드명이 ClickHouse `kafka_listen_events` 의 컬럼명과의 계약이라
 * 애플리케이션 설정에 묶어두지 않는다. 그쪽 주석에 이유가 적혀 있다.
 */
@Configuration
@RequiredArgsConstructor
public class EventKafkaConfig {

    private final EventProperties props;

    @Bean
    public ProducerFactory<String, String> eventProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafkaBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // 브로커가 죽어 있을 때 요청 스레드가 오래 잡히지 않도록 짧게 잡는다.
        // 두 값 모두 Kafka ConfigDef 상 LONG 이다. int 로 넣으면 환경에 따라
        // ConfigException 이 날 수 있어 long 으로 못 박는다.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, props.kafkaMaxBlock().toMillis());
        // 하트비트가 초당 여러 건 몰리므로 조금 모아 보낸다.
        config.put(ProducerConfig.LINGER_MS_CONFIG, 20L);
        // linger 안에 모인 것을 한 배치에 더 많이 담는다. 기본 16KB 는 이 트래픽에서
        // 금방 차서 linger 를 채우기 전에 끊어 보내게 된다.
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);
        // 청취 이벤트는 필드 이름이 값보다 긴 JSON 이고, 대부분이 같은 모양의
        // 하트비트라 압축이 잘 먹는다. lz4 는 CPU 를 거의 쓰지 않으면서 네트워크와
        // ClickHouse 쪽 디스크 I/O 를 함께 줄인다.
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        // 프로듀서 내부 재시도가 같은 메시지를 두 번 넣지 않게 한다. Redis 멱등 처리는
        // "같은 event_id 로 두 번 들어온 요청" 만 막을 뿐, 이미 통과한 한 건이 전송
        // 재시도로 복제되는 것은 막지 못한다. MV 는 중복을 걸러주지 않으므로 여기서 막는다.
        //
        // 멱등 프로듀서는 acks=all 을 요구한다 — 명시하지 않으면 클라이언트가 기동 시
        // ConfigException 을 던진다. 복제본이 1개라 acks=1 과 비용 차이는 없다.
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> eventKafkaTemplate() {
        return new KafkaTemplate<>(eventProducerFactory());
    }

}
