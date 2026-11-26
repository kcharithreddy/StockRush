package com.flashsale.sale.queue;

import com.flashsale.sale.AclRedis;
import com.flashsale.sale.buy.BuyOutcome;
import com.flashsale.sale.buy.BuyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** The waiting room end to end on Redis with the production ACL (sale-service's own user). */
@SpringBootTest(properties = {"spring.kafka.admin.auto-create=false", "flashsale.waiting-room.admit-per-second=3",
        "flashsale.waiting-room.scheduler-enabled=false"})
class WaitingRoomTest {

    private static final long GATED = 7;
    private static final long OPEN = 8;

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        AclRedis.register(registry);
    }

    @MockitoBean
    KafkaTemplate<String, String> kafka;

    @Autowired
    WaitingRoomService waitingRoom;

    @Autowired
    AdmissionJob admission;

    @Autowired
    BuyService buyService;

    private final StringRedisTemplate admin = AclRedis.admin();

    /** What order-service's warm-up does. */
    private void openSale(long saleId, int stock, boolean waitingRoom, long startsInMillis) {
        long now = System.currentTimeMillis();
        admin.opsForValue().set("sale:" + saleId + ":stock", Integer.toString(stock));
        admin.opsForValue().set("sale:" + saleId + ":window", (now + startsInMillis) + "," + (now + 3_600_000));
        if (waitingRoom) {
            admin.opsForValue().set("sale:" + saleId + ":gate", "1");
            admin.opsForSet().add("sale:gated", Long.toString(saleId));
        }
    }

    private void waitForNextSecond() throws InterruptedException {
        Thread.sleep(1000 - System.currentTimeMillis() % 1000 + 20);
    }

    @BeforeEach
    void setUp() {
        AclRedis.flushAll();
        buyService.clearSoldOutFlags();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void saleWithoutWaitingRoomSellsDirectly() {
        openSale(OPEN, 5, false, -1000);
        UUID user = UUID.randomUUID();

        assertThat(waitingRoom.join(OPEN, user).status()).isEqualTo("NO_WAITING_ROOM");
        assertThat(buyService.buy(OPEN, user).outcome()).isEqualTo(BuyOutcome.SUCCESS);
    }

    @Test
    void gatedSaleOnlySellsToAdmittedBuyersInQueueOrder() throws Exception {
        openSale(GATED, 10, true, -1000);
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            users.add(UUID.randomUUID());
        }

        // Skipping the queue does not work.
        assertThat(buyService.buy(GATED, users.get(0)).outcome()).isEqualTo(BuyOutcome.NOT_ADMITTED);

        for (int i = 0; i < users.size(); i++) {
            assertThat(waitingRoom.join(GATED, users.get(i))).isEqualTo(new QueueStatus("WAITING", (long) i + 1));
            Thread.sleep(1); // after the start: first come, first served
        }
        // Joining twice keeps one's place.
        assertThat(waitingRoom.join(GATED, users.get(2)).position()).isEqualTo(3L);

        waitForNextSecond();
        admission.admit();
        admission.admit(); // same second: another instance's call admits nobody

        // Batch of 3: the first three are in, the rest move up the line.
        for (int i = 0; i < 3; i++) {
            assertThat(waitingRoom.status(GATED, users.get(i)).status()).isEqualTo("ADMITTED");
        }
        assertThat(waitingRoom.status(GATED, users.get(3))).isEqualTo(new QueueStatus("WAITING", 1L));
        assertThat(buyService.buy(GATED, users.get(0)).outcome()).isEqualTo(BuyOutcome.SUCCESS);
        assertThat(buyService.buy(GATED, users.get(3)).outcome()).isEqualTo(BuyOutcome.NOT_ADMITTED);

        waitForNextSecond();
        admission.admit();
        assertThat(waitingRoom.status(GATED, users.get(4)).status()).isEqualTo("ADMITTED");
    }

    @Test
    void earlyArrivalsAreShuffledLaterOnesQueueBehindThem() {
        openSale(GATED, 10, true, 60_000); // opens in a minute
        List<String> early = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID user = UUID.randomUUID();
            waitingRoom.join(GATED, user);
            early.add(user.toString());
        }

        List<String> line = new ArrayList<>(admin.opsForZSet().range("sale:" + GATED + ":queue", 0, -1));
        assertThat(line).containsExactlyInAnyOrderElementsOf(early);
        assertThat(line).as("lottery, not arrival order").isNotEqualTo(early);
        assertThat(admin.opsForZSet().score("sale:" + GATED + ":queue", early.get(0))).isLessThan(1e9);

        // Nobody is admitted, and nobody can buy, before the sale opens.
        admission.admit();
        assertThat(admin.opsForSet().size("sale:" + GATED + ":admitted")).isZero();
    }

    @Test
    void soldOutSaleStopsAdmittingAndSaysSo() throws Exception {
        openSale(GATED, 0, true, -1000);
        UUID user = UUID.randomUUID();

        assertThat(waitingRoom.join(GATED, user).status()).isEqualTo("SOLD_OUT");
        waitForNextSecond();
        admission.admit();
        assertThat(admin.opsForSet().size("sale:" + GATED + ":admitted")).isZero();
    }

    @Test
    void statusWithoutJoiningIsNotInQueue() {
        openSale(GATED, 10, true, -1000);
        assertThat(waitingRoom.status(GATED, UUID.randomUUID()).status()).isEqualTo("NOT_IN_QUEUE");
    }
}
