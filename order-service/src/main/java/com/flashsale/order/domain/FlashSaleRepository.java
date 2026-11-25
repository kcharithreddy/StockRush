package com.flashsale.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface FlashSaleRepository extends JpaRepository<FlashSale, Long> {

    @Query("select s from FlashSale s where s.startsAt <= :now and s.endsAt >= :now")
    List<FlashSale> findActiveAt(@Param("now") Instant now);
}
