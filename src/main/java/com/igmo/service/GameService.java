package com.igmo.service;

import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomCodeGenerationFailedException;
import com.igmo.service.exception.RoomNotFoundException;
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
        return new CreateGameResponse(room.getCode(), host.getId(), LobbySnapshot.from(room));
    }

    public JoinGameResponse joinGame(String code, String nickname) {
        GameRoom room = gameRegistry.find(code)
                .orElseThrow(RoomNotFoundException::new);
        synchronized (room) {
            // 이 사이에 삭제된 경우
            if (gameRegistry.find(code).orElse(null) != room) {
                throw new RoomNotFoundException();
            }
            String playerId = room.addPlayer(new Player(nickname));
            LobbySnapshot snapshot = LobbySnapshot.from(room);
            messagingTemplate.convertAndSend(LOBBY_TOPIC_PREFIX + code, snapshot);
            return new JoinGameResponse(playerId, snapshot);
        }
    }

    public void leaveGame(String code, String playerId) {
        GameRoom room = gameRegistry.find(code)
                .orElseThrow(RoomNotFoundException::new);
        if (!removePlayerAndBroadcast(room, playerId)) {
            throw new PlayerNotFoundException();
        }
    }

    public void handleDisconnect(String code, String playerId) {
        gameRegistry.find(code)
                .ifPresent(room -> removePlayerAndBroadcast(room, playerId));
    }

    private boolean removePlayerAndBroadcast(GameRoom room, String playerId) {
        synchronized (room) {
            if (!room.removePlayer(playerId)) {
                return false;
            }
            if (room.isEmpty()) {
                gameRegistry.remove(room.getCode());
                return true;
            }
            messagingTemplate.convertAndSend(LOBBY_TOPIC_PREFIX + room.getCode(), LobbySnapshot.from(room));
            return true;
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
