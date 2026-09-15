package com.osundosun.mupid.api.ai.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.ai.enumerate.AiMessageType;
import com.osundosun.mupid.api.music.entity.Music;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ai_chat_messages")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiChatMessage {

    /**
     * AI 채팅 메시지 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer aiChatMessageId;

    /**
     * AI 채팅 세션 번호
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AiChatSession session;

    /**
     * 채팅 순서
     */
    @Column(name = "`index`", nullable = false)
    private Integer index;

    /**
     * 메시지 유형
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiMessageType messageType;

    /**
     * 관리 번호
     */
    @Column
    private String messageId;

    /**
     * 관리 이름
     */
    @Column
    private String messageName;

    /**
     * 메시지 본문
     */
    @Column
    private String text;

    /**
     * 메시지 본문(멀티모달)
     */
    @Column
    private String content;

    /**
     * 메시지 본문(블록형식)
     */
    @Column
    private String contentBlocks;

    /**
     * 추가 파라미터
     */
    @Column
    private String additionalKwargs;

    /**
     * LLM 메타데이터
     */
    @Column
    private String responseMetadata;

    /**
     * 지금 듣고 있는 노래
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "music_id")
    private Music music;

    /**
     * 생성 일시
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
}
