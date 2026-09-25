package com.igmo.service.lobby;

import com.igmo.domain.GameRoom;
import com.igmo.domain.GameStartPolicy;
import com.igmo.domain.Player;
import com.igmo.service.GameEventPublisher;
import com.igmo.service.GamePhaseScheduler;
import com.igmo.service.GameRoomRestoredEvent;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomCodeGenerationFailedException;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.LobbySnapshot;
import com.igmo.web.http.response.CreateGameResponse;
import com.igmo.web.http.response.JoinGameResponse;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GameLobbyService {

    private static final int MAX_ROOM_CODE_ATTEMPTS = 10;

    private final GameRoomRepository gameRoomRepository;
    private final RoomCodeGenerator roomCodeGenerator;
    private final GameEventPublisher eventPublisher;
    private final GameStartPolicy gameStartPolicy;
    private final GamePhaseScheduler gamePhaseScheduler;

    @Value("${igmo.game.lobby-duration}")
    private Duration lobbyDuration;

    public CreateGameResponse createGame(String nickname) {
        Player host = new Player(nickname);
        GameRoom room = createRoomWithUniqueCode(host);
        return new CreateGameResponse(room.getCode(), host.getId(), host.getSecret(), LobbySnapshot.from(room));
    }

    public JoinGameResponse joinGame(String code, String nickname) {
        return gameRoomRepository.update(code, room -> {
            Player player = new Player(nickname);
            room.addPlayer(player);
            LobbySnapshot snapshot = LobbySnapshot.from(room);
            eventPublisher.publishLobby(code, snapshot);
            return new JoinGameResponse(player.getId(), player.getSecret(), snapshot);
        });
    }

    public void changeReady(String code, String playerId, boolean ready) {
        gameRoomRepository.update(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            room.changePlayerReady(playerId, ready);
            eventPublisher.publishLobby(code, LobbySnapshot.from(room));
            return null;
        });
    }

    @EventListener
    public void restoreLobbyExpiration(GameRoomRestoredEvent event) {
        GameRoom room = event.room();
        if (!room.isInLobby()) {
            return;
        }
        gamePhaseScheduler.scheduleLobbyExpiration(
                room.getCode(),
                room.getLobbyDeadline(),
                () -> expireLobbyIfDue(room)
        );
    }

    private GameRoom createRoomWithUniqueCode(Player host) {
        for (int attempt = 0; attempt < MAX_ROOM_CODE_ATTEMPTS; attempt++) {
            GameRoom room = GameRoom.create(roomCodeGenerator.generate(), host, gameStartPolicy, lobbyDuration);
            if (gameRoomRepository.saveIfAbsent(room)) {
                gamePhaseScheduler.scheduleLobbyExpiration(
                        room.getCode(),
                        room.getLobbyDeadline(),
                        () -> expireLobbyIfDue(room)
                );
                return room;
            }
        }
        throw new RoomCodeGenerationFailedException();
    }

    private void expireLobbyIfDue(GameRoom room) {
        if (gameRoomRepository.removeLobbyIfExpired(room, Instant.now())) {
            eventPublisher.publishLobbyExpired(room.getCode());
        }
    }
}
