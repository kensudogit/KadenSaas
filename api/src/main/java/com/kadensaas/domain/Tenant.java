package com.kadensaas.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;

/**
 * テナント。架電の関門が使う設定をここが持つ。
 *
 * <p>★ 架電可能時間や上限回数をアプリの定数にしない。業種によって
 * 許される時間帯が違い、テナントごとに変わる。定数にすると
 * 「1 社のために全体を変える」ことになる。
 */
@Entity
@Table(name = "tenants")
@Getter
@Setter
public class Tenant {

    @Id
    @GeneratedValue
    private UUID id;

    private String name;
    private String slug;

    private String timezone;

    @Column(name = "calling_hours_start")
    private LocalTime callingHoursStart;

    @Column(name = "calling_hours_end")
    private LocalTime callingHoursEnd;

    @Column(name = "calling_weekdays", columnDefinition = "int[]")
    private int[] callingWeekdays;

    @Column(name = "exclude_holidays")
    private boolean excludeHolidays;

    @Column(name = "max_attempts_per_day")
    private int maxAttemptsPerDay;

    @Column(name = "max_attempts_total")
    private int maxAttemptsTotal;

    @Column(name = "recording_retention_days")
    private int recordingRetentionDays;

    private String status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
