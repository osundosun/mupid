package com.osundosun.mupid.api.onboarding.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;
import com.osundosun.mupid.api.code.repository.CodeRepository;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.onboarding.dto.CodeOption;
import com.osundosun.mupid.api.onboarding.entity.OnboardingSurvey;
import com.osundosun.mupid.api.onboarding.error.OnboardingErrorCode;
import com.osundosun.mupid.api.onboarding.repository.OnboardingSurveyRepository;
import com.osundosun.mupid.api.onboarding.request.OnboardingSubmitRequest;
import com.osundosun.mupid.api.onboarding.response.OnboardingOptionResponse;
import com.osundosun.mupid.api.onboarding.response.OnboardingStatusResponse;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 온보딩 설문.
 *
 * ## 분위기·장르를 조합으로 저장하는 이유
 *
 * `onboarding_survey` 한 줄은 `(user_id, mood_code_id, genre_code_id)` 이고
 * 두 코드가 모두 NOT NULL 이다. 그런데 화면은 분위기와 장르를 **각각 여러 개**
 * 고르게 할 뿐, 둘을 짝지어 묻지 않는다. 그래서 고른 분위기와 장르의 **모든
 * 조합**을 넣는다. 읽을 때 각 칼럼을 `DISTINCT` 하면 고른 집합이 그대로
 * 복원되고, 짝이 골고루 퍼져 있어서 "이 분위기와 이 장르를 짝지어 골랐다"는
 * 오해도 없다.
 *
 * 한 사람이 최대 8×8 = 64줄이다. 더 나은 모양은 두 칼럼을 NULL 허용으로 바꿔
 * 한 줄에 답 하나를 담는 것인데, ERD 변경이라 팀에 먼저 물어야 한다
 * (`knowledge/open-questions.md`).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class OnboardingServiceImpl implements OnboardingService {

    /** 조합 폭발을 막는 상한. 화면 선택지가 각각 8개라 넉넉하다. */
    private static final int MAX_SELECTION = 16;

    private final OnboardingSurveyRepository repository;
    private final CodeRepository codeRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public OnboardingOptionResponse getOptions() {
        List<CodeOption> moods = options(CodeType.MOOD);
        List<CodeOption> genres = options(CodeType.GENRE);
        if (moods.isEmpty() || genres.isEmpty()) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_OPTION_EMPTY);
        }
        return new OnboardingOptionResponse(moods, genres);
    }

    @Override
    @Transactional(readOnly = true)
    public OnboardingStatusResponse getStatus(User user) {
        List<OnboardingSurvey> answers = repository.findAllByUser(user.getUserId());
        if (answers.isEmpty()) {
            return new OnboardingStatusResponse(false, null, user.getNickname(), List.of(), List.of());
        }
        LocalDateTime submittedAt = answers.stream()
            .map(OnboardingSurvey::getCreatedAt)
            .filter(value -> value != null)
            .max(LocalDateTime::compareTo)
            .orElse(null);
        return new OnboardingStatusResponse(
            true,
            submittedAt,
            user.getNickname(),
            distinct(answers, OnboardingSurvey::getMoodCode),
            distinct(answers, OnboardingSurvey::getGenreCode)
        );
    }

    @Override
    public void submit(User user, OnboardingSubmitRequest request) {
        List<Code> moods = resolve(request.moodCodeIds(), CodeType.MOOD);
        List<Code> genres = resolve(request.genreCodeIds(), CodeType.GENRE);

        // 다시 제출하면 이전 응답을 남겨 두지 않는다. 지운 뒤 넣어야 하므로 먼저 비운다.
        repository.deleteByUser_UserId(user.getUserId());
        repository.flush();

        List<OnboardingSurvey> rows = new ArrayList<>(moods.size() * genres.size());
        for (Code mood : moods) {
            for (Code genre : genres) {
                rows.add(OnboardingSurvey.builder()
                    .user(user)
                    .moodCode(mood)
                    .genreCode(genre)
                    .build());
            }
        }
        repository.saveAll(rows);

        // 온보딩에서 받은 이름을 계정 닉네임으로 삼는다. 화면이 이 이름으로 인사한다.
        User managed = userRepository.findById(user.getUserId()).orElseThrow();
        managed.setNickname(request.nickname().trim());
    }

    private List<CodeOption> options(CodeType type) {
        return codeRepository.findAllByCodeTypeOrderByCodeIdAsc(type).stream()
            .map(CodeOption::of)
            .toList();
    }

    /**
     * 보내 온 번호를 코드로 바꾼다. 중복은 버리고, 없는 번호나 용도가 다른 번호는 400 이다.
     */
    private List<Code> resolve(List<Integer> codeIds, CodeType type) {
        List<Integer> wanted = codeIds.stream()
            .filter(value -> value != null)
            .distinct()
            .toList();
        if (wanted.isEmpty() || wanted.size() > MAX_SELECTION) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_INVALID_CODE);
        }
        Map<Integer, Code> found = codeRepository.findAllByCodeIdIn(wanted).stream()
            .filter(code -> code.getCodeType() == type)
            .collect(Collectors.toMap(Code::getCodeId, Function.identity()));
        if (found.size() != wanted.size()) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_INVALID_CODE);
        }
        return wanted.stream().map(found::get).toList();
    }

    /** 조합으로 깔려 있는 응답에서 한쪽 축만 골라 낸다. */
    private List<CodeOption> distinct(
        List<OnboardingSurvey> answers,
        Function<OnboardingSurvey, Code> axis
    ) {
        Map<Integer, CodeOption> unique = new LinkedHashMap<>();
        for (OnboardingSurvey answer : answers) {
            Code code = axis.apply(answer);
            unique.putIfAbsent(code.getCodeId(), CodeOption.of(code));
        }
        return List.copyOf(unique.values());
    }

}
