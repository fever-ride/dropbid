package com.dropbid.payment.repository;

import com.dropbid.payment.model.Payment;
import com.dropbid.payment.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    List<Payment> findByAuctionId(String auctionId);

    java.util.Optional<Payment> findByAuctionIdAndUserId(String auctionId, String userId);

    List<Payment> findByUserId(String userId);

    /** Used by the recovery job: find stuck PENDING or PROCESSING payments older than threshold. */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.status IN (:statuses)
            AND p.createdAt < :threshold
            """)
    List<Payment> findStuckPayments(
            @Param("statuses") List<PaymentStatus> statuses,
            @Param("threshold") Instant threshold
    );
}
