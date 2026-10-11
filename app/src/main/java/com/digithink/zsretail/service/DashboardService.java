package com.digithink.zsretail.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.dto.DashboardTodayDTO;
import com.digithink.zsretail.model.ReturnHeader;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.enumeration.SessionStatus;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.repository.CashierSessionRepository;
import com.digithink.zsretail.repository.ReturnHeaderRepository;
import com.digithink.zsretail.repository.SalesHeaderRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DashboardService {

    @Autowired
    private SalesHeaderRepository salesHeaderRepository;

    @Autowired
    private ReturnHeaderRepository returnHeaderRepository;

    @Autowired
    private CashierSessionRepository cashierSessionRepository;

    public DashboardTodayDTO getTodayStats() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);

        // Today's completed sales
        List<SalesHeader> todaySales = salesHeaderRepository
                .findBySalesDateBetweenAndStatus(startOfDay, endOfDay, TransactionStatus.COMPLETED);
        long todaySalesCount = todaySales.size();
        double todaySalesAmount = todaySales.stream()
                .mapToDouble(s -> s.getTotalAmount() != null ? s.getTotalAmount() : 0.0)
                .sum();

        // Currently open sessions
        long openSessionsCount = cashierSessionRepository.findByStatus(SessionStatus.OPENED).size();

        // Today's returns (completed returns created today)
        List<ReturnHeader> todayReturns = returnHeaderRepository.findByCreatedAtAfter(startOfDay);
        long todayReturnsCount = todayReturns.size();
        double todayReturnsAmount = todayReturns.stream()
                .mapToDouble(r -> r.getTotalReturnAmount() != null ? r.getTotalReturnAmount() : 0.0)
                .sum();

        // Pending (saved) tickets
        long pendingTicketsCount = salesHeaderRepository.findByStatus(TransactionStatus.PENDING).size();

        return new DashboardTodayDTO(
                todaySalesCount, todaySalesAmount,
                openSessionsCount,
                todayReturnsCount, todayReturnsAmount,
                pendingTicketsCount
        );
    }
}
