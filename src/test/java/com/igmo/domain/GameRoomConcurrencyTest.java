package com.igmo.domain;

import com.igmo.exception.RoomFullException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GameRoomConcurrencyTest {

    @Test
    @DisplayName("여러 명이 동시에 입장해도 정원(8명)을 초과하지 않는다.")
    void addPlayer_동시_입장에도_정원을_초과하지_않는다() throws InterruptedException {
        // given
        GameRoom room = GameRoom.create("ABCD", new Player("호스트"));
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();

        // when
        for (int i = 0; i < threadCount; i++) {
            int index = i;
            executor.submit(() -> {
                try {
                    start.await();
                    room.addPlayer(new Player("참가자" + index));
                    success.incrementAndGet();
                } catch (RoomFullException e) {
                    full.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        // then
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(room.getPlayers()).hasSize(8);
            softly.assertThat(success.get()).isEqualTo(7);
            softly.assertThat(full.get()).isEqualTo(threadCount - 7);
        });
    }
}
