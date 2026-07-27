package com.igmo.service;

import com.igmo.domain.GameRoom;
import com.igmo.domain.GameStartPolicy;
import com.igmo.domain.Player;
import com.igmo.service.exception.PlayerNotFoundException;
import com.igmo.service.exception.RoomCodeGenerationFailedException;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.LobbySnapshot;
import org.springframework.stereotype.Service;

@Service
public class GameLobbyService {

    private static final int MAX_ROOM_CODE_ATTEMPTS = 10;

    private final GameRoomRepository gameRoomRepository;
    private final RoomCodeGenerator roomCodeGenerator;
    private final GameEventPublisher eventPublisher;
    private final GameStartPolicy gameStartPolicy;

    public GameLobbyService(
            GameRoomRepository gameRoomRepository,
            RoomCodeGenerator roomCodeGenerator,
            GameEventPublisher eventPublisher,
            GameStartPolicy gameStartPolicy
    ) {
        this.gameRoomRepository = gameRoomRepository;
        this.roomCodeGenerator = roomCodeGenerator;
        this.eventPublisher = eventPublisher;
        this.gameStartPolicy = gameStartPolicy;
    }

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

    private GameRoom createRoomWithUniqueCode(Player host) {
        for (int attempt = 0; attempt < MAX_ROOM_CODE_ATTEMPTS; attempt++) {
            GameRoom room = GameRoom.create(roomCodeGenerator.generate(), host, gameStartPolicy);
            if (gameRoomRepository.saveIfAbsent(room)) {
                return room;
            }
        }
        throw new RoomCodeGenerationFailedException();
    }
}
