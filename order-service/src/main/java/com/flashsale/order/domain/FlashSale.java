package com.flashsale.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "flash_sales")
public class FlashSale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(name = "total_stock", nullable = false)
    private int totalStock;

    @Column(name = "sale_price_paise", nullable = false)
    private long salePricePaise;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "waiting_room", nullable = false)
    private boolean waitingRoom;

    protected FlashSale() {
    }

    public Long getId() {
        return id;
    }

    public long getProductId() {
        return productId;
    }

    public int getTotalStock() {
        return totalStock;
    }

    public long getSalePricePaise() {
        return salePricePaise;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public boolean hasWaitingRoom() {
        return waitingRoom;
    }

    public boolean isActiveAt(Instant now) {
        return !now.isBefore(startsAt) && !now.isAfter(endsAt);
    }
}
