package com.igmo.service;

import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomCodeGenerationFailedException;
import com.igmo.service.exception.RoomNotFoundException;
import com.igmo.service.exception.UnauthorizedPlayerException;
import com.igmo.store.GameRegistry;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.LobbySnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GameService {

    private static final String LOBBY_TOPIC_PREFIX = "/topic/room/";
    private static final int MAX_ROOM_CODE_ATTEMPTS = 10;

    private final GameRegistry gameRegistry;
    private final RoomCodeGenerator roomCodeGenerator;
    private final SimpMessagingTemplate messagingTemplate;

    public CreateGameResponse createGame(String nickname) {
        Player host = new Player(nickname);
        GameRoom room = createRoomWithUniqueCode(host);
        return new CreateGameResponse(room.getCode(), host.getId(), host.getSecret(), LobbySnapshot.from(room));
    }

    public JoinGameResponse joinGame(String code, String nickname) {
        GameRoom room = gameRegistry.find(code)
                .orElseThrow(RoomNotFoundException::new);
        synchronized (room) {
            // 이 사이에 삭제된 경우
            if (gameRegistry.find(code).orElse(null) != room) {
                throw new RoomNotFoundException();
            }
            Player player = new Player(nickname);
            room.addPlayer(player);
            LobbySnapshot snapshot = LobbySnapshot.from(room);
            messagingTemplate.convertAndSend(LOBBY_TOPIC_PREFIX + code, snapshot);
            return new JoinGameResponse(player.getId(), player.getSecret(), snapshot);
        }
    }

    public void leaveGame(String code, String playerId, String secret) {
        GameRoom room = gameRegistry.find(code)
                .orElseThrow(RoomNotFoundException::new);
        synchronized (room) {
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            if (!room.isSecretValid(playerId, secret)) {
                throw new UnauthorizedPlayerException();
            }
            removePlayerAndBroadcast(room, playerId);
        }
    }

    public void handleDisconnect(String code, String playerId) {
        gameRegistry.find(code)
                .ifPresent(room -> removePlayerAndBroadcast(room, playerId));
    }

    private void removePlayerAndBroadcast(GameRoom room, String playerId) {
        synchronized (room) {
            if (!room.removePlayer(playerId)) {
                return;
            }
            if (room.isEmpty()) {
                gameRegistry.remove(room.getCode());
                return;
            }
            messagingTemplate.convertAndSend(LOBBY_TOPIC_PREFIX + room.getCode(), LobbySnapshot.from(room));
        }
    }

    private GameRoom createRoomWithUniqueCode(Player host) {
        for (int attempt = 0; attempt < MAX_ROOM_CODE_ATTEMPTS; attempt++) {
            GameRoom room = GameRoom.create(roomCodeGenerator.generate(), host);
            if (gameRegistry.saveIfAbsent(room)) {
                return room;
            }
        }
        throw new RoomCodeGenerationFailedException();
    }
}
