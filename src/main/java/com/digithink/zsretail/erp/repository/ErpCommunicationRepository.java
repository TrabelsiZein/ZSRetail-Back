package com.digithink.zsretail.erp.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.erp.enumeration.ErpSyncOperation;
import com.digithink.zsretail.erp.model.ErpCommunication;
import com.digithink.zsretail.repository._BaseRepository;

@Repository
public interface ErpCommunicationRepository extends _BaseRepository<ErpCommunication, Long> {

    List<ErpCommunication> findByStartedAtBetweenOrderByStartedAtDesc(LocalDateTime start, LocalDateTime end);

    List<ErpCommunication> findByOperationAndStartedAtBetweenOrderByStartedAtDesc(
            ErpSyncOperation operation, LocalDateTime start, LocalDateTime end);

    List<ErpCommunication> findByOperationInAndStartedAtBetweenOrderByStartedAtDesc(
            List<ErpSyncOperation> operations, LocalDateTime start, LocalDateTime end);

    List<ErpCommunication> findByOperationOrderByStartedAtDesc(ErpSyncOperation operation);

    List<ErpCommunication> findByOperationInOrderByStartedAtDesc(List<ErpSyncOperation> operations);
}

