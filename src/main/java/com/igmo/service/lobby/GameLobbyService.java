package com.igmo.service.lobby;

import com.igmo.domain.GameRoom;
import com.igmo.domain.GameStartPolicy;
import com.igmo.domain.Player;
import com.igmo.service.GameEventPublisher;
import com.igmo.service.GamePhaseScheduler;
import com.igmo.service.GameRoomRestoredEvent;
import com.igmo.service.LobbyExpiredEvent;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomCodeGenerationFailedException;
import com.igmo.store.GameRoomDelivery;
import com.igmo.store.GameRoomRepository;
import com.igmo.store.RoomUpdate;
import com.igmo.web.LobbySnapshot;
import com.igmo.web.http.response.CreateGameResponse;
import com.igmo.web.http.response.JoinGameResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
        Player player = new Player(nickname);
        JoinGameResponse response = gameRoomRepository.updateWithCas(code, room -> {
            room.addPlayer(player);
            LobbySnapshot snapshot = LobbySnapshot.from(room);
            return RoomUpdate.changed(
                    new JoinGameResponse(player.getId(), player.getSecret(), snapshot),
                    List.of(GameRoomDelivery.roomSnapshot())
            );
        }).value();
        eventPublisher.publishLobby(code, response.snapshot());
        return response;
    }

    public void changeReady(String code, String playerId, boolean ready) {
        LobbySnapshot snapshot = gameRoomRepository.updateWithCas(code, room -> {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            room.changePlayerReady(playerId, ready);
            return RoomUpdate.changed(
                    LobbySnapshot.from(room),
                    List.of(GameRoomDelivery.roomSnapshot())
            );
        }).value();
        eventPublisher.publishLobby(code, snapshot);
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
                () -> gameRoomRepository.removeLobbyIfExpired(room, Instant.now())
        );
    }

    private GameRoom createRoomWithUniqueCode(Player host) {
        for (int attempt = 0; attempt < MAX_ROOM_CODE_ATTEMPTS; attempt++) {
            GameRoom room = GameRoom.create(roomCodeGenerator.generate(), host, gameStartPolicy, lobbyDuration);
            if (gameRoomRepository.saveIfAbsent(room)) {
                gamePhaseScheduler.scheduleLobbyExpiration(
                        room.getCode(),
                        room.getLobbyDeadline(),
                        () -> gameRoomRepository.removeLobbyIfExpired(room, Instant.now())
                );
                return room;
            }
        }
        throw new RoomCodeGenerationFailedException();
    }

    @EventListener
    public void publishLobbyExpired(LobbyExpiredEvent event) {
        eventPublisher.publishLobbyExpired(event.roomCode());
    }
}
