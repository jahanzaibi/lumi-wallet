package com.lumi.wallet.event.outbound;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    /** Oldest first, so events reach the broker in roughly the order they happened. */
    @Query("""
            select e from OutboxEvent e
            where e.status = com.lumi.wallet.event.outbound.OutboxStatus.PENDING
            order by e.createdAt, e.id
            """)
    List<OutboxEvent> findPending(Limit limit);

    List<OutboxEvent> findByStatus(OutboxStatus status);

    List<OutboxEvent> findByEventTypeOrderByCreatedAt(String eventType);
}
