package com.blackboard.targetplanner.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 目标选择统计信息。
 *
 * <p>这些字段只用于日志、实验报告和验收展示，不改变 CarID:Target 的原有格式。</p>
 */
public class TargetSelectionMetrics {
    private final String strategy;
    private final int frontierCount;
    private final int candidateCount;
    private final int filteredCandidateCount;
    private final int occupiedCarCount;
    private final int reservedTargetCount;
    private final double selectedScore;
    private final int selectedInformationGain;
    private final long elapsedMillis;

    public TargetSelectionMetrics(String strategy,
                                  int frontierCount,
                                  int candidateCount,
                                  int filteredCandidateCount,
                                  int occupiedCarCount,
                                  int reservedTargetCount,
                                  double selectedScore,
                                  int selectedInformationGain,
                                  long elapsedMillis) {
        this.strategy = strategy;
        this.frontierCount = frontierCount;
        this.candidateCount = candidateCount;
        this.filteredCandidateCount = filteredCandidateCount;
        this.occupiedCarCount = occupiedCarCount;
        this.reservedTargetCount = reservedTargetCount;
        this.selectedScore = selectedScore;
        this.selectedInformationGain = selectedInformationGain;
        this.elapsedMillis = elapsedMillis;
    }

    public String getStrategy() {
        return strategy;
    }

    public int getFrontierCount() {
        return frontierCount;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public int getFilteredCandidateCount() {
        return filteredCandidateCount;
    }

    public int getOccupiedCarCount() {
        return occupiedCarCount;
    }

    public int getReservedTargetCount() {
        return reservedTargetCount;
    }

    public double getSelectedScore() {
        return selectedScore;
    }

    public int getSelectedInformationGain() {
        return selectedInformationGain;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("strategy", strategy);
        map.put("frontierCount", frontierCount);
        map.put("candidateCount", candidateCount);
        map.put("filteredCandidateCount", filteredCandidateCount);
        map.put("occupiedCarCount", occupiedCarCount);
        map.put("reservedTargetCount", reservedTargetCount);
        map.put("selectedScore", selectedScore);
        map.put("selectedInformationGain", selectedInformationGain);
        map.put("elapsedMs", elapsedMillis);
        return map;
    }
}
