package com.osundosun.mupid.api.user.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

public interface UserRepository extends JpaRepository<User, Integer> {

    Optional<User> findByUserIdAndStatusNot(int userId, UserStatus status);

    /**
     * <b>탈퇴한 회원도</b> 찾는다. 살아 있는지 아닌지는 부르는 쪽이 판단한다.
     *
     * <p>탈퇴는 행을 지우지 않고 {@code status} 만 옮기므로 행은 남는다.
     * "그런 회원이 없다"(404)와 "탈퇴했다"(410)를 나누려면 상태로 거르지 않는
     * 조회가 필요하다 — {@code findByUserIdAndStatusNot} 은 둘 다 {@code empty} 다.
     */
    Optional<User> findByUserId(int userId);

    Optional<User> findByEmailAndStatusNot(String email, UserStatus status);

    Optional<User> findByImageFileId(Integer imageFileId);

    boolean existsByRole(UserRole role);

    /**
     * 그 상태인 회원의 번호만. **엔티티를 통째로 올리지 않는다** — 배치가 전체 회원을
     * 도는데 프로필·이미지까지 영속성 컨텍스트에 쌓을 이유가 없다.
     */
    @Query("select u.userId from User u where u.status = :status order by u.userId")
    List<Integer> findAllUserIdsByStatus(@Param("status") UserStatus status);
    
}
