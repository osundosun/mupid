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
import com.osundosun.mupid.api.onboarding.response.OnboardingResultResponse;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.error.UserErrorCode;
import com.osundosun.mupid.api.user.service.UserService;

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
 *
 * ## 내용을 읽는 것은 추천 서버뿐이다
 *
 * 본인이 제출 내용을 되돌려 받는 길은 없다. 화면이 진입 분기에 쓰는 것은
 * `hasSubmitted` 가 주는 **불리언 하나**고, 조합을 풀어 집합으로 되돌리는
 * `getResult` 는 배치(`ROLE_SYSTEM`)만 부른다.
 *
 * ## 한 번만 받는다
 *
 * `submit` 은 이미 제출한 사람을 409 로 막는다. 조합으로 깔아 두는 저장
 * 형태라 부분 수정이 성립하지 않고, 콜드스타트 입력이라 한 시점의 답으로
 * 굳는 편이 맞다. 닉네임은 회원가입이 받으므로 여기서 건드리지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class OnboardingServiceImpl implements OnboardingService {

    /** 조합 폭발을 막는 상한. 화면 선택지가 각각 8개라 넉넉하다. */
    private static final int MAX_SELECTION = 16;

    private final OnboardingSurveyRepository repository;
    private final CodeRepository codeRepository;
    private final UserService userService;

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
    public boolean hasSubmitted(int userId) {
        return repository.existsByUser_UserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public OnboardingResultResponse getResult(int userId) {
        /*
         * 응답이 없는 까닭은 둘이다 — 아직 안 했거나, 그런 사용자가 없거나.
         * 부르는 쪽(배치)에게 앞은 건너뛸 일이고 뒤는 데이터가 어긋난 일이라
         * 섞으면 안 된다. 그래서 설문을 읽기 전에 사용자부터 확인한다.
         * 탈퇴 사용자를 없는 것으로 보는 규칙은 `UserService.getUser` 가 갖고 있다.
         */
        userService.getUser(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        List<OnboardingSurvey> answers = repository.findAllByUser(userId);
        return new OnboardingResultResponse(
            userId,
            !answers.isEmpty(),
            submittedAt(answers),
            distinct(answers, OnboardingSurvey::getMoodCode),
            distinct(answers, OnboardingSurvey::getGenreCode)
        );
    }

    @Override
    public void submit(User user, OnboardingSubmitRequest request) {
        /*
         * 한 번만 받는다. 조합으로 깔아 두는 저장 형태라 부분 수정이라는 것이
         * 애초에 없고, 고쳐 쓰는 값도 아니다 — 이력이 없는 사람의 첫 추천을
         * 만드는 콜드스타트 입력이라 한 시점의 답으로 굳는 편이 맞다.
         *
         * 코드 검증보다 먼저 본다. 어차피 거절할 요청에 조회를 더할 이유가 없다.
         */
        if (repository.existsByUser_UserId(user.getUserId())) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_ALREADY_SUBMITTED);
        }

        List<Code> moods = resolve(request.moodCodeIds(), CodeType.MOOD);
        List<Code> genres = resolve(request.genreCodeIds(), CodeType.GENRE);

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

        /*
         * 가입 직후 상태는 `PENDING` 이고, 그 상태를 벗는 길은 이 제출 하나뿐이다.
         * 제출과 상태 전환은 같은 트랜잭션이라 한쪽만 남는 일이 없다.
         *
         * 인증 주체로 받은 `user` 를 직접 고치지 않고 `UserService` 를 거치는 것은,
         * 이 엔티티가 보안 필터가 읽어 둔 것이라 지금 영속성 컨텍스트가 관리한다는
         * 보장이 없기 때문이다. 관리되지 않으면 `setStatus` 는 그냥 사라진다.
         */
        userService.activateUser(user.getUserId());
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

    /**
     * 제출 시각. 조합이라 한 번 제출해도 여러 줄이 생기므로 **가장 늦은 것**을 쓴다.
     * 응답이 없으면 `null` 이다.
     */
    private LocalDateTime submittedAt(List<OnboardingSurvey> answers) {
        return answers.stream()
            .map(OnboardingSurvey::getCreatedAt)
            .filter(value -> value != null)
            .max(LocalDateTime::compareTo)
            .orElse(null);
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
