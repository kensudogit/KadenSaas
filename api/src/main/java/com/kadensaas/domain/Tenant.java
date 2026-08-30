package com.kadensaas.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;


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

    // ★ calling_hours_start / calling_hours_end はあえて写像しない。
    //
    //   hibernate.jdbc.time_zone: UTC は timestamptz のために要るが、
    //   time 型の列にも効いてしまう。結果、JST の端末では 09:00 と設定した
    //   値が DB に 00:00 で入り、読むときに 09:00 に戻る。往復では辻褄が
    //   合うので気付かないが、DB の中身は間違っており、SQL で直接読む
    //   診断画面（TelephonySettingsService#diagnose）とは 9 時間ずれる。
    //   コンテナのタイムゾーンが変われば、架電可能時間そのものがずれる。
    //
    //   壁時計の時刻は JVM のタイムゾーンを経由させない。判定はテナントの
    //   タイムゾーンで DB 側に計算させる（DialingGate#checkCallingWindow）。
    //   挿入時は DB の default（09:00-20:00）が入る。

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
