package com.igmo.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisGameRoomStateRepositoryTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hashOperations = mock();
    private final RedisGameRoomStateRepository repository =
            new RedisGameRoomStateRepository(redisTemplate, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @DisplayName("GameRoom 상태를 Redis 해시에 저장한다.")
    void save_GameRoom상태를Redis해시에저장한다() {
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        String expectedPlayers = "[{\"id\":\"" + room.getPlayers().getFirst().getId()
                + "\",\"nickname\":\"호스트\",\"score\":0,\"ready\":false}]";

        repository.save(room);

        verify(hashOperations).putAll(eq("igmo:game-room:ABCD"), eq(Map.of(
                "roomCode", "ABCD",
                "phase", "LOBBY",
                "currentRound", "0",
                "players", expectedPlayers
        )));
    }

    @Test
    @DisplayName("Redis 해시를 GameRoom 상태로 조회한다.")
    void find_Redis해시를GameRoom상태로조회한다() {
        when(hashOperations.entries("igmo:game-room:ABCD")).thenReturn(Map.of(
                "roomCode", "ABCD",
                "phase", "PLAYING",
                "currentRound", "2",
                "players", "[{\"id\":\"player-1\",\"nickname\":\"호스트\",\"score\":3,\"ready\":true}]"
        ));

        Optional<RedisGameRoomState> state = repository.find("ABCD");

        assertThat(state).contains(new RedisGameRoomState(
                "ABCD",
                GamePhase.PLAYING,
                2,
                List.of(new RedisGameRoomState.PlayerState("player-1", "호스트", 3, true))
        ));
    }

    @Test
    @DisplayName("방 삭제 시 Redis 키를 삭제한다.")
    void delete_방삭제시Redis키를삭제한다() {
        repository.delete("ABCD");

        verify(redisTemplate).delete("igmo:game-room:ABCD");
    }

    @Test
    @DisplayName("GameRoomRepository의 상태 변경 후 Redis에 최신 상태를 저장한다.")
    void gameRoomRepository_상태변경후Redis에최신상태를저장한다() {
        RedisGameRoomStateRepository redisStateRepository = mock(RedisGameRoomStateRepository.class);
        GameRegistry gameRegistry = new GameRegistry();
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        gameRegistry.saveIfAbsent(room);
        GameRoomRepository gameRoomRepository = new GameRoomRepository(
                gameRegistry,
                Optional.of(redisStateRepository)
        );

        gameRoomRepository.update("ABCD", currentRoom -> {
            currentRoom.changePlayerReady(currentRoom.getPlayers().getFirst().getId(), true);
            return null;
        });

        verify(redisStateRepository, times(1)).save(room);
    }
}
