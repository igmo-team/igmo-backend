# IGMO WebSocket API

이 문서는 실제 STOMP E2E 테스트에서 수신·검증한 프레임을 바탕으로 생성됩니다.

## Getting Started

### WebSocket Endpoint

`{{serverUrl}}`

### STOMP CONNECT native headers

다음 값은 HTTP WebSocket Handshake Header가 아닙니다. WebSocket 연결 이후 보내는 STOMP `CONNECT` frame의 native header입니다.

| Header | 의미 |
| --- | --- |
| `roomCode` | 연결할 게임방 코드 |
| `playerId` | 현재 플레이어 식별자 |
| `secret` | 플레이어 인증을 위한 secret |

서버는 세 값으로 게임방과 플레이어를 검증한 뒤, 유효한 `playerId`를 STOMP 사용자로 바인딩합니다. `/user/queue/*`는 이 사용자에게만 전달됩니다.

### `@stomp/stompjs` 연결 예시

```ts
import { Client } from '@stomp/stompjs';

const client = new Client({
  brokerURL: '{{serverUrl}}',
  connectHeaders: {
    roomCode: 'ROOM123',
    playerId: 'PLAYER_01',
    secret: 'SECRET_TOKEN',
  },
  onConnect: () => {
    console.log('STOMP connected');
  },
});

client.activate();
```

### 구독 예시

`onConnect` 이후 public topic과 필요한 개인 큐를 먼저 구독합니다.

```ts
client.subscribe('/topic/rooms/ROOM123', message => {
  const event = JSON.parse(message.body);
  console.log(event);
});

client.subscribe('/user/queue/image-generation', message => {
  const event = JSON.parse(message.body);
  console.log(event);
});

client.subscribe('/user/queue/guess-submission', message => {
  const event = JSON.parse(message.body);
  console.log(event);
});

client.subscribe('/user/queue/vote-own-option', message => {
  const event = JSON.parse(message.body);
  console.log(event);
});

client.subscribe('/user/queue/errors', message => {
  const error = JSON.parse(message.body);
  console.error(error.message);
});
```

`/user/queue/errors`는 모든 STOMP SEND에서 발생할 수 있는 개인 오류 채널입니다. SEND 전에 구독합니다.

## General Notes

### 비동기 메시지 처리

STOMP SEND 이후 발생하는 서버 이벤트는 HTTP request-response 관계처럼 동작하지 않습니다.

### 메시지 도착 순서

하나의 행동으로 여러 Destination에서 이벤트가 발생할 수 있습니다. 명시적으로 보장된 경우가 아니라면 각 이벤트의 도착 순서에 의존하면 안 됩니다.

### 메시지 구분 기준

클라이언트는 destination과 `type` 또는 `status`를 기준으로 메시지를 분기합니다.

### Broadcast와 User Queue 차이

- `/topic/rooms/{roomCode}`: 같은 게임방 참가자 전체에게 전달되는 Broadcast
- `/user/queue/*`: 현재 연결된 특정 STOMP 사용자에게 전달되는 개인 메시지
