package com.igmo.web.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class WebSocketSessionRegistryTest {

    private final WebSocketSessionRegistry registry = new WebSocketSessionRegistry();

    @Test
    @DisplayName("열린 WebSocket 세션을 등록하고 해제한다.")
    void registerAndUnregister_세션을_추적한다() {
        // given
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);

        // when
        registry.register(session);

        // then
        assertThat(registry.count()).isOne();
        registry.unregister("session-1");
        assertThat(registry.count()).isZero();
    }

    @Test
    @DisplayName("등록한 세션이 없으면 개수는 0이다.")
    void count_빈_레지스트리는_0이다() {
        assertThat(registry.count()).isZero();
    }

    @Test
    @DisplayName("동일 세션을 중복 등록해도 한 개로 센다.")
    void register_동일_세션의_중복_등록은_개수를_늘리지_않는다() {
        // given
        WebSocketSession session = session("one", true);
        registry.register(session);

        // when
        registry.register(session);

        // then
        assertThat(registry.count()).isOne();
    }

    @Test
    @DisplayName("동일 ID로 다른 세션을 등록하면 이후 종료 대상은 새 세션이다.")
    void register_동일_ID는_새_세션으로_교체한다() throws IOException {
        // given
        WebSocketSession original = session("one", true);
        WebSocketSession replacement = session("one", true);
        registry.register(original);

        // when
        registry.register(replacement);
        registry.closeAll();

        // then
        assertThat(registry.count()).isOne();
        verify(original, never()).close(CloseStatus.GOING_AWAY);
        verify(replacement).close(CloseStatus.GOING_AWAY);
    }

    @Test
    @DisplayName("등록 해제는 연결을 닫지 않고 해당 세션만 추적에서 제거한다.")
    void unregister_대상만_제거하고_연결은_닫지_않는다() throws IOException {
        // given
        WebSocketSession removed = session("one", true);
        WebSocketSession remaining = session("two", true);
        registry.register(removed);
        registry.register(remaining);

        // when
        registry.unregister("one");
        registry.closeAll();

        // then
        assertThat(registry.count()).isOne();
        verify(removed, never()).close(CloseStatus.GOING_AWAY);
        verify(remaining).close(CloseStatus.GOING_AWAY);
    }

    @Test
    @DisplayName("알 수 없는 ID와 이미 해제한 ID를 해제해도 나머지 세션은 유지한다.")
    void unregister_없는_ID와_중복_해제를_무시한다() {
        // given
        registry.register(session("one", true));
        registry.register(session("two", true));
        registry.unregister("one");

        // when
        registry.unregister("one");
        registry.unregister("unknown");

        // then
        assertThat(registry.count()).isOne();
    }

    @Test
    @DisplayName("개수 조회 시 닫힌 세션을 제거하고 열린 세션만 센다.")
    void count_닫힌_세션은_추적에서_제거한다() throws IOException {
        // given
        WebSocketSession closed = session("closed", false);
        registry.register(closed);
        registry.register(session("open", true));

        // when
        int count = registry.count();
        when(closed.isOpen()).thenReturn(true);
        registry.closeAll();

        // then
        assertThat(count).isOne();
        assertThat(registry.count()).isOne();
        verify(closed, never()).close(CloseStatus.GOING_AWAY);
    }

    @Test
    @DisplayName("등록 이후 연결이 닫히면 다음 개수 조회에서 제외한다.")
    void count_등록_후_종료된_세션을_제외한다() {
        // given
        WebSocketSession session = session("one", true);
        registry.register(session);

        // when
        when(session.isOpen()).thenReturn(false);

        // then
        assertThat(registry.count()).isZero();
    }

    @Test
    @DisplayName("선택 종료는 요청한 ID만 GOING_AWAY 상태로 닫고 없는 ID는 무시한다.")
    void close_선택한_세션만_닫는다() throws IOException {
        // given
        WebSocketSession selected = session("one", true);
        WebSocketSession other = session("two", true);
        registry.register(selected);
        registry.register(other);

        // when
        registry.close(Set.of("one", "unknown"));

        // then
        verify(selected).close(CloseStatus.GOING_AWAY);
        verify(other, never()).close(CloseStatus.GOING_AWAY);
    }

    @Test
    @DisplayName("빈 ID 집합으로 종료를 요청하면 연결을 닫지 않는다.")
    void close_빈_집합은_아무것도_닫지_않는다() throws IOException {
        // given
        WebSocketSession session = session("one", true);
        registry.register(session);

        // when
        registry.close(Set.of());

        // then
        verify(session, never()).close(CloseStatus.GOING_AWAY);
        assertThat(registry.count()).isOne();
    }

    @Test
    @DisplayName("이미 닫힌 세션은 중복 종료하지 않고 추적에서 제거한다.")
    void close_이미_닫힌_세션을_제거한다() throws IOException {
        // given
        WebSocketSession closed = session("one", false);
        registry.register(closed);

        // when
        registry.close(Set.of("one"));
        when(closed.isOpen()).thenReturn(true);

        // then
        assertThat(registry.count()).isZero();
        verify(closed, never()).close(CloseStatus.GOING_AWAY);
    }

    @Test
    @DisplayName("종료 요청 후에도 연결이 열려 있으면 완료된 것으로 세지 않는다.")
    void close_종료_요청만으로_열린_세션을_제거하지_않는다() {
        // given
        registry.register(session("one", true));

        // when
        registry.close(Set.of("one"));

        // then
        assertThat(registry.count()).isOne();
    }

    @Test
    @DisplayName("종료 요청으로 실제 연결이 닫히면 개수 조회에서 제거한다.")
    void close_연결이_닫히면_개수는_0이_된다() throws IOException {
        // given
        WebSocketSession session = session("one", true);
        registry.register(session);
        doAnswer(invocation -> {
            when(session.isOpen()).thenReturn(false);
            return null;
        }).when(session).close(CloseStatus.GOING_AWAY);

        // when
        registry.close(Set.of("one"));

        // then
        assertThat(registry.count()).isZero();
    }

    @Test
    @DisplayName("선택 종료 중 IOException이 발생해도 다음 세션을 종료하고 실패한 세션은 유지한다.")
    void close_IO예외_후에도_다음_세션을_종료한다() throws IOException {
        // given
        WebSocketSession failing = session("fail", true);
        WebSocketSession next = session("next", true);
        registry.register(failing);
        registry.register(next);
        doThrow(new IOException("종료 실패")).when(failing).close(CloseStatus.GOING_AWAY);
        Set<String> ids = new LinkedHashSet<>(List.of("fail", "next"));

        // when & then
        assertThatCode(() -> registry.close(ids)).doesNotThrowAnyException();
        verify(failing).close(CloseStatus.GOING_AWAY);
        verify(next).close(CloseStatus.GOING_AWAY);
        assertThat(registry.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("빈 레지스트리의 전체 종료는 예외 없이 완료한다.")
    void closeAll_빈_레지스트리는_예외가_없다() {
        assertThatCode(registry::closeAll).doesNotThrowAnyException();
        assertThat(registry.count()).isZero();
    }

    @Test
    @DisplayName("전체 종료는 열린 모든 세션에 GOING_AWAY를 전달하고 닫힌 세션은 제외한다.")
    void closeAll_열린_세션을_모두_종료한다() throws IOException {
        // given
        WebSocketSession first = session("one", true);
        WebSocketSession second = session("two", true);
        WebSocketSession closed = session("closed", false);
        registry.register(first);
        registry.register(second);
        registry.register(closed);

        // when
        registry.closeAll();

        // then
        verify(first).close(CloseStatus.GOING_AWAY);
        verify(second).close(CloseStatus.GOING_AWAY);
        verify(closed, never()).close(CloseStatus.GOING_AWAY);
    }

    @Test
    @DisplayName("전체 종료 중 일부 세션에 IOException이 발생해도 다른 세션에 종료를 요청한다.")
    void closeAll_일부_IO예외에도_모든_세션에_종료를_요청한다() throws IOException {
        // given
        WebSocketSession failing = session("fail", true);
        WebSocketSession healthy = session("healthy", true);
        registry.register(failing);
        registry.register(healthy);
        doThrow(new IOException("종료 실패")).when(failing).close(CloseStatus.GOING_AWAY);

        // when & then
        assertThatCode(registry::closeAll).doesNotThrowAnyException();
        verify(failing).close(CloseStatus.GOING_AWAY);
        verify(healthy).close(CloseStatus.GOING_AWAY);
    }

    @Test
    @DisplayName("전체 종료 도중 연결 종료 콜백이 등록을 해제해도 나머지 세션을 종료한다.")
    void closeAll_종료_콜백에서_등록을_해제할_수_있다() throws IOException {
        // given
        WebSocketSession first = session("one", true);
        WebSocketSession second = session("two", true);
        registry.register(first);
        registry.register(second);
        doAnswer(invocation -> {
            registry.unregister("one");
            return null;
        }).when(first).close(CloseStatus.GOING_AWAY);
        doAnswer(invocation -> {
            registry.unregister("two");
            return null;
        }).when(second).close(CloseStatus.GOING_AWAY);

        // when
        registry.closeAll();

        // then
        verify(first).close(CloseStatus.GOING_AWAY);
        verify(second).close(CloseStatus.GOING_AWAY);
        assertThat(registry.count()).isZero();
    }

    private WebSocketSession session(String id, boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(open);
        return session;
    }
}
