package com.osundosun.mupid.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * `@EnableScheduling` 은 취향 리포트의 자정 배치(`ReportDailyBatch`)가 들여왔다.
 * 스케줄러는 인스턴스마다 돈다 — 새 `@Scheduled` 를 붙일 때는 여러 벌 띄웠을 때
 * 무슨 일이 생기는지를 먼저 보고, 필요하면 Redis 선점을 건다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
@EnableScheduling
public class ApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(ApiApplication.class, args);
	}

}
