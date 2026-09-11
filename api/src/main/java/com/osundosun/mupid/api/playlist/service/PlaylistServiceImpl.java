package com.osundosun.mupid.api.playlist.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.repository.PlaylistRepository;
import com.osundosun.mupid.api.playlist.request.PlaylistCreateRequest;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.error.UserErrorCode;
import com.osundosun.mupid.api.user.service.UserService;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class PlaylistServiceImpl implements PlaylistService {

    private final PlaylistRepository repository;
    private final UserService userService;

    @Override
    public Playlist createPlaylist(int userId, PlaylistCreateRequest dto) {
        User user = userService.getUser(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Playlist newPlaylist = Playlist.builder()
            .user(user)
            .title(dto.title())
            .build(); /* 수록곡 수는 0, 삭제 일시는 null */

        return repository.save(newPlaylist);
    }

}
