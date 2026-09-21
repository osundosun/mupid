package com.osundosun.mupid.api.common.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 비동기 실행기 (S15P21A204-384 문의 알림).
 *
 * <h2>왜 필요했나</h2>
 *
 * 문의 한 건은 메일 두 통(관리자·문의자)과 Mattermost 알림 한 건을 낸다. 전부 요청
 * 스레드에서 돌리면 <b>응답 시간이 Gmail 왕복 두 번</b>이 된다 — 문의 접수는 메일이
 * 나갔는지와 무관하게 즉시 끝나야 한다.
 *
 * <h2>왜 전용 실행기인가</h2>
 *
 * 부트의 기본 실행기(`applicationTaskExecutor`)를 함께 쓰지 않는다. 알림은 외부 I/O 로
 * 수 초를 묶일 수 있어, 나중에 다른 비동기 작업이 생겼을 때 서로 자리를 뺏는다.
 * 이름을 붙여 이 용도에만 쓴다({@link #INQUIRY_NOTIFIER_EXECUTOR}).
 *
 * <h2>큐가 차면 어떻게 되는가</h2>
 *
 * {@link ThreadPoolExecutor.AbortPolicy}(기본값)라 넘기지 못하면
 * {@code TaskRejectedException} 이 <b>호출한(요청) 스레드로</b> 올라온다. 그래서 넘기는
 * 쪽(`InquiryServiceImpl`)이 그 예외를 잡아 로그만 남긴다 — 알림을 못 넘겼다고 문의
 * 접수가 실패하면 계약 위반이다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 문의 알림 전용 실행기 이름. `@Async` 에 이 상수를 그대로 쓴다. */
    public static final String INQUIRY_NOTIFIER_EXECUTOR = "inquiryNotifierExecutor";

    /**
     * 스레드 상한을 작게 잡는다. 알림은 짧은 HTTP 한 번이라 동시에 몇 건이면 충분하고,
     * 문의 폭주 때 Gmail·Mattermost 로 나가는 동시 연결을 스스로 제한하는 효과도 있다.
     *
     * <p>큐를 두어 순간적인 몰림은 받되, 그마저 넘으면 위 주석대로 거절된다.
     */
    @Bean(name = INQUIRY_NOTIFIER_EXECUTOR)
    public ThreadPoolTaskExecutor inquiryNotifierExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("inquiry-notify-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

}
