package com.igmo.service;

import com.igmo.domain.GameRoom;
import com.igmo.domain.Player;
import com.igmo.exception.RoomNotFoundException;
import com.igmo.store.GameRegistry;
import com.igmo.web.dto.CreateGameResponse;
import com.igmo.web.dto.JoinGameResponse;
import com.igmo.web.dto.LobbySnapshot;
import org.springframework.stereotype.Service;

@Service
public class GameService {

    private final GameRegistry gameRegistry;
    private final RoomCodeGenerator roomCodeGenerator;

    public GameService(GameRegistry gameRegistry, RoomCodeGenerator roomCodeGenerator) {
        this.gameRegistry = gameRegistry;
        this.roomCodeGenerator = roomCodeGenerator;
    }

    public CreateGameResponse createGame(String nickname) {
        Player host = new Player(nickname);
        GameRoom room = createRoomWithUniqueCode(host);
        return new CreateGameResponse(room.getCode(), host.getId());
    }

    public JoinGameResponse joinGame(String code, String nickname) {
        GameRoom room = gameRegistry.find(code)
                .orElseThrow(RoomNotFoundException::new);
        String playerId = room.addPlayer(new Player(nickname));
        return new JoinGameResponse(playerId, LobbySnapshot.from(room));
    }

    private GameRoom createRoomWithUniqueCode(Player host) {
        GameRoom room;
        do {
            room = GameRoom.create(roomCodeGenerator.generate(), host);
        } while (!gameRegistry.saveIfAbsent(room));
        return room;
    }
}
