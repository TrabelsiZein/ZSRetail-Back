package com.digithink.zsretail.dto;

public class DashboardTodayDTO {

    private long todaySalesCount;
    private double todaySalesAmount;
    private long openSessionsCount;
    private long todayReturnsCount;
    private double todayReturnsAmount;
    private long pendingTicketsCount;

    public DashboardTodayDTO() {}

    public DashboardTodayDTO(long todaySalesCount, double todaySalesAmount,
                              long openSessionsCount,
                              long todayReturnsCount, double todayReturnsAmount,
                              long pendingTicketsCount) {
        this.todaySalesCount = todaySalesCount;
        this.todaySalesAmount = todaySalesAmount;
        this.openSessionsCount = openSessionsCount;
        this.todayReturnsCount = todayReturnsCount;
        this.todayReturnsAmount = todayReturnsAmount;
        this.pendingTicketsCount = pendingTicketsCount;
    }

    public long getTodaySalesCount() { return todaySalesCount; }
    public void setTodaySalesCount(long todaySalesCount) { this.todaySalesCount = todaySalesCount; }

    public double getTodaySalesAmount() { return todaySalesAmount; }
    public void setTodaySalesAmount(double todaySalesAmount) { this.todaySalesAmount = todaySalesAmount; }

    public long getOpenSessionsCount() { return openSessionsCount; }
    public void setOpenSessionsCount(long openSessionsCount) { this.openSessionsCount = openSessionsCount; }

    public long getTodayReturnsCount() { return todayReturnsCount; }
    public void setTodayReturnsCount(long todayReturnsCount) { this.todayReturnsCount = todayReturnsCount; }

    public double getTodayReturnsAmount() { return todayReturnsAmount; }
    public void setTodayReturnsAmount(double todayReturnsAmount) { this.todayReturnsAmount = todayReturnsAmount; }

    public long getPendingTicketsCount() { return pendingTicketsCount; }
    public void setPendingTicketsCount(long pendingTicketsCount) { this.pendingTicketsCount = pendingTicketsCount; }
}
