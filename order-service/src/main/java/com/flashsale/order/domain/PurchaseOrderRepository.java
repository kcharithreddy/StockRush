package com.flashsale.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByOrderToken(UUID orderToken);

    long countBySaleIdAndStatusIn(long saleId, Collection<OrderStatus> statuses);

    @Modifying
    @Query("delete from PurchaseOrder o where o.saleId = :saleId")
    int deleteBySaleId(@Param("saleId") long saleId);
}
