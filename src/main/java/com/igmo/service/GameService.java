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

    private static final String LOBBY_TOPIC_PREFIX = "/topic/rooms/";
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
            if (isDetached(code, room)) {
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
            if (isDetached(code, room)) {
                throw new RoomNotFoundException();
            }
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            if (!room.isSecretValid(playerId, secret)) {
                throw new UnauthorizedPlayerException();
            }
            removePlayerAndBroadcast(room, playerId);
        }
    }

    public void changeReady(String code, String playerId, boolean ready) {
        GameRoom room = gameRegistry.find(code)
                .orElseThrow(RoomNotFoundException::new);
        synchronized (room) {
            if (isDetached(code, room)) {
                throw new RoomNotFoundException();
            }
            if (!room.hasPlayer(playerId)) {
                throw new PlayerNotFoundException();
            }
            room.changePlayerReady(playerId, ready);
            messagingTemplate.convertAndSend(LOBBY_TOPIC_PREFIX + code, LobbySnapshot.from(room));
        }
    }

    public void startGame(String code, String playerId) {
        GameRoom room = gameRegistry.find(code)
                .orElseThrow(RoomNotFoundException::new);
        synchronized (room) {
            if (isDetached(code, room)) {
                throw new RoomNotFoundException();
            }
            room.start(playerId);
            messagingTemplate.convertAndSend(LOBBY_TOPIC_PREFIX + code, LobbySnapshot.from(room));
        }
    }

    public void handleDisconnect(String code, String playerId) {
        gameRegistry.find(code)
                .ifPresent(room -> removePlayerAndBroadcast(room, playerId));
    }

    private void removePlayerAndBroadcast(GameRoom room, String playerId) {
        synchronized (room) {
            // 방 획득과 락 진입 사이에 방이 삭제·교체되었으면 낡은 객체이므로 조용히 무시한다.
            if (isDetached(room.getCode(), room)) {
                return;
            }
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

    // 방 획득과 락 진입 사이에 방이 삭제되고 같은 코드로 새 방이 생성되면 낡은 room 객체가 된다.
    private boolean isDetached(String code, GameRoom room) {
        return gameRegistry.find(code).orElse(null) != room;
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
