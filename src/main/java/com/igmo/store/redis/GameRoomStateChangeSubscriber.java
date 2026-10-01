package com.igmo.store.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igmo.domain.GamePhase;
import com.igmo.domain.GameRoom;
import com.igmo.service.GameEventPublisher;
import com.igmo.service.GameRoomStateSyncService;
import com.igmo.store.GameRoomDelivery;
import com.igmo.store.GameRoomRepository;
import com.igmo.web.websocket.message.RoomMessage;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class GameRoomStateChangeSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final GameRoomStateChangePublisher publisher;
    private final GameRoomRepository gameRoomRepository;
    private final GameRoomStateSyncService stateSyncService;
    private final GameEventPublisher gameEventPublisher;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            GameRoomStateChangedMessage stateChanged = objectMapper.readValue(
                    message.getBody(),
                    GameRoomStateChangedMessage.class
            );
            if (publisher.isPublishedByThisInstance(stateChanged.sourceInstanceId())) {
                return;
            }
            long eventVersion = stateChanged.roomVersion() == 0 ? Long.MAX_VALUE : stateChanged.roomVersion();
            var room = gameRoomRepository.synchronizeFromRedisAndGet(
                    stateChanged.roomCode(), eventVersion);
            for (GameRoomDelivery delivery : stateChanged.deliveries()) {
                publishDelivery(delivery, room.orElse(null), stateChanged.roomCode());
            }
        } catch (IOException exception) {
            log.warn("게임방 상태 변경 이벤트를 해석할 수 없습니다.", exception);
        } catch (RuntimeException exception) {
            log.warn("게임방 상태를 Redis에서 동기화할 수 없습니다.", exception);
        }
    }

    private void publishDelivery(GameRoomDelivery delivery, GameRoom room, String roomCode) {
        switch (delivery.type()) {
            case ROOM_SNAPSHOT -> publishRoomSnapshot(room);
            case IMAGE_RESULT -> publishImageResult(room, delivery.playerId());
            case OWN_VOTE_OPTIONS -> publishOwnVoteOptions(room);
            case GAME_RESULT -> publishGameResult(room, delivery, roomCode);
            case ROOM_REMOVED -> {
                // Redis 동기화 단계에서 로컬 캐시를 제거한다. 방이 이미 복구됐으면 오래된 삭제 알림이다.
            }
            case LOBBY_EXPIRED -> {
                if (room == null) {
                    gameEventPublisher.publishLobbyExpired(roomCode);
                }
            }
        }
    }

    private void publishRoomSnapshot(GameRoom room) {
        if (room != null) {
            stateSyncService.publishRoomSnapshot(room);
        }
    }

    private void publishImageResult(GameRoom room, String playerId) {
        if (room != null && playerId != null) {
            stateSyncService.publishImageResult(room, playerId);
        }
    }

    private void publishOwnVoteOptions(GameRoom room) {
        if (room != null) {
            stateSyncService.publishOwnVoteOptions(room);
        }
    }

    private void publishGameResult(GameRoom room, GameRoomDelivery delivery, String roomCode) {
        if (room != null && room.getPhase() == GamePhase.ENDED) {
            stateSyncService.publishRoomSnapshot(room);
        } else if (room == null && delivery.gameResultSnapshot() != null) {
            gameEventPublisher.publish(
                    roomCode,
                    RoomMessage.gameResultSnapshot(delivery.gameResultSnapshot()));
        }
    }
}
