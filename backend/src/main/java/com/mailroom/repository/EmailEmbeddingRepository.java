package com.mailroom.repository;

import com.mailroom.entity.EmailEmbedding;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface EmailEmbeddingRepository extends JpaRepository<EmailEmbedding, UUID> {

    List<EmailEmbedding> findByEmailIdInAndModel(Collection<UUID> emailIds, String model);

    /**
     * Emails in this mailbox with no vector for the current model, newest
     * first — recent mail is what gets asked about, so it is what should
     * become searchable first.
     */
    @Query("""
            select e.id from EmailMessage e
            where e.mailAccount.id = :mailAccountId
              and not exists (
                select 1 from EmailEmbedding v
                where v.emailId = e.id and v.model = :model
              )
            order by e.receivedAt desc
            """)
    List<UUID> findUnembedded(UUID mailAccountId, String model, Pageable pageable);

    @Query("select count(v) from EmailEmbedding v where v.model = :model")
    long countByModel(String model);
}
